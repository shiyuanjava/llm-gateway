# LLM Gateway：从 GitLab 到 Docker Compose、K3s 的完整部署教程

本文不是只给一组可复制的命令，而是解释项目如何被构建、交付、持久化，以及以后如何用同样的方法部署其他项目。三条硬约束贯穿全文，它们也是大多数个人云服务器的真实约束：

1. **服务器硬盘小**：不在服务器上保留完整源码副本，不在服务器上编译。构建在 CI 里完成，服务器只拉取构建好的镜像；持久落盘的部署文件只有 `/opt/llm-gateway` 下的三个小文件加数据卷。
2. **密钥不进仓库、不进 CI 变量**：应用密钥（JWT、管理员账号、供应商 API Key）统一放在 Nacos 配置中心，控制台里填一次；只有容器启动前就必须存在的基础设施密码（MySQL/Redis）放在服务器本地的一个小 `.env` 文件里。
3. **每次发布可追溯、可回滚**：镜像用提交 SHA 打标签，回滚就是重跑旧流水线或 `rollout undo`。

配套文件：

- 后端镜像：`llm-gateway/Dockerfile`
- 前端镜像：`llm-gateway-ui/Dockerfile`
- 单机编排：`llm-gateway/docker-compose.yml`
- Nacos 初始化模板：`llm-gateway/deploy/nacos-init/init.sh`
- GitLab 流水线：`.gitlab-ci.yml`
- Docker 安装脚本：`deploy/scripts/install-docker-ubuntu.sh`
- K3s 安装脚本：`deploy/scripts/install-k3s-ubuntu.sh`
- Kubernetes 清单：`deploy/k8s/base/`

## 1. 先理解最终结构

```text
开发电脑
  └─ git push
       ↓
自建 GitLab（代码、流水线、Container Registry）
  └─ GitLab Runner
       ├─ verify:后端测试(Maven + 临时 MySQL)、前端构建检查
       ├─ image: 在 Docker-in-Docker 里构建 gateway/ui 镜像 → 推到 Registry
       └─ deploy(手动触发,二选一)
           ├─ Compose:服务器只同步 2 个文件 + 拉镜像 + up -d
           └─ K3s:  kubectl apply + set image 滚动更新

服务器上真正落盘的东西：
  /opt/llm-gateway/.env                    ← 基础设施密码(手工创建一次)
  /opt/llm-gateway/docker-compose.yml      ← CI 每次部署时同步
  /opt/llm-gateway/deploy/nacos-init/init.sh
  Docker named volumes                     ← MySQL/Nacos 数据、网关日志

运行时：
浏览器 -> ui/nginx -> gateway -> MySQL
                         ├──────> Redis
                         ├──────> Nacos(动态配置 + 应用密钥 + Sentinel 规则)
                         └──────> Sentinel Dashboard
```

各组件职责：

| 组件 | 作用 | 是否必须持久化 |
|---|---|---|
| MySQL | 账号、API Key、路由、定价、请求记录 | 必须 |
| Redis | 响应缓存 | 可不持久化；K3s 示例开启 AOF 用于演示 PVC |
| Nacos | 动态配置、**应用密钥**、服务注册、Sentinel 规则源 | 必须（密钥就存在它的数据卷里） |
| Sentinel Dashboard | 查看流控状态的控制台 | 不持久化；规则写入 Nacos |
| gateway | Java 业务服务 | 业务本身无状态；示例只持久化滚动日志 |
| ui | Vue 构建后的静态文件 + nginx | 不需要 |

建议先完成 Compose，再完成 K3s。不要第一次就同时排查 GitLab、Runner、Registry、Kubernetes 和应用五层问题。

## 2. 服务器与安全前提

本文按 Ubuntu 22.04/24.04 编写。GitLab 和本项目放在同一台机器时，建议至少 4 核 8 GB；同时运行 GitLab、构建任务、MySQL、Nacos 和 Java 服务时，8 核 16 GB 会更从容。

建议的公网端口：

| 端口 | 用途 | 建议 |
|---|---|---|
| 22 | SSH | 只允许自己的 IP 更好 |
| 80/443 | GitLab | 生产建议域名 + HTTPS |
| 5050 | Container Registry | 绑定内网 IP,**不对公网开放**(见第 4 节;机器内部访问自己的公网 IP 走 NAT 常不通) |
| 8081 | Compose 管理台 | 需要 Compose 部署时开放 |
| 30081 | K3s 管理台 NodePort | 需要 K3s 部署时开放 |
| 6443 | Kubernetes API | 不要对全公网开放，只允许 Runner/运维 IP |

MySQL、Redis、Nacos、Sentinel 都不应直接暴露公网。本项目 Compose 已把 Nacos/Sentinel 管理端口绑定到 `127.0.0.1`；K3s 中它们使用 `ClusterIP`，只能在集群内访问。**密钥放进 Nacos 之后，这一条从「建议」升级为「红线」：8848/8850 一旦暴露公网，等于把所有密钥挂在公网上。**

远程查看控制台时使用 SSH 隧道：

```bash
# 在自己的电脑执行
ssh -L 8850:127.0.0.1:8850 -L 8858:127.0.0.1:8858 <服务器用户>@119.29.120.205
```

然后本机访问 `http://127.0.0.1:8850`（Nacos）和 `http://127.0.0.1:8858`（Sentinel）。

## 3. 在 GitLab 创建项目并推送代码

登录 GitLab：

1. 点击 **New project** → **Create blank project**。
2. 项目名填 `llm-gateway`，可见性选 **Private**。
3. 不要勾选初始化 README，因为本地仓库已经有提交历史。
4. 创建后复制页面显示的 HTTP 或 SSH Clone 地址。

保留现有 GitHub `origin`，新增一个名为 `gitlab` 的远程仓库：

```bash
cd llm-gateway-project
git remote add gitlab http://119.29.120.205/<你的命名空间>/llm-gateway.git
git push -u gitlab main
```

若 HTTP 推送不接受账号密码，在 GitLab 用户设置中创建 Personal Access Token（至少授予 `write_repository`），推送时把 Token 当作密码；更推荐配置 SSH Key。

以后一次推送两个平台：`git push origin main && git push gitlab main`。

## 4. 启用 Container Registry：整条链路的枢纽

「服务器不放源码、不做编译」成立的前提是镜像有地方存。GitLab Omnibus 自带 Registry，只是默认没开。

在服务器上编辑 `/etc/gitlab/gitlab.rb`：

```ruby
registry_external_url 'http://10.1.0.16:5050'
```

**地址刻意用内网 IP（`ip addr` 看 eth0，本文机器是 `10.1.0.16`），不用公网 IP。** 原因：云服务器的公网 IP 是 NAT 映射,机器从内部访问"自己的公网 IP"要绕出去再回来,腾讯云上这条回环常直接超时,还要求安全组放行；而 Registry 的所有使用者——CI 的 dind 推镜像、本机 docker 拉镜像、以后 K3s 的 containerd——全都在这台机器上,内网地址稳定可达且天然不暴露公网。这个地址会成为 `CI_REGISTRY` 和镜像名前缀,推过镜像后就不要再改了。

然后：

```bash
sudo gitlab-ctl reconfigure
```

完成后项目左侧出现 **Deploy → Container Registry**，CI 里也会自动多出 `CI_REGISTRY`、`CI_REGISTRY_IMAGE` 等变量——流水线中 `build_images`、`deploy_compose`、`deploy_k3s` 三个 Job 的规则都写了 `&& $CI_REGISTRY`，Registry 没启用时它们整体消失，这是有意的：没有镜像仓库，这条交付链路就不成立。

### 4.1 HTTP Registry 要在三个地方放行

学习阶段用 HTTP 足够，但 Docker/containerd 默认只信 HTTPS，凡是要「推」或「拉」这个 Registry 的运行时都要显式放行，一共三处：

| 谁在访问 | 配置位置 |
|---|---|
| 服务器本机 docker（部署时拉镜像） | `/etc/docker/daemon.json` |
| CI 里的 Docker-in-Docker（构建时推镜像） | `.gitlab-ci.yml` 中 dind 服务的 `--insecure-registry` 参数（已写好） |
| K3s 的 containerd（K3s 拉镜像，第二阶段才需要） | `/etc/rancher/k3s/registries.yaml` |

服务器本机（一个文件同时解决两件事：放行自建 HTTP Registry + 国内拉取 Docker Hub 的镜像加速——境内服务器直连 `registry-1.docker.io` 通常超时，必须配 mirror）：

```bash
sudo tee /etc/docker/daemon.json >/dev/null <<'EOF'
{
  "insecure-registries": ["10.1.0.16:5050"],
  "registry-mirrors": [
    "https://mirror.ccs.tencentyun.com",
    "https://docker.m.daocloud.io"
  ]
}
EOF
sudo systemctl restart docker
docker info | grep -A3 "Registry Mirrors"   # 确认生效
docker pull node:20-alpine                   # 试拉一个,几十秒内成功即通
```

两个键互不影响：`registry-mirrors` 只加速「从 Docker Hub 拉取」；`insecure-registries` 只放行你自己的 `10.1.0.16:5050`。腾讯云机器优先用 `mirror.ccs.tencentyun.com`（走内网）；镜像加速站时效性强，全部超时就搜索当期可用的地址替换。注意 dind 是独立守护进程、不读这个文件，所以 `.gitlab-ci.yml` 里 dind 的 `command` 同样带了 `--registry-mirror` 参数。

生产环境应给 Registry 配 HTTPS（与 GitLab 同一套证书体系），然后把 insecure 放行全部删掉。

### 4.2 一开始就设置清理策略（小硬盘的关键）

镜像按提交 SHA 打标签意味着每次发布都会在 Registry 里多一组镜像，不清理会稳定地吃满磁盘：

1. 项目 **Settings → Packages and registries → Container registry → Cleanup policy**：启用，保留最近 5 个标签，其余定期删除。
2. 清理策略删掉的是「标签引用」，底层存储靠垃圾回收(GC)释放。**GitLab 18/19 起 Registry 默认使用元数据数据库**（`database.enabled` 默认 `prefer`），此模式下 **GC 是在线自动的**：标签被删后，后台任务在复核延迟（约 24 小时）后自动回收存储，不停机、不需要执行任何命令。你要做的只是启用清理策略，然后一两天后用 `df -h` 验证空间回来了。
3. 旧命令 `sudo gitlab-ctl registry-garbage-collect -m` 只适用于旧的文件系统元数据模式（`database.enabled = false`）；在元数据库模式下执行会直接报错拒绝。不要为了用回这条命令去关掉元数据库——已推送过镜像后往回切不受支持，而且会失去无停机 GC。

确认自己处于哪种模式：

```bash
grep -A4 'database:' /var/opt/gitlab/registry/config.yml
sudo gitlab-ctl tail registry   # 在线 GC 的 worker 日志会出现在这里
```

## 5. 安装 GitLab Runner：一台机器,两种角色

GitLab 负责保存代码和安排任务，Runner 才是实际执行 `mvn test`、`docker build`、`docker compose up` 的进程。需要两个 Runner，各司其职——**两个都装在这台云服务器上**（同一个 `gitlab-runner` 守护进程，注册两次），区别在执行方式：

| Runner | Executor | 标签 | 干什么 |
|---|---|---|---|
| 构建 Runner | Docker（privileged） | 无，勾选「运行未打标签的作业」 | 每个 Job 起一个临时容器跑测试、前端构建、dind 构建镜像、K3s 部署 |
| 部署 Runner | Shell | `llm-deploy` | 以 `gitlab-runner` 用户直接在服务器本机执行 `deploy_compose` 的命令 |

为什么这样分：`.gitlab-ci.yml` 里 `deploy_compose` 写了 `tags: [llm-deploy]`，只会被部署 Runner 接走；其余 Job 都没打标签，只会被勾选了「运行未打标签的作业」的构建 Runner 接走。标签就是 Job 和 Runner 之间的路由规则。

### 5.1 服务器上安装 gitlab-runner（只做一次）

```bash
curl -L "https://packages.gitlab.com/install/repositories/runner/gitlab-runner/script.deb.sh" | sudo bash
sudo apt-get install -y gitlab-runner
```

安装会自动创建 `gitlab-runner` 系统用户——之后部署 Job 就是以这个用户身份在服务器上跑的。

### 5.2 创建构建 Runner

项目 **设置 → CI/CD → Runners → 新建项目 Runner**（New project runner），表单这样填：

| 表单项 | 填什么 | 为什么 |
|---|---|---|
| 标签 | **留空** | 让它接所有未打标签的 Job |
| 运行未打标签的作业 | **必须勾选** | 不勾的话 verify/image 阶段的 Job 永远没人接,流水线卡 pending |
| Runner 描述 | `llm-gateway-build` | 随意,便于辨认 |
| 已暂停 | 不勾 | |
| 受保护 | **不勾** | 勾了它就只跑受保护分支;功能分支的测试也需要它 |
| 锁定到当前项目 | 可勾可不勾 | 项目级 Runner 本来就只属于本项目 |
| 最大作业超时 | 留空 | 用项目默认(60 分钟);首次构建下载依赖较慢,不要设太小 |

点「创建 Runner」后页面会给出带 `glrt-...` token 的注册命令，到服务器上执行：

```bash
sudo gitlab-runner register --url http://119.29.120.205 --token <页面上的glrt-token>
# 交互提示只剩三问(标签等已在网页表单里定好):
#   GitLab instance URL → 回车确认
#   Enter a name        → 回车或随意
#   Enter an executor   → 输入 docker
#   Enter the default Docker image → alpine:3.21(Job 未指定 image 时的兜底,随意)
```

注册完打开 `/etc/gitlab-runner/config.toml`，在这个 runner 的 `[runners.docker]` 段把 `privileged = false` 改成 **`privileged = true`**（Docker-in-Docker 构建镜像必需），然后 `sudo gitlab-runner restart`。小内存机器顺便确认文件顶部 `concurrent = 1`（同时只跑一个 Job，避免构建挤爆内存；富余可改 2）。

### 5.3 创建部署 Runner

再点一次「新建项目 Runner」，表单这样填：

| 表单项 | 填什么 | 为什么 |
|---|---|---|
| 标签 | `llm-deploy` | 与 `.gitlab-ci.yml` 里 `deploy_compose` 的 `tags` 精确对应 |
| 运行未打标签的作业 | **不要勾** | 否则测试 Job 也会被它接走,直接跑在服务器裸机上 |
| Runner 描述 | `llm-gateway-deploy` | |
| 受保护 | **建议勾选** | Shell Runner 权限大;勾选后只服务受保护分支(main)的 Job |
| 锁定到当前项目 | 建议勾选 | 防止未来被共享给别的项目 |
| 最大作业超时 | 留空 | |

同样拿页面的新 token 到服务器注册，executor 这次选 **shell**：

```bash
sudo gitlab-runner register --url http://119.29.120.205 --token <第二个glrt-token>
#   Enter an executor → 输入 shell

sudo usermod -aG docker gitlab-runner       # 让部署 Job 能操作宿主机 docker
sudo systemctl restart gitlab-runner
sudo -u gitlab-runner docker version        # 验证:能打印 Server 版本即通过
```

### 5.4 验证

回到 **设置 → CI/CD → Runners**，应看到两个 Runner 均为绿色 online。随便推一次代码：verify 阶段的 Job 应显示由 `llm-gateway-build` 执行；`deploy_compose` 在手动触发时由 `llm-gateway-deploy` 执行（首次触发前先完成第 6 节的服务器初始化）。

Shell Runner 能执行服务器本机命令，权限很高：项目必须是 Private，`main` 分支设为 Protected，只允许可信维护者触发部署。

`deploy_compose` Job 设置了 `GIT_DEPTH: "1"`：Shell Runner 的工作目录里只会有一份浅克隆的源码（几 MB，且每次部署复用同一目录），Job 真正落到 `/opt/llm-gateway` 的只有 compose 文件和 nacos-init 脚本两个文件。这就是「不把整个项目文件夹丢到服务器」的具体实现——你以后看到任何「把仓库 clone 到服务器再 build」的教程，都可以用这个模式替换它。

最后在 **Settings → CI/CD → Variables** 添加一个非密钥变量：

| 变量 | 示例 | 说明 |
|---|---|---|
| `DEPLOY_HOST` | `119.29.120.205` | 流水线环境链接展示用 |

注意这里**没有任何密码类变量**。对比很多教程把一堆 `XXX_PASSWORD` 塞进 CI 变量：那意味着密钥多了一个存储地、一个泄露面（CI 日志、导出的流水线配置），且换密钥要去 GitLab 改。本项目密钥只存在两个地方，见第 8 节。

## 6. 服务器一次性初始化

只需要做一次。先装 Docker（脚本内容即仓库里的 `deploy/scripts/install-docker-ubuntu.sh`，服务器上不需要有仓库，直接复制执行）：

```bash
sudo apt-get update
sudo apt-get install -y ca-certificates curl
sudo install -m 0755 -d /etc/apt/keyrings
sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
sudo chmod a+r /etc/apt/keyrings/docker.asc
. /etc/os-release
sudo tee /etc/apt/sources.list.d/docker.sources >/dev/null <<EOF
Types: deb
URIs: https://download.docker.com/linux/ubuntu
Suites: ${VERSION_CODENAME}
Components: stable
Architectures: $(dpkg --print-architecture)
Signed-By: /etc/apt/keyrings/docker.asc
EOF
sudo apt-get update
sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
sudo systemctl enable --now docker
```

再创建部署目录和基础设施密钥文件：

```bash
sudo install -d -o gitlab-runner -g gitlab-runner -m 750 /opt/llm-gateway
sudo tee /opt/llm-gateway/.env >/dev/null <<'EOF'
MYSQL_ROOT_PASSWORD=<强随机密码>
REDIS_PASSWORD=<强随机密码>
UI_PORT=8081
EOF
sudo chown root:gitlab-runner /opt/llm-gateway/.env
sudo chmod 640 /opt/llm-gateway/.env
```

生成随机值：`openssl rand -base64 32`。

权限设计的意图：目录属主是 `gitlab-runner`，所以部署 Job 能往里同步 compose 文件；`.env` 属主是 root、组只读，所以 Job 能读取但改不了密码文件。

**注意**：`MYSQL_ROOT_PASSWORD` 只在 MySQL **首次初始化数据卷**时生效。数据卷建好之后再改 `.env` 不会改数据库里的密码，只会造成「网关用新密码连旧库」的 Access denied。真要换 MySQL 密码，先在库里 `ALTER USER`，再同步改 `.env`。

## 7. 第一次部署：跑流水线，然后在 Nacos 里填密钥

推送代码后流水线自动跑 verify（后端测试 + 前端构建）和 image（构建并推送镜像）。GitLab 界面上每一步在哪看：

| 想做/想看什么 | 在哪 |
|---|---|
| 流水线有没有在跑、过没过 | 左侧 **构建 → 流水线**；最上面一行是最新一条，点流水线号进详情，能看到 verify / image / deploy 三列 Job |
| 某个 Job 的实时日志 | 流水线详情页里点 Job 名字，日志实时滚动；失败先看最后几十行 |
| 手动触发 `deploy_compose` | 流水线详情页 deploy 列，`deploy_compose` 旁边的 **▶** 按钮 |
| 重跑一个失败的 Job | 进该 Job 页面，右上角 **重试**（Retry） |
| 部署完成后的访问入口 | 左侧 **部署 → 环境**，`compose-production` 会带着 URL 链接 |
| Runner 有没有接活 | Job 日志开头几行会写用了哪个 Runner（描述名） |

然后在流水线页面手动点击 `deploy_compose`，这个 Job 做的事完全可预测：

1. 校验 `/opt/llm-gateway/.env` 存在（不存在直接失败并提示）；
2. 把 `docker-compose.yml` 和 `deploy/nacos-init/init.sh` 同步到 `/opt/llm-gateway`；
3. `docker login` 后按本次提交 SHA 拉取 gateway/ui 镜像；
4. `docker compose up -d --wait` 等全部服务健康；
5. `docker image prune -af` 清掉不再被使用的旧镜像。

**第一次执行它必然失败，这是设计好的。** 失败信息会提示：gateway 在反复重启，因为 Nacos 里还没有 `GATEWAY_JWT_SECRET`。网关对密钥缺失的态度是 fail-closed——宁可不启动，也不带着空密钥上线。此时基础设施（MySQL/Redis/Nacos/Sentinel）已经全部正常运行，`nacos-init` 也已向 Nacos 发布了一份**密钥留空的配置模板**。

接下来把密钥填进去。Nacos 控制台在服务器上只监听 `127.0.0.1`（公网直接访问 `http://119.29.120.205:8850` 是打不开的，这是有意设计），所以要用 SSH 隧道把它「接」到你自己的电脑上：

1. **在你自己的电脑上**（Windows 用 PowerShell 或终端；不是在服务器上）执行，并保持这个窗口一直开着——隧道随 SSH 会话存活：

   ```bash
   ssh -L 8850:127.0.0.1:8850 -L 8858:127.0.0.1:8858 root@119.29.120.205
   ```

   `-L 本机端口:目标地址:目标端口` 的含义：让**你电脑**的 `127.0.0.1:8850` 开始监听，发往它的流量经 SSH 加密送到服务器，再由服务器连向「服务器自己的 `127.0.0.1:8850`」——正是 compose 里只绑回环的 Nacos 控制台。`8858` 顺带把 Sentinel 控制台也接出来，不需要可去掉。
2. 在**你电脑的浏览器**打开 `http://127.0.0.1:8850`（Nacos 鉴权已关闭，无需登录，安全性由「只绑回环 + SSH 隧道」保证）。进入 **配置管理 → 配置列表**，点开 `llm-gateway.yaml`（Group：`DEFAULT_GROUP`）→ **编辑**；
3. 填入真实值后发布：

```yaml
# 模板里已有运营参数(限流/配额/容错),保持不动;把下面这些空值换成真实值
GATEWAY_JWT_SECRET: "<至少32字符随机串,openssl rand -hex 32>"
ADMIN_USERNAME: "admin"
ADMIN_PASSWORD: "<强随机密码>"
DEEPSEEK_API_KEY: "sk-..."
OPENAI_API_KEY: ""
ANTHROPIC_API_KEY: ""
```

4. 回到 GitLab 重跑 `deploy_compose`（或 SSH 到服务器 `cd /opt/llm-gateway && docker compose restart gateway` 加速）。gateway 下一次启动时从 Nacos 读到密钥，通过健康检查，Job 变绿。

访问 `http://119.29.120.205:8081`，用刚才设置的管理员账号登录。

以后每次发布就是：push → 流水线绿 → 手动点 `deploy_compose`。密钥不用再管；改密钥/换供应商 Key 时在 Nacos 控制台编辑发布，然后 `docker compose restart gateway`。

回滚：打开旧提交的流水线，重跑那次的 `deploy_compose`——镜像按 SHA 存在 Registry 里，随时可以拉回来。

日常查看（SSH 到服务器）：

```bash
cd /opt/llm-gateway
docker compose ps
docker compose logs --tail=200 -f gateway
```

## 8. 密钥模型：什么密钥放哪里，为什么

给任何项目设计密钥存放时，先问一个问题：**这个密钥「谁」在「什么时候」需要？**

| 层级 | 谁需要、何时需要 | 放哪里 | 本项目的实例 |
|---|---|---|---|
| 基础设施级 | 容器**启动瞬间**就要（进程参数/初始化） | 服务器本地 `.env`（或 K8s Secret） | `MYSQL_ROOT_PASSWORD`、`REDIS_PASSWORD` |
| 应用级 | 应用**启动之后**读配置才用 | 配置中心（Nacos） | `GATEWAY_JWT_SECRET`、`ADMIN_*`、供应商 API Key |
| 平台级 | **CI 部署时**才用 | GitLab CI 变量 | `KUBE_CONFIG`（仅 K3s 路线） |

MySQL 容器初始化数据卷时就要拿到 root 密码，那时 Nacos 可能都还没起来，所以它天然进不了配置中心——这就是 `.env` 无法彻底消灭、但可以缩到只剩两行的原因。而 JWT 密钥、供应商 Key 是网关启动后才用的，网关又本来就会在启动时从 Nacos 拉配置（`spring.config.import: nacos:llm-gateway.yaml`），把它们放 Nacos 是顺水推舟：**一处存储、控制台可改、改完重启即生效，并且随 Nacos 数据卷持久化**。

实现机制值得记住，可以照搬到其他 Spring 项目：`application.yaml` 里所有敏感项都写成 `${环境变量名:默认值}` 占位符；Nacos 配置里用**与环境变量同名的顶层键**发布真实值。Spring 解析占位符时按「环境变量 > Nacos 配置 > 本地 application.yaml」的顺序取值，于是：

- 生产：环境变量不设，值来自 Nacos；
- CI 测试：Job 里注入环境变量（如 `GATEWAY_JWT_SECRET: ci-test-...`），压过 Nacos，测试不依赖外部服务；
- 本地裸跑：IDE/shell 里设环境变量即可。

有一个坑要避开：**空字符串环境变量也算「已设置」**，会把 Nacos 的值遮蔽掉。所以 `docker-compose.yml` 里刻意删掉了 `GATEWAY_JWT_SECRET: ${GATEWAY_JWT_SECRET:-}` 这类透传——不传，而不是传空。

另一个设计点：`nacos-init` 只在配置**不存在**时发布模板（create-if-absent）。Nacos 是密钥与运营参数的唯一事实来源，重复部署、重建容器都不会覆盖你在控制台改过的值。代价是模板以后新增了键，已有环境要在控制台手动补。

安全边界要诚实地说清楚：本项目的 Nacos 未开启鉴权，安全性完全依赖网络隔离（8848/8850 只绑 127.0.0.1 / ClusterIP，公网不可达，管理靠 SSH 隧道）。单机自用够了；多人共用一台机器、或者以后有横向扩展，应开启 Nacos 鉴权（`NACOS_AUTH_ENABLED=true`，客户端配用户名密码），那会引入一个新的「基础设施级」密钥，按上表放 `.env`。

## 9. 数据卷到底存在哪里

Compose 文件中的：

```yaml
volumes:
  - mysql-data:/var/lib/mysql
```

左边是 Docker 管理的 named volume，右边是容器内数据目录。删除、重建容器不会删除 named volume；执行 `docker compose down -v` 才会删除它，所以生产环境不要随手加 `-v`。**Nacos 的数据卷里存着你的全部应用密钥，删掉它等于把密钥和动态配置一起清空。**

查看实际卷名和宿主机位置：

```bash
docker volume ls
docker volume inspect llm-gateway_mysql-data
docker volume inspect llm-gateway_nacos-data
docker volume inspect llm-gateway_gateway-logs
```

MySQL 应做逻辑备份，而不是直接复制正在写入的数据目录：

```bash
mkdir -p "$HOME/backups"
cd /opt/llm-gateway
docker compose exec -T mysql sh -c 'mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" --single-transaction --routines --triggers llm_gateway' \
  > "$HOME/backups/llm_gateway_$(date +%F_%H%M%S).sql"
```

Redis 在 Compose 中被当作可重建缓存，故意没有数据卷且关闭持久化；丢失缓存不会丢业务数据。K3s 示例开启 AOF 和 PVC，是为了展示有状态 Redis 的写法。你要根据业务语义决定是否持久化，而不是看到中间件就机械挂卷。

## 10. 小硬盘怎么管

一台小盘服务器上，会持续增长的东西就这几类，逐一都有对策：

| 占盘项 | 增长原因 | 对策 |
|---|---|---|
| Registry 镜像 | 每次发布新增一组 SHA 标签 | 清理策略保留最近 5 个；在线 GC 约 24h 后自动回收（第 4.2 节） |
| 服务器上的 Docker 镜像 | 每次部署拉新镜像 | 部署 Job 末尾 `docker image prune -af`（自动） |
| 构建缓存 | Docker-in-Docker 逐 Job 丢弃，不落host | 无需处理；代价是每次构建重新下载依赖 |
| GitLab CI 缓存/artifacts | `.m2`、`.npm` 缓存,前端 dist | artifacts 已设 `expire_in: 7 days`；缓存可在 UI 里手动清 |
| Runner 工作目录 | 浅克隆源码 | `GIT_DEPTH` 已设为 20/1，单份复用，几 MB |
| 数据卷 | MySQL 数据、Nacos 数据、网关 14 天滚动日志 | 正常业务数据；定期备份后按需清理请求明细表 |
| GitLab 本体 | 自动备份、日志 | `gitlab.rb` 里控制备份保留数（`backup_keep_time`） |

日常巡检两条命令：

```bash
df -h
docker system df
```

`docker image prune -af` 会删除所有「当前没有容器在用」的镜像，包括上一个版本的 gateway/ui——这是故意的：回滚不依赖本机旧镜像，而是重跑旧流水线从 Registry 拉。小盘机器上「本机只留正在运行的版本，历史版本都在 Registry」是值得记住的分工。

## 11. 第二阶段：安装 K3s

K3s 是符合 Kubernetes API 的轻量发行版，适合单机云服务器。它默认带 containerd（容器运行时）、CoreDNS、local-path-provisioner（自动创建本机 PVC）、Traefik（Ingress）。

安装（脚本内容即仓库里的 `deploy/scripts/install-k3s-ubuntu.sh`，也可直接在 GitLab 网页上打开该文件复制执行）：

```bash
curl -sfL https://get.k3s.io | sh -
sudo k3s kubectl get nodes -o wide
```

为了少写 `sudo k3s kubectl`，给运维用户复制 kubeconfig：

```bash
mkdir -p "$HOME/.kube"
sudo cp /etc/rancher/k3s/k3s.yaml "$HOME/.kube/config"
sudo chown "$USER:$USER" "$HOME/.kube/config"
chmod 600 "$HOME/.kube/config"
kubectl get nodes
```

### 11.1 让 containerd 认识 HTTP Registry

K3s 拉镜像走的是 containerd，不读 Docker 的 `daemon.json`，要单独配：

```bash
sudo tee /etc/rancher/k3s/registries.yaml >/dev/null <<'EOF'
mirrors:
  "10.1.0.16:5050":
    endpoint:
      - "http://10.1.0.16:5050"
EOF
sudo systemctl restart k3s
```

这样 K3s 直接从 Registry 拉镜像，不需要在服务器上 `docker build`、也不需要 `docker save | ctr import` 的手工导入——那种做法会让同一个镜像在 Docker 和 containerd 里各存一份，小盘机器承受不起。

### 11.2 一次性创建命名空间与两个 Secret

CI 不管理密钥，所以部署前手工创建一次（值与 `/opt/llm-gateway/.env` 保持一致；K3s 环境也可以用不同的密码，两套环境彼此独立）：

```bash
kubectl apply -f - <<'EOF'
apiVersion: v1
kind: Namespace
metadata:
  name: llm-gateway
EOF

kubectl -n llm-gateway create secret generic llm-gateway-secrets \
  --from-literal=MYSQL_ROOT_PASSWORD='<mysql密码>' \
  --from-literal=REDIS_PASSWORD='<redis密码>'
```

拉取镜像的凭据用 **Deploy Token** 而不是 CI 的 Job Token：Job Token 在 Job 结束后失效，如果拿它做 imagePullSecret，Pod 日后重启、漂移时会拉不下来镜像。项目 **Settings → Repository → Deploy tokens**，创建一个只有 `read_registry` 权限的 token，然后：

```bash
kubectl -n llm-gateway create secret docker-registry gitlab-registry \
  --docker-server=10.1.0.16:5050 \
  --docker-username=<deploy-token 用户名> \
  --docker-password=<deploy-token>
```

字段说明可参考 `deploy/k8s/base/secret.example.yaml`（该文件不会被 kustomize 应用，只是文档）。

### 11.3 配置 CI 并部署

在 GitLab CI/CD Variables 添加唯一的平台级密钥：

| 变量 | 类型 | 说明 |
|---|---|---|
| `KUBE_CONFIG` | File（勾选 Protected） | 可访问 K3s API 的 kubeconfig |

生成方法——把 API 地址从 `127.0.0.1` 改成 Runner 能连的地址：

```bash
sudo sed 's/127.0.0.1/<服务器内网IP>/' /etc/rancher/k3s/k3s.yaml
```

把输出完整粘贴进 File 变量。安全组的 6443 只允许 Runner 来源 IP；若证书不包含所用地址，重装 K3s 时加 `--tls-san <地址>`，不要关闭 TLS 校验。

顺带说明：`deploy_k3s` 在流水线里始终可见，但未配置 `KUBE_CONFIG` 时点了会立刻失败并提示「K3s 路线尚未启用」——第一阶段误点它没有任何影响，Compose 部署请点 `deploy_compose`。

然后在流水线里手动点 `deploy_k3s`，它做四件事：`kubectl apply -k deploy/k8s/base`（清单来自 CI Job 自己的浅克隆，不占服务器部署目录）、把两个 Deployment 的镜像 `set image` 到本次 SHA、等待 rollout 完成。

**首次部署同样会在 gateway 上卡住**——原因和 Compose 一样：K3s 里的 Nacos 是另一个独立实例，`nacos-init` Job 发布的模板密钥也是空的。填法：

```bash
kubectl -n llm-gateway port-forward service/nacos 8850:8080
# 配合 SSH 隧道打开 http://127.0.0.1:8850,编辑 llm-gateway.yaml 填密钥
kubectl -n llm-gateway rollout restart deployment/gateway
```

之后的发布就是常规滚动更新；回滚：

```bash
kubectl -n llm-gateway rollout history deployment/gateway
kubectl -n llm-gateway rollout undo deployment/gateway
```

数据库迁移由 Flyway 在 gateway 启动时执行，数据库结构变更必须保持向后兼容，避免新旧 Pod 短暂并存时互相冲突。

### 11.4 Kubernetes 清单为什么这样拆

- `Deployment`：适合 gateway、ui、Sentinel 这类可替换实例。
- `StatefulSet`：适合 MySQL、Redis、Nacos；Pod 名称和数据卷绑定关系稳定。
- `Service`：提供稳定 DNS。应用写 `MYSQL_HOST=mysql`，不关心 MySQL Pod IP。
- `ConfigMap`：非敏感环境变量。
- `Secret`：只放两个基础设施密码。**应用密钥不在这里**——在 Nacos。Secret 只是 Base64 存储，不等于加密；仍要限制 RBAC 和备份权限。
- `PVC`：声明需要多少持久化空间。K3s 的 `local-path` 会在节点磁盘创建实际目录（`/var/lib/rancher/k3s/storage`，仍是本机，不是异地备份）。
- readinessProbe：未准备好时不接流量。livenessProbe：进程卡死时由 Kubernetes 重启。
- resources：避免单个容器无限挤占内存。

## 12. `.gitlab-ci.yml` 是怎么工作的

| Job | 阶段 | 目的 |
|---|---|---|
| `backend_test` | verify | JDK 21 + 临时 MySQL 8.4 跑测试并上传 JUnit 报告；密钥用 Job 内环境变量注入 |
| `frontend_build` | verify | `npm ci` 后执行 Vite 生产构建 |
| `frontend_format` | verify | 报告历史文件格式问题；当前允许失败,不阻断部署 |
| `build_images` | image | dind 构建两个镜像并按 SHA 推到 Registry |
| `deploy_compose` | deploy(手动) | Shell Runner 同步 2 个文件到 `/opt/llm-gateway`,拉镜像启动,清理旧镜像 |
| `deploy_k3s` | deploy(手动) | apply 清单 + `set image` 到本次 SHA + 等待 rollout |

几个关键变量由 GitLab 自动提供：

- `CI_REGISTRY` / `CI_REGISTRY_IMAGE`：Registry 地址与当前项目的镜像前缀，启用 Registry 后才存在（所以被用作三个 Job 的开关）。
- `CI_COMMIT_SHA`：本次提交的唯一版本号。用它做镜像标签的意义在于：**线上任何时刻你都说得出跑的是哪次提交**，回滚有明确目标。不要只推 `latest`。
- `CI_REGISTRY_USER` / `CI_REGISTRY_PASSWORD`：当前 Job 的临时 Registry 凭据，Job 结束即失效——适合 CI 里推拉镜像，不适合做集群的 imagePullSecret（见 11.2）。
- `GIT_DEPTH`：浅克隆深度。全局 20，部署 Job 收紧到 1。

两个部署 Job 都用了 `resource_group`：同一环境的部署强制串行，避免两个人同时点出竞态。`needs: build_images` 则保证部署永远发生在镜像推送成功之后。

### 12.1 国内网络：四个下载点各自的加速

境内服务器跑这条流水线，会从四个地方下载东西，逐一都要换成国内源，漏一个就会在对应环节超时或龟速：

| 下载点 | 谁在用 | 加速方式 |
|---|---|---|
| Docker Hub（宿主机） | Runner 拉 Job/Service 镜像、部署时拉运行镜像 | `daemon.json` 的 `registry-mirrors`（第 4.1 节） |
| Docker Hub（dind 内） | `build_images` 构建镜像时的 `FROM` | dind 服务的 `--registry-mirror` 参数（`.gitlab-ci.yml`） |
| Maven Central | `backend_test` 与后端 Dockerfile 的 `mvn` | settings.xml 写 `mirrorOf=central` → 阿里云 `maven.aliyun.com/repository/public` |
| npm registry | 前端两个 Job 与前端 Dockerfile 的 `npm ci` | `npm config set registry https://registry.npmmirror.com` |

另外两点：verify Job 的 `.m2`/`.npm` 有 GitLab 缓存，首次跑绿之后后续只增量下载；镜像加速站时效性强，报 `i/o timeout` 先怀疑加速站失效，换当期可用的。

## 13. 以后如何用同样的方法部署其他项目

假设以后新增 `order-service`。不要从复制命令开始，先回答六个问题：

1. 它如何构建？Java Jar、Node、Go 还是静态文件？→ 决定 Dockerfile 写法
2. 它监听哪个端口，有没有 `/actuator/health` 或 `/health`？→ 决定健康检查
3. 哪些配置公开、哪些是密钥？密钥属于哪一级（基础设施/应用/平台，见第 8 节的表）？→ 决定放 `.env`、配置中心还是 CI 变量
4. 服务本身是否无状态？真正的数据存在哪里？→ 决定要不要数据卷/PVC、怎么备份
5. 谁调用它，它调用谁？→ 决定网络暴露面（反代？ClusterIP？公网端口？）
6. 发布失败时如何回滚？→ 决定镜像标签策略（用提交 SHA）

### 13.1 Dockerfile 模板

Java 服务可按当前后端的多阶段方式：

```dockerfile
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml ./
RUN mvn -B -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd -r -u 10001 app
COPY --from=build /app/target/*.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

原则：构建工具不进入最终镜像、不把 `.env`/密钥 COPY 进去、使用非 root 用户、固定运行入口、提供健康检查。**构建永远发生在 CI（或你自己的电脑），产物是镜像；服务器只认镜像，不认源码。**

### 13.2 加入 Compose

```yaml
order-service:
  image: ${ORDER_IMAGE:-order-service:local}
  environment:
    SPRING_PROFILES_ACTIVE: prod
    MYSQL_HOST: mysql
    NACOS_SERVER_ADDR: nacos:8848
  depends_on:
    mysql:
      condition: service_healthy
    nacos:
      condition: service_healthy
  restart: unless-stopped
```

Compose 默认把同一文件中的服务放进同一网络，服务名就是 DNS 名：连接地址写 `mysql:3306`，不能写 `localhost:3306`（容器里的 localhost 指容器自己）。密钥照第 8 节分级：数据库密码进 `.env`，业务密钥进 Nacos（新服务同样用 `spring.config.import` + `${环境变量名:}` 占位符的写法，然后在它的 Nacos dataId 里发布同名键）。

### 13.3 加入 Kubernetes

无状态服务通常需要 Service + Deployment 两个对象：

```yaml
apiVersion: v1
kind: Service
metadata:
  name: order-service
  namespace: llm-gateway
spec:
  selector:
    app: order-service
  ports:
    - port: 8080
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: order-service
  namespace: llm-gateway
spec:
  replicas: 1
  selector:
    matchLabels:
      app: order-service
  template:
    metadata:
      labels:
        app: order-service
    spec:
      imagePullSecrets:
        - name: gitlab-registry
      containers:
        - name: order-service
          image: 10.1.0.16:5050/<命名空间>/llm-gateway/order-service:commit-sha
          ports:
            - containerPort: 8080
          readinessProbe:
            httpGet:
              path: /actuator/health
              port: 8080
```

然后把文件加入 `deploy/k8s/base/kustomization.yaml`。

### 13.4 注册到 Nacos

Spring Cloud Alibaba 服务需要：

1. 引入 `spring-cloud-starter-alibaba-nacos-discovery`（要动态配置再加 `nacos-config`）。
2. 设置唯一的 `spring.application.name`。
3. 设置 `spring.cloud.nacos.server-addr=${NACOS_SERVER_ADDR:nacos:8848}`。
4. Compose/Kubernetes 注入 `NACOS_SERVER_ADDR=nacos:8848`。
5. 启动后在 Nacos 服务列表确认实例和健康状态。

注意：Kubernetes 自己已有 Service DNS，集群内服务发现不一定需要 Nacos。本项目保留 Nacos，是因为还用它做动态配置、密钥存放和 Sentinel 规则源。以后要明确「为什么使用」，不要为了注册而注册两套发现机制。

如果前端需要访问新服务，通常让 nginx 增加反向代理（改完必须重建 UI 镜像）：

```nginx
location /orders/ {
    proxy_pass http://order-service:8080;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
}
```

### 13.5 流水线增加新镜像

思路固定为四步：测试、构建、推送、更新部署。镜像命名 `$CI_REGISTRY_IMAGE/order-service:$CI_COMMIT_SHA`；Compose 路线给 `deploy_compose` 的 pull/up 列表加上新服务，K3s 路线加：

```bash
kubectl -n llm-gateway set image deployment/order-service \
  order-service="$CI_REGISTRY_IMAGE/order-service:$CI_COMMIT_SHA"
kubectl -n llm-gateway rollout status deployment/order-service --timeout=180s
```

不要把部署命令混进单元测试 Job，也不要在仓库或 CI 变量里保存服务器密码。

## 14. 常用排障命令

Compose（先 `cd /opt/llm-gateway`）：

```bash
docker compose ps
docker compose logs --tail=200 gateway
docker compose logs mysql nacos redis
docker inspect <容器名>
docker system df
```

Kubernetes：

```bash
kubectl -n llm-gateway get all
kubectl -n llm-gateway describe pod <Pod名>
kubectl -n llm-gateway logs <Pod名> --all-containers --tail=200
kubectl -n llm-gateway logs <Pod名> --previous
kubectl -n llm-gateway get events --sort-by=.lastTimestamp
```

判断问题所在层次：

- 测试 Job 失败：代码或依赖问题，还没构建镜像。
- 容器"起来又消失"、`docker ps -a` 里成片 `Exited (137)`、`dmesg` 有 `Out of memory`：小内存机器被 OOM 杀了。GitLab 本身就吃 4GB 上下,再叠全套中间件很容易触顶。标准保命操作是加 4G swap（`fallocate -l 4G /swapfile && chmod 600 /swapfile && mkswap /swapfile && swapon /swapfile`,再写入 `/etc/fstab`）,代价是紧张时变慢。部署 Job 可反复重试,`docker compose up -d` 会把缺的容器补齐,数据卷不受影响。
- `docker login`/push/pull Registry 报 `Client.Timeout exceeded`（超时而非拒绝）：多半是把 Registry 地址写成了公网 IP——机器内部访问自己的公网 IP 走 NAT 回环，云上常不通。Registry 统一用内网 IP（第 4 节）；改完 `gitlab.rb` 要 `gitlab-ctl reconfigure`，三处 insecure 放行（4.1 节）与 CI 里 dind 参数同步改。
- 测试 Job 卡在启动早期（如 `HV000001` 之后长时间无输出）：环境里没有 Nacos，而 nacos-client 会对 `localhost:8848` 反复重连退避，每个测试上下文都卡数分钟。测试环境显式设 `NACOS_DISCOVERY_ENABLED=false`、`SPRING_CLOUD_NACOS_CONFIG_ENABLED=false`、`SPRING_CLOUD_SENTINEL_ENABLED=false` 三个开关（`.gitlab-ci.yml` 已带）。通用教训：**单元/集成测试不应隐式依赖外部中间件，凡有"无此依赖"开关的组件，测试环境都要显式关掉**。
- 测试 Job 大量 `Failed to load ApplicationContext`：先滚到 Job 日志**最顶部**找黄色 `WARNING: Service ... probably didn't start properly`——多半是 service 容器没起来。经典坑：**Job 级 `variables:` 会整体注入 service 容器**，比如 `MYSQL_USER=root` 会让官方 mysql 镜像的启动脚本直接报错退出（它禁止 root 作为 MYSQL_USER）。解法：数据库初始化变量写在 service 自己的 `variables:` 里（优先级更高），并把泄漏的键显式置空。
- 拉镜像报 `dial tcp ...:443: i/o timeout`（目标是 `registry-1.docker.io`）：境内直连 Docker Hub 不通。宿主机 `daemon.json` 的 `registry-mirrors` 和 dind 的 `--registry-mirror` 是否都配了（第 4.1 节）？都配了还超时，说明该加速站失效，换当期可用的。
- `http://127.0.0.1:8850` 打不开：① 隧道窗口是否还开着（`ssh -L` 那个会话关了隧道就断）；② 首次 `deploy_compose` 是否已经跑过——没跑过服务器上还没有 Nacos 容器，先在服务器执行 `cd /opt/llm-gateway && docker compose ps` 确认 nacos 是 Up，`ss -tlnp | grep 8850` 确认在监听；③ 隧道命令要在自己电脑上执行，不是在服务器上。浏览器始终用自己电脑的，服务器上不需要也不该装浏览器。
- 镜像 Job 失败：dind/Registry 问题。日志出现 `http: server gave HTTP response to HTTPS client` → 三处 insecure-registry 放行少配了（第 4.1 节）。
- gateway 反复重启且日志有 `GATEWAY_JWT_SECRET 未配置或长度不足` → Nacos 里没填密钥，或填错了 dataId/Group（应为 `llm-gateway.yaml` / `DEFAULT_GROUP`）。
- gateway 日志 `Access denied for user 'root'` → `.env`（或 K8s Secret）的 MySQL 密码与已初始化的数据卷不一致（见第 6 节注意事项）。
- `ImagePullBackOff`（K3s）→ `registries.yaml` 没配、`gitlab-registry` Secret 没建或 Deploy Token 过期；`kubectl describe pod` 看具体报错。
- `CrashLoopBackOff`：应用启动失败，先看日志再看配置来源（Nacos/Secret/ConfigMap）。
- readiness 失败：进程活着但依赖没好或健康地址错误。
- 页面能开但 API 502：检查 gateway Pod/容器和 nginx 上游服务名。
- 改了 `nacos-init` 模板但环境里没变化：init 是 create-if-absent，已有配置不会被覆盖——在控制台手动补键；K3s 里还需 `kubectl -n llm-gateway delete job nacos-init` 后重新 apply 才会重跑。

## 15. 上线检查清单

- GitLab 项目 Private，`main` 分支 Protected。
- 服务器安全组没有开放 3306、6379、8848、8850、8858；6443 只对 Runner/运维 IP 开放。
- 密钥各归其位：仓库和 CI 变量里没有任何业务密码；`.env` 只有两个基础设施密码且权限 640；应用密钥只在 Nacos。
- Registry 清理策略已启用（元数据库模式下空间由在线 GC 自动回收，删标签约一天后生效）。
- MySQL 定时备份到另一处，并实际演练过恢复；记住 Nacos 数据卷里有密钥，备份策略要覆盖。
- 每次发布使用提交 SHA 镜像标签，验证过「重跑旧流水线」能回滚。
- `df -h` 与 `docker system df` 纳入日常巡检；GitLab artifacts、Registry、数据卷是三大增长点。
