# 双服务器 GitLab CI/CD + Docker Compose 部署操作手册

- 版本：2026-08-08（修订：京东云 GitLab 改为 IP + 私有 CA 访问）
- 适用系统：Ubuntu Server 22.04 LTS 64 位
- 适用仓库：`llm-gateway-project`、`软项智训`
- 目标：推送 `main` 后自动测试、构建镜像、推送 Registry、替换腾讯云容器；失败时不影响现有版本或自动回滚

本文按首次重装顺序编写。除明确标注“本地电脑”外，命令均在对应云服务器执行。所有 `<...>` 都必须替换，不要把尖括号原样粘贴。

## 本次修订：京东云为什么不再使用域名

京东云实例尚未完成 ICP 备案，云厂商的“未备案拦截”会在 80 端口按 `Host` 头拦截 `gitlab.ztmdcg.cn` 的请求并直接返回 `403`。Let's Encrypt 的 HTTP-01 校验必须访问 `http://gitlab.ztmdcg.cn/.well-known/acme-challenge/...`，请求在到达 GitLab 之前就被拦截页面截走，因此签发一定失败：

```text
Validation failed, unable to request certificate...
Invalid response from http://gitlab.ztmdcg.cn/.well-known/acme-challenge/... 403
```

这不是 GitLab、DNS 或 certbot 的配置问题，**在备案通过之前重试任何次数都不会成功**。因此本文对京东云做如下调整：

| 项目 | 原方案（已废弃） | 现方案 |
|---|---|---|
| GitLab 地址 | `gitlab.ztmdcg.cn` 域名 | `https://117.72.220.94` |
| Registry 地址 | `registry.ztmdcg.cn` 域名 | `https://117.72.220.94:5050` |
| 证书来源 | Let's Encrypt HTTP-01 | 自建私有 CA，叶证书写入 `subjectAltName = IP:117.72.220.94` |
| DNS 记录 | `gitlab`、`registry` 两条 A 记录 | 不再需要，**必须删除** |
| 访问方式 | 浏览器访问域名 | 内部开发人员直接访问 IP |

腾讯云 `ztmdcg.cn` 与 `gateway.ztmdcg.cn` 已完成备案，**对外服务与 Let's Encrypt 流程完全不变**，本次修订不影响第 7 章及之后的对外入口。

仍然使用 HTTPS 而不是明文 HTTP，是因为 GitLab 登录密码、Git 推送内容、Registry 凭据都要穿越公网；私有 CA 只解决“谁来签发”，不降低传输加密强度。代价是 CA 证书必须手工分发到每一个客户端，见第 3 章。

备案通过后若想切回域名：在 DNSPod 补回 `gitlab`、`registry` 两条 A 记录，把 `external_url` / `registry_external_url` 改为域名，把 `letsencrypt` 开关翻回启用，`reconfigure` 拿到公信证书后，再按第 3.8 节反向清理各客户端的 CA 信任。切换前先确认 `curl -I http://<域名>/` 不再返回拦截页面的 `403`，否则会重现同一个失败。

## 阅读与执行规则

重装会清空系统盘；开始前先确认旧服务器没有仍需保留的数据，或已经完成快照/异机备份。本文不提供任何可直接使用的密码、Token 或 API Key，所有占位符都要替换为现场生成的值。

下表适用于对应阶段里的**每一条服务器变更命令**：一条命令返回非零、检查结果不符合“成功标志”，或输出中出现 `failed`/`error` 时，立即停止当前阶段，执行“失败动作”，不要继续粘贴后续命令，也不要靠反复重跑掩盖原因。

| 阶段 | 成功标志 | 失败动作 |
|---|---|---|
| 重装、时区、swap、基础包 | 系统版本/时间/swap 与本文一致 | 保留完整错误输出；先修复云控制台、APT、磁盘或 DNS，再继续 |
| Docker、GitLab、Runner | 对应 `version`、`status`、`verify` 命令退出码为 0 | 查看 `systemctl status`、`journalctl` 或 `gitlab-ctl tail`，服务未健康前不进入下一章 |
| 私有 CA 与证书分发 | `openssl verify` 与 `curl --cacert` 成功，`docker login` 不报 `x509` | 重新核对 SAN 中的 IP、CA 文件路径与属主；不要改用 `-k`、`--insecure` 或明文 HTTP 绕过 |
| 目录、sudoers、密钥 | `stat`/`test`/`visudo -cf` 检查通过 | 恢复正确属主和权限；不要临时放宽到 `777` 或 `NOPASSWD: ALL` |
| Nginx、证书 | `nginx -t` 与 `certbot renew --dry-run` 成功 | 保留旧配置，不 reload；检查 DNS、80/443、安全组和证书路径 |
| CI/CD 发布 | Pipeline 全绿，Compose 容器为 `healthy` | 查看失败 Job 和对应 Compose 日志；不要手工改 `latest` 或绕过测试 |
| 备份、恢复 | 生成相对路径 `SHA256SUMS` 且校验通过；恢复后两套应用健康 | 保留 `.partial` 或失败日志；恢复失败时保持应用停止，查明 MySQL 状态后再决定重试或回退 |

## 0. 最终拓扑与交付文件

### 0.1 两台服务器职责

| 服务器 | 配置 | 职责 |
|---|---|---|
| 京东云 `117.72.220.94` | 4C / 8GB / 180GB SSD | GitLab CE、GitLab Container Registry、Docker 构建 Runner |
| 腾讯云 `119.29.120.205` | 4C / 8GB / 60GB SSD | 宿主机 Nginx/Certbot、部署 Runner、共享组件、两个业务项目 |

```text
本地电脑
  ├─ push llm-gateway/main      （SSH：git@117.72.220.94）
  └─ push soft-training/main    （SSH：git@117.72.220.94）
           │
           ▼
京东云 117.72.220.94（未备案，只用 IP）
  ├─ GitLab CE      https://117.72.220.94
  ├─ Registry       https://117.72.220.94:5050
  ├─ 私有 CA        /etc/gitlab/ssl/ztmdcg-ca.crt
  └─ Docker Runner（并发 1）
           │ HTTPS 拉取 SHA 镜像（腾讯云用私有 CA 校验）
           ▼
腾讯云 119.29.120.205（已备案，对外用域名）
  ├─ Nginx :80/:443 → ztmdcg.cn、gateway.ztmdcg.cn
  ├─ MySQL + 两套 Redis + MinIO + Qdrant + Nacos + Sentinel
  ├─ Gateway backend/UI
  └─ 软项智训 backend/frontend
```

### 0.2 公网与本机端口

腾讯云是对外入口，`80` 用于 Let's Encrypt 校验并跳转 `443`，对外 API 使用 HTTPS。京东云是内部开发设施，只对开发人员和腾讯云开放，**不开放 80 端口**：备案通过前 80 端口会被拦截页面接管，而 IP + 私有 CA 方案不需要它。

| 机器 | 监听 | 用途 | 安全组 |
|---|---:|---|---|
| 京东云 | `22` | SSH / Git over SSH（开发人员推送） | 管理人员与开发人员公网 IP |
| 京东云 | `443` | GitLab Web/API（`https://117.72.220.94`） | 开发人员公网 IP + `119.29.120.205` |
| 京东云 | `5050` | Container Registry（`https://117.72.220.94:5050`） | `119.29.120.205` + 管理人员公网 IP |
| 腾讯云 | `22` | SSH | 仅管理人员公网 IP |
| 腾讯云 | `80`、`443` | `ztmdcg.cn`、`gateway.ztmdcg.cn` | 公网 |
| 腾讯云 | `127.0.0.1:18080` | 软项智训前端 | 不开放 |
| 腾讯云 | `127.0.0.1:18090` | 软项智训后端 | 不开放 |
| 腾讯云 | `127.0.0.1:18081` | Gateway UI | 不开放 |
| 腾讯云 | `127.0.0.1:18091` | Gateway API/Admin | 不开放 |
| 腾讯云 | `127.0.0.1:9001` | MinIO 控制台 | SSH 隧道 |
| 腾讯云 | `127.0.0.1:6333` | Qdrant REST/控制台 | SSH 隧道 |
| 腾讯云 | `127.0.0.1:8848`、`8850` | Nacos API/控制台 | SSH 隧道 |
| 腾讯云 | `127.0.0.1:8858` | Sentinel 控制台 | SSH 隧道 |

### 0.3 已生成的仓库文件

`llm-gateway-project`：

- `.gitlab-ci.yml`
- `deploy/platform/docker-compose.yml`
- `deploy/platform/.env.example`
- `deploy/platform/mysql/init/10-create-app-databases.sh`
- `deploy/platform/nacos-init/init.sh`
- `deploy/production/docker-compose.yml`
- `deploy/production/.env.example`
- `deploy/nginx/00-acme-bootstrap.conf`
- `deploy/nginx/proxy-common.conf`
- `deploy/nginx/gateway.ztmdcg.cn.conf`
- `deploy/scripts/deploy-production.sh`
- `deploy/scripts/backup-runtime.sh`
- `deploy/scripts/restore-mysql.sh`

`软项智训`：

- `.gitlab-ci.yml`
- `deploy/production/docker-compose.yml`
- `deploy/production/.env.example`
- `deploy/nginx/ztmdcg.cn.conf`
- `deploy/scripts/deploy-production.sh`

本地开发用的两个原有 `docker-compose.yml` 保持不变；生产只使用 `deploy/` 下的文件。

## 1. DNSPod 与云安全组

### 1.1 DNSPod 记录

在腾讯云 DNSPod 为 `ztmdcg.cn` 只保留以下三条记录，全部指向已备案的腾讯云：

| 主机记录 | 类型 | 记录值 |
|---|---|---|
| `@` | A | `119.29.120.205` |
| `www` | CNAME | `ztmdcg.cn` |
| `gateway` | A | `119.29.120.205` |

如果之前已经添加过 `gitlab` 和 `registry` 两条指向 `117.72.220.94` 的 A 记录，**现在把它们删除**。留着它们只会让人再去对未备案实例试一次 certbot，并再拿到同一个 `403`。京东云一律使用 IP 访问，不需要任何 DNS 记录。

在本地电脑验证；任务要求使用系统自带的 `nslookup`，不要只看浏览器缓存：

```powershell
nslookup ztmdcg.cn
nslookup gateway.ztmdcg.cn
```

成功标志：两个域名都解析到 `119.29.120.205`。腾讯云证书签发前必须先完成解析。

顺带确认 `gitlab.ztmdcg.cn` 已经无法解析或不再指向京东云：

```powershell
nslookup gitlab.ztmdcg.cn
```

成功标志：返回 `NXDOMAIN` / 找不到记录。若仍解析到 `117.72.220.94`，说明 DNSPod 记录没删干净，回到控制台删除后再继续。

### 1.2 京东云安全组

京东云不面向公众，只服务于开发人员和腾讯云拉镜像。入站规则：

- TCP `22`：管理人员与需要 push 的开发人员固定公网 IP，逐个添加来源。
- TCP `443`：开发人员固定公网 IP，另加 `119.29.120.205/32`（Runner 克隆代码需要）。
- TCP `5050`：`119.29.120.205/32`，另加管理人员 IP 便于排查 Registry。

不要把 `443`、`5050` 放开到 `0.0.0.0/0`：私有 CA 只保证传输加密，不提供任何访问控制，公网暴露的 GitLab 登录页会被持续扫描爆破。也不要开放 GitLab 内置 PostgreSQL、Redis、Gitaly 等内部端口。

家用宽带是动态 IP 时，IP 变化后需要回控制台更新来源；不要因为嫌麻烦就改成全网放通。

### 1.3 腾讯云安全组

入站规则：

- TCP `22`：仅你的固定公网 IP。
- TCP `80`、`443`：公网。

不要开放 `3306`、`6379`、`6333`、`6334`、`8848`、`8850`、`8858`、`9000`、`9001`、`18080`、`18081`、`18090`、`18091`。

## 2. 两台服务器共同初始化

分别登录：

```bash
ssh root@117.72.220.94
ssh root@119.29.120.205
```

### 2.0 从云控制台重装 Ubuntu

分别在京东云和腾讯云控制台执行系统重装：

1. 对仍需保留的旧系统盘创建快照，并把数据库、对象文件和密钥异机备份。
2. 选择 `Ubuntu Server 22.04 LTS 64 位`，不安装宝塔或其他运维面板。
3. 优先使用 SSH Key；若平台必须设置临时 root 密码，使用密码管理器生成随机值，首次登录后立即轮换。
4. 确认两台实例仍绑定本文列出的公网 IP，等待实例状态恢复为“运行中”。

重装后本地可能保留旧 SSH 主机指纹，确认控制台显示的实例与 IP 无误后执行：

```powershell
ssh-keygen -R 117.72.220.94
ssh-keygen -R 119.29.120.205
ssh root@117.72.220.94
ssh root@119.29.120.205
```

成功标志：两台机器都能重新建立 SSH 连接。若超时，先查实例状态、安全组 `22` 端口和公网 IP；若提示密钥错误，回到云控制台重置登录凭据，不要放开全网 SSH。

### 2.1 系统检查与基础软件

```bash
cat /etc/os-release
uname -m
free -h
df -h

sudo timedatectl set-timezone Asia/Shanghai
sudo apt-get update
sudo apt-get install -y ca-certificates curl gnupg openssl jq vim htop unzip
```

成功标志：系统为 Ubuntu 22.04、架构为 `x86_64`，时间为东八区。

### 2.2 配置 4GB swap

先检查：

```bash
swapon --show
```

没有 swap 时执行：

```bash
sudo fallocate -l 4G /swapfile
sudo chmod 600 /swapfile
sudo mkswap /swapfile
sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
sudo sysctl vm.swappiness=10
echo 'vm.swappiness=10' | sudo tee /etc/sysctl.d/99-ztmdcg.conf
free -h
```

不得重复向 `/etc/fstab` 写相同记录。

### 2.3 安装 Docker Engine 与 Compose v2

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
docker version
docker compose version
```

配置日志轮转；镜像加速地址失效时删除对应行后重启 Docker：

```bash
sudo tee /etc/docker/daemon.json >/dev/null <<'EOF'
{
  "log-driver": "json-file",
  "log-opts": {
    "max-size": "20m",
    "max-file": "3"
  },
  "registry-mirrors": [
    "https://docker.m.daocloud.io",
    "https://docker.1ms.run"
  ]
}
EOF

sudo dockerd --validate --config-file=/etc/docker/daemon.json
sudo systemctl restart docker
docker info
```

成功标志：能看到 Docker Server 版本和 Compose v2 版本。

## 3. 京东云安装 GitLab CE、私有 CA 与 HTTPS Registry

本章各节的执行机器不同，动手前先对照下表确认你 SSH 在哪台机器上：

| 节 | 执行机器 | 何时执行 |
|---|---|---|
| 3.1 生成私有 CA 与 IP 证书 | 京东云 `117.72.220.94` | 首次部署 |
| 3.2 让 GitLab 自身信任这张 CA | 京东云 `117.72.220.94` | 首次部署 |
| 3.3 安装 GitLab 并配置 `gitlab.rb` | 京东云 `117.72.220.94` | 首次部署 |
| 3.4 已用域名装过一次的补救 | 京东云 `117.72.220.94` | 仅在装包已失败时 |
| 3.5 本机验证 HTTPS 与 Registry | 京东云 `117.72.220.94` | 首次部署 |
| 3.6 证书续期 | 京东云 `117.72.220.94` | **825 天后，首次部署跳过** |
| 3.7 把 CA 分发到腾讯云 | 本地电脑 + 腾讯云 `119.29.120.205` | 首次部署 |
| 3.8 开发人员本地电脑的信任设置 | 本地电脑 | 首次部署 |
| 3.9 首次登录与安全设置 | 浏览器 | 首次部署 |
| 3.10 创建 Group 与两个私有项目 | 浏览器 | 首次部署 |

只有 3.7 涉及腾讯云，且它做的是分发 CA，不是生成证书。**所有 `openssl` 生成命令都只在京东云执行**；腾讯云没有 `/etc/gitlab/` 目录，在那里跑会一路报 `No such file or directory`。

顺序不能颠倒：先生成证书，再让 GitLab 带着证书首次 `reconfigure`，否则 GitLab 会以缺省自签证书启动，后面还要重做一遍。

### 3.1 生成私有 CA 与 IP 证书

先建目录并生成 CA。CA 私钥是这套信任体系的根，只留在京东云本机 `600` 权限，不复制、不进 Git、不进镜像：

```bash
sudo install -d -m 755 /etc/gitlab/ssl
sudo install -d -m 700 /etc/gitlab/ssl/ca-private
cd /etc/gitlab/ssl

sudo openssl req -x509 -newkey rsa:4096 -sha256 -days 3650 -nodes \
  -keyout /etc/gitlab/ssl/ca-private/ztmdcg-ca.key \
  -out /etc/gitlab/ssl/ztmdcg-ca.crt \
  -subj "/C=CN/O=ztmdcg/CN=ztmdcg internal CA" \
  -addext "basicConstraints=critical,CA:TRUE,pathlen:0" \
  -addext "keyUsage=critical,keyCertSign,cRLSign"

sudo chmod 600 /etc/gitlab/ssl/ca-private/ztmdcg-ca.key
sudo chmod 644 /etc/gitlab/ssl/ztmdcg-ca.crt
```

再生成服务器证书。**`subjectAltName` 里必须写 `IP:117.72.220.94`**：Docker、Go、浏览器和 OpenSSL 3 都只看 SAN，把 IP 只写在 `CN` 里会得到 `x509: cannot validate certificate ... doesn't contain any IP SANs`：

```bash
sudo tee /etc/gitlab/ssl/gitlab-ip.ext >/dev/null <<'EOF'
basicConstraints = CA:FALSE
keyUsage = critical, digitalSignature, keyEncipherment
extendedKeyUsage = serverAuth
subjectAltName = IP:117.72.220.94, IP:127.0.0.1
EOF

sudo openssl req -newkey rsa:2048 -sha256 -nodes \
  -keyout /etc/gitlab/ssl/gitlab-ip.key \
  -out /etc/gitlab/ssl/gitlab-ip.csr \
  -subj "/C=CN/O=ztmdcg/CN=117.72.220.94"

sudo openssl x509 -req -sha256 -days 825 \
  -in /etc/gitlab/ssl/gitlab-ip.csr \
  -CA /etc/gitlab/ssl/ztmdcg-ca.crt \
  -CAkey /etc/gitlab/ssl/ca-private/ztmdcg-ca.key \
  -CAcreateserial \
  -extfile /etc/gitlab/ssl/gitlab-ip.ext \
  -out /etc/gitlab/ssl/gitlab-ip.crt

sudo chmod 600 /etc/gitlab/ssl/gitlab-ip.key
sudo chmod 644 /etc/gitlab/ssl/gitlab-ip.crt
sudo rm -f /etc/gitlab/ssl/gitlab-ip.csr
```

`IP:127.0.0.1` 是为了让京东云本机的构建 Runner 与健康检查能用 `https://127.0.0.1` 访问自己，不依赖云平台是否支持访问自身公网 IP。

验证证书链与 SAN：

```bash
sudo openssl verify -CAfile /etc/gitlab/ssl/ztmdcg-ca.crt /etc/gitlab/ssl/gitlab-ip.crt
sudo openssl x509 -in /etc/gitlab/ssl/gitlab-ip.crt -noout -dates \
  -ext subjectAltName
```

成功标志：输出 `gitlab-ip.crt: OK`，SAN 段同时包含 `IP Address:117.72.220.94` 与 `IP Address:127.0.0.1`，`notAfter` 是 825 天后。

叶证书 825 天到期，CA 10 年到期。**把叶证书到期日记进日历**：过期后所有 Pipeline 会同时报 `x509: certificate has expired`。续期方法见 3.6。

### 3.2 让 GitLab 自身信任这张 CA

GitLab omnibus 有独立的信任目录，`gitlab-rake gitlab:check` 与内部组件从这里读 CA：

```bash
sudo install -d -m 755 /etc/gitlab/trusted-certs
sudo install -m 644 /etc/gitlab/ssl/ztmdcg-ca.crt /etc/gitlab/trusted-certs/ztmdcg-ca.crt
```

同时写入系统信任库，供本机 `git`、`curl` 和 `gitlab-runner` 使用：

```bash
sudo install -m 644 /etc/gitlab/ssl/ztmdcg-ca.crt \
  /usr/local/share/ca-certificates/ztmdcg-ca.crt
sudo update-ca-certificates
```

成功标志：`update-ca-certificates` 输出 `1 added`。

### 3.3 安装 GitLab 并配置 `/etc/gitlab/gitlab.rb`

主机名用普通短名，不要再设成 `gitlab.ztmdcg.cn`，避免以后误以为域名可用：

```bash
sudo hostnamectl set-hostname ztmdcg-gitlab
sudo apt-get update
sudo apt-get install -y curl openssh-server ca-certificates tzdata perl postfix
curl -fsSL https://packages.gitlab.com/install/repositories/gitlab/gitlab-ce/script.deb.sh | sudo bash
```

**装包前必须先关掉自动 `reconfigure`。** 这一步不是可选优化：deb 的安装后脚本默认立刻跑一次 `gitlab-ctl reconfigure`，而那时 `gitlab.rb` 里还没有 `letsencrypt['enable'] = false`，omnibus 见到 `external_url` 是 `https` 就会去申请证书，直接撞上未备案拦截的 `403` 并让 `dpkg` 留下半配置状态。3.1 章已经生成证书也挡不住这一步，因为此刻还没有任何配置项告诉 GitLab 用它。

```bash
sudo install -d -m 755 /etc/gitlab
sudo touch /etc/gitlab/skip-auto-reconfigure
sudo apt-get install -y gitlab-ce
```

**不要用 `EXTERNAL_URL="..." apt-get install` 的写法。** 该环境变量只在 `/etc/gitlab/gitlab.rb` 尚不存在时生效；一旦文件已存在（例如之前装过一次），它会被静默忽略，你以为改成了 IP，实际仍在用文件里的旧域名。本手册一律在 `gitlab.rb` 里显式写 `external_url`，不依赖这个变量。

装完包后 `gitlab.rb` 可能还没生成，缺失时从模板补一份，再备份编辑：

```bash
sudo test -f /etc/gitlab/gitlab.rb || sudo cp /opt/gitlab/etc/gitlab.rb.template /etc/gitlab/gitlab.rb
sudo chmod 600 /etc/gitlab/gitlab.rb
sudo cp /etc/gitlab/gitlab.rb /etc/gitlab/gitlab.rb.before-ztmdcg
sudoedit /etc/gitlab/gitlab.rb
```

确认或追加以下配置：

```ruby
external_url 'https://117.72.220.94'
registry_external_url 'https://117.72.220.94:5050'

gitlab_rails['gitlab_signup_enabled'] = false
gitlab_rails['backup_keep_time'] = 604800

# 未备案实例：彻底关闭 Let's Encrypt，否则每次 reconfigure 都会
# 因 HTTP-01 被拦截页面截走而失败，并阻塞整个 reconfigure
letsencrypt['enable'] = false

# 私有 CA 签发的 IP 证书，GitLab Web 与 Registry 复用同一张
nginx['ssl_certificate'] = '/etc/gitlab/ssl/gitlab-ip.crt'
nginx['ssl_certificate_key'] = '/etc/gitlab/ssl/gitlab-ip.key'
registry_nginx['ssl_certificate'] = '/etc/gitlab/ssl/gitlab-ip.crt'
registry_nginx['ssl_certificate_key'] = '/etc/gitlab/ssl/gitlab-ip.key'

# 80 端口在备案通过前会被拦截，不监听、不跳转
nginx['listen_port'] = 443
nginx['listen_https'] = true
nginx['redirect_http_to_https'] = false
registry_nginx['listen_port'] = 5050
registry_nginx['listen_https'] = true
registry_nginx['redirect_http_to_https'] = false

# 4C8G 节省内存
puma['worker_processes'] = 0
sidekiq['concurrency'] = 5
prometheus_monitoring['enable'] = false
```

`letsencrypt['enable'] = false` 是本次修订最关键的一行；漏掉它会重现开头那个 `403` 报错，并且 `reconfigure` 会中断在证书阶段。

从模板生成的 `gitlab.rb` 自带一行注释掉的 `external_url`，若你是追加而不是替换，很容易留下两行。omnibus 取最后一行生效，务必先自查：

```bash
sudo grep -cE "^external_url" /etc/gitlab/gitlab.rb            # 必须输出 1
sudo grep -nE "^registry_external_url" /etc/gitlab/gitlab.rb   # 必须只有 5050 那行
sudo grep -nE "^letsencrypt\['enable'\]" /etc/gitlab/gitlab.rb # 必须只有 false 那行
```

三条都符合预期后再应用配置。`reconfigure` 成功后才能删掉 `skip-auto-reconfigure`，否则后续 `apt upgrade` 又会在你改配置之前自动跑一次：

```bash
sudo gitlab-ctl reconfigure
sudo rm -f /etc/gitlab/skip-auto-reconfigure
sudo gitlab-ctl status
sudo gitlab-rake gitlab:check SANITIZE=true
```

### 3.4 已经用域名装过一次 GitLab 的补救

如果在读到本次修订前，你已经执行过 `EXTERNAL_URL="https://gitlab.ztmdcg.cn" apt-get install -y gitlab-ce`，会看到安装以这段报错结束：

```text
letsencrypt_certificate[gitlab.ztmdcg.cn] ... had an error: RuntimeError:
[gitlab.ztmdcg.cn] Validation failed, unable to request certificate,
Errors: [... "detail"=>"117.72.220.94: Invalid response from
http://gitlab.ztmdcg.cn/.well-known/acme-challenge/...: 403" ...]
dpkg: error processing package gitlab-ce (--configure)
```

**不需要卸载重装。** GitLab 本体已经装好（日志里 `541 resources updated`，root 初始密码也已写入 `/etc/gitlab/initial_root_password`），只有证书那一步失败，导致 `dpkg` 停在半配置状态。

> **本节自成一套，按这里的命令走完即可。** 不要回到 3.3 重跑装包命令：`gitlab-ce` 已经装上了，再执行 `apt-get install` 只会让 `apt` 去收拾那个半配置的包，在你改好配置之前又失败一次，而且报错信息还会变样，更难判断。

先确认当前配置里到底写的是什么：

```bash
sudo grep -nE "^external_url|^registry_external_url|^letsencrypt" /etc/gitlab/gitlab.rb
```

看到 `external_url 'https://gitlab.ztmdcg.cn'` 即可确诊。

**第 1 步**，挡住 `apt` 的自动 `reconfigure`，避免后续任何 `apt` 操作反复触发失败：

```bash
sudo touch /etc/gitlab/skip-auto-reconfigure
```

**第 2 步**，执行 3.1、3.2 生成并信任私有 CA 与 IP 证书（此前跳过了这两节）。完成后确认产物齐全，输出必须是 `gitlab-ip.crt: OK`，否则不要往下走：

```bash
ls -l /etc/gitlab/ssl/
sudo openssl verify -CAfile /etc/gitlab/ssl/ztmdcg-ca.crt /etc/gitlab/ssl/gitlab-ip.crt
```

**第 3 步**，备份并编辑配置。把已有的 `external_url` 那行**改写**成 IP，不要新增一行，其余按 3.3 的 ruby 配置块补齐：

```bash
sudo cp /etc/gitlab/gitlab.rb /etc/gitlab/gitlab.rb.before-ztmdcg
sudoedit /etc/gitlab/gitlab.rb
```

**第 4 步**，用 3.3 末尾的三条 `grep` 自查，确认没有残留的域名行和 `letsencrypt['enable'] = true`。

**第 5 步**，重新配置并修复 dpkg：

```bash
sudo gitlab-ctl reconfigure
sudo dpkg --configure -a
sudo rm -f /etc/gitlab/skip-auto-reconfigure
sudo gitlab-ctl status
```

`dpkg --configure -a` 会重跑安装后脚本收尾。此时 `gitlab.rb` 已指向 IP、Let's Encrypt 已关闭，不会再触发证书申请。

成功标志：`reconfigure` 跑满几分钟且结尾没有 `letsencrypt` 相关报错，`dpkg --configure -a` 正常结束，`gitlab-ctl status` 中各组件为 `run`。随后继续 3.5 的本机验证。

初始密码文件的 24 小时清理倒计时从**首次安装**就开始了，尽快登录改密；若文件已消失，用 `sudo gitlab-rake "gitlab:password:reset[root]"` 重置。

#### 中途改过主机名

若在补救过程中执行过 `hostnamectl set-hostname`，`apt` 或 `dpkg` 可能改报：

```text
It looks like there was a problem with public attributes; run gitlab-ctl reconfigure manually to fix.
```

omnibus 把上一次 `reconfigure` 的节点属性缓存成**以主机名命名**的 JSON，改名后按新名字找不到文件。确认：

```bash
ls -l /opt/gitlab/embedded/nodes/
hostname -f
```

文件名仍是旧主机名即可确诊。这个状态无害，上面第 5 步的 `reconfigure` 会按新主机名重新生成；**但它不能替代第 3 步**——配置没改就直接 `reconfigure`，仍会死在 Let's Encrypt 上。

### 3.5 京东云本机验证 HTTPS 与 Registry

```bash
curl --cacert /etc/gitlab/ssl/ztmdcg-ca.crt -I https://127.0.0.1/users/sign_in
curl --cacert /etc/gitlab/ssl/ztmdcg-ca.crt -I https://127.0.0.1:5050/v2/
sudo ss -lntp | grep -E ':(443|5050)\s'
```

成功标志：

- GitLab 登录页返回 `200` 或 `302`。
- Registry `/v2/` 返回 `401 Unauthorized`——这表示 HTTPS 与鉴权链路正常，不是错误。
- `443` 与 `5050` 都由 `nginx` 监听；`80` 没有监听进程。

再确认本机能否访问自身公网 IP（部分云平台不支持这种回环，构建 Runner 推镜像依赖它）：

```bash
curl --cacert /etc/gitlab/ssl/ztmdcg-ca.crt -sS -o /dev/null \
  -w 'public-ip reachable: %{http_code}\n' https://117.72.220.94:5050/v2/
```

成功标志：输出 `public-ip reachable: 401`。

若超时或连接被拒，说明京东云不支持访问自身公网 IP，补一条 NAT 规则把本机与容器发往公网 IP 的流量折回内网地址：

```bash
PRIVATE_IP="$(ip -4 -o addr show scope global | awk '{print $4}' | cut -d/ -f1 | head -n1)"
echo "private ip: $PRIVATE_IP"
sudo iptables -t nat -A OUTPUT -d 117.72.220.94 -j DNAT --to-destination "$PRIVATE_IP"
sudo iptables -t nat -A PREROUTING -d 117.72.220.94 -j DNAT --to-destination "$PRIVATE_IP"
sudo apt-get install -y iptables-persistent
sudo netfilter-persistent save
```

`OUTPUT` 规则覆盖本机进程，`PREROUTING` 覆盖 Docker 容器里的构建流量，两条都要有。加完重跑上面的 `curl` 确认返回 `401`。

### 3.6 证书续期

> **首次部署请跳过本节，直接看 3.7。** 本节是 825 天后的运维操作，在京东云执行。
>
> 这里的 `openssl` 命令与 3.1 高度相似，区别是产物带 `.new` 后缀、且复用已有的 CA 和 `gitlab-ip.ext`。**如果你是来找"生成证书"的步骤，你要的是 3.1，不是这里**——本节的命令依赖 3.1 已经生成过 `ztmdcg-ca.crt` 和 `gitlab-ip.ext`，在全新机器上执行只会报 `No such file or directory`。

叶证书 825 天后过期，CA 私钥仍在 `/etc/gitlab/ssl/ca-private/`，续期只需重签叶证书，**CA 不动，所有客户端不需要重新分发**：

```bash
sudo openssl req -newkey rsa:2048 -sha256 -nodes \
  -keyout /etc/gitlab/ssl/gitlab-ip.key.new \
  -out /etc/gitlab/ssl/gitlab-ip.csr \
  -subj "/C=CN/O=ztmdcg/CN=117.72.220.94"

sudo openssl x509 -req -sha256 -days 825 \
  -in /etc/gitlab/ssl/gitlab-ip.csr \
  -CA /etc/gitlab/ssl/ztmdcg-ca.crt \
  -CAkey /etc/gitlab/ssl/ca-private/ztmdcg-ca.key \
  -CAcreateserial \
  -extfile /etc/gitlab/ssl/gitlab-ip.ext \
  -out /etc/gitlab/ssl/gitlab-ip.crt.new

sudo openssl verify -CAfile /etc/gitlab/ssl/ztmdcg-ca.crt /etc/gitlab/ssl/gitlab-ip.crt.new
```

`verify` 通过后再替换并重载，失败时旧证书仍然有效：

```bash
sudo mv /etc/gitlab/ssl/gitlab-ip.key.new /etc/gitlab/ssl/gitlab-ip.key
sudo mv /etc/gitlab/ssl/gitlab-ip.crt.new /etc/gitlab/ssl/gitlab-ip.crt
sudo chmod 600 /etc/gitlab/ssl/gitlab-ip.key
sudo chmod 644 /etc/gitlab/ssl/gitlab-ip.crt
sudo rm -f /etc/gitlab/ssl/gitlab-ip.csr
sudo gitlab-ctl restart nginx
sudo gitlab-ctl restart registry
```

CA 本身到期（10 年）时才需要重做 3.1 并重新执行 3.7、3.8 的分发。

### 3.7 把 CA 分发到腾讯云

在 **本地电脑** PowerShell 把 CA 公钥从京东云取到腾讯云。CA 公钥不是机密，但要确认内容没被截断：

```powershell
scp root@117.72.220.94:/etc/gitlab/ssl/ztmdcg-ca.crt "$env:TEMP\ztmdcg-ca.crt"
scp "$env:TEMP\ztmdcg-ca.crt" root@119.29.120.205:/tmp/ztmdcg-ca.crt
```

在 **腾讯云** 安装到两个位置。系统信任库供 `git`、`curl` 和 `gitlab-runner` 使用；`certs.d` 供 Docker 拉镜像使用，目录名必须与镜像地址里的 `主机:端口` 完全一致：

```bash
sudo install -m 644 /tmp/ztmdcg-ca.crt /usr/local/share/ca-certificates/ztmdcg-ca.crt
sudo update-ca-certificates

sudo install -d -m 755 '/etc/docker/certs.d/117.72.220.94:5050'
sudo install -m 644 /tmp/ztmdcg-ca.crt '/etc/docker/certs.d/117.72.220.94:5050/ca.crt'
rm -f /tmp/ztmdcg-ca.crt
```

验证（本步骤要求京东云安全组已放通 `119.29.120.205` 的 `443` **和 `5050`**，两条规则要分别添加，只加 443 会让下面第二条超时）。加 `--connect-timeout` 避免被拦截时干等两分钟：

```bash
curl -sS --connect-timeout 10 -o /dev/null -w 'gitlab: %{http_code}\n' https://117.72.220.94/users/sign_in
curl -sS --connect-timeout 10 -o /dev/null -w 'registry: %{http_code}\n' https://117.72.220.94:5050/v2/
```

成功标志：分别返回 `gitlab: 200`（或 `302`）与 `registry: 401`，并且**没有加 `--cacert` 也没有报证书错误**——说明系统信任库已生效。若出现 `SSL certificate problem: unable to get local issuer certificate`，回到 `update-ca-certificates` 检查；不要改用 `curl -k`。

失败时先分清是哪一类，两者原因完全不同：

| curl 报错 | 含义 | 排查方向 |
|---|---|---|
| `Connection timed out` | 包被静默丢弃，没到达主机 | 京东云安全组缺对应端口规则；其次查 `ufw status` |
| `Connection refused` | 包到了主机，但没有进程监听 | 京东云上查 `ss -lntp \| grep 5050` 与 `gitlab-ctl status registry` |
| `SSL certificate problem` | 网络通了，证书不被信任 | 本机 CA 没装好，回 `update-ca-certificates` |

在京东云本机自查，可把「服务问题」与「安全组问题」彻底分开——本机 `401` 而腾讯云超时，就一定是安全组：

```bash
sudo ss -lntp | grep 5050
sudo gitlab-ctl status registry
curl --cacert /etc/gitlab/ssl/ztmdcg-ca.crt -o /dev/null \
  -w 'local registry: %{http_code}\n' https://127.0.0.1:5050/v2/
```

上面两条 `curl` 只验证了系统信任库。`certs.d` 要等 Registry 里真的有镜像才能验证，第 9 章第一次 `docker pull` 会自然覆盖到；那一步若报 `x509`，就回来检查目录名是否精确等于 `117.72.220.94:5050`。Docker 读取 `certs.d` 不需要重启 daemon。

### 3.8 开发人员本地电脑的信任设置

本节全部在**本地电脑**（Windows）操作，每台需要访问 GitLab 的开发机各做一次。

#### 3.8.0 先判断你要不要做

| 你要做的事 | 是否需要本节 |
|---|---|
| 只用 `git push` / `git pull` 推拉代码 | **不需要**。第 8 章走 SSH，SSH 不使用 HTTPS 证书 |
| 用浏览器打开 GitLab 看流水线、改设置 | 需要，否则每次都是证书警告页 |
| 用 HTTPS 方式 `git clone` | 需要，否则报 `SSL certificate problem` |

只推代码的人可以直接跳到第 4 章。下面的步骤只是让浏览器不再报警告，不影响任何功能。

#### 3.8.1 把 CA 证书取到本地电脑

CA 公钥文件在京东云的 `/etc/gitlab/ssl/ztmdcg-ca.crt`，由 3.1 生成。它**不是机密**（私钥才是，私钥留在服务器 `ca-private/` 里不外传），可以正常复制。

方式一，在本地 PowerShell 用 `scp`（Windows 10/11 自带）：

```powershell
mkdir -Force "$env:USERPROFILE\.ssh" | Out-Null
scp root@117.72.220.94:/etc/gitlab/ssl/ztmdcg-ca.crt "$env:USERPROFILE\.ssh\ztmdcg-ca.crt"
```

方式二，用 MobaXterm：连上京东云后，窗口左侧是 SFTP 文件浏览器，把路径切到 `/etc/gitlab/ssl/`，找到 `ztmdcg-ca.crt`，直接拖到桌面，再移动到 `C:\Users\<你的用户名>\.ssh\` 下。

取回后核对指纹，确认文件完整且没被掉包。先在**京东云**读出指纹：

```bash
openssl x509 -in /etc/gitlab/ssl/ztmdcg-ca.crt -noout -fingerprint -sha1
```

再在**本地 PowerShell** 读出同一个值：

```powershell
(Get-PfxCertificate "$env:USERPROFILE\.ssh\ztmdcg-ca.crt").Thumbprint
```

成功标志：两串十六进制完全一致（服务器输出带冒号，Windows 不带，忽略冒号比对）。**不一致就停下**，不要导入，重新复制文件。

#### 3.8.2 导入 Windows 证书存储

Chrome 和 Edge 都使用 Windows 的证书存储，导入一次两个浏览器都生效。

方式一，PowerShell 一条命令。**必须以管理员身份运行 PowerShell**（开始菜单搜索 PowerShell → 右键 → 以管理员身份运行）：

```powershell
Import-Certificate -FilePath "$env:USERPROFILE\.ssh\ztmdcg-ca.crt" `
  -CertStoreLocation Cert:\LocalMachine\Root
```

方式二，图形界面：双击 `ztmdcg-ca.crt` → 安装证书 → 存储位置选**本地计算机** → 下一步 → 选「将所有的证书都放入下列存储」→ 浏览 → 选**受信任的根证书颁发机构** → 下一步 → 完成。会弹出安全警告问是否确认安装，确认即可。

验证导入成功：

```powershell
Get-ChildItem Cert:\LocalMachine\Root |
  Where-Object { $_.Subject -like '*ztmdcg*' } |
  Select-Object Subject, Thumbprint, NotAfter
```

成功标志：列出一条 `CN=ztmdcg internal CA`，`Thumbprint` 与 3.8.1 核对的一致。

**导入后必须完全退出浏览器再重开**——不是关标签页，是右键任务栏图标退出，或结束所有 `chrome.exe` / `msedge.exe` 进程。浏览器只在启动时读取证书存储。

然后访问 `https://117.72.220.94`，地址栏应显示正常的锁图标，没有警告页。

#### 3.8.3 Firefox 需要单独导入

Firefox 不用 Windows 证书存储，有自己独立的一套。用 Firefox 的话额外做：

设置 → 在搜索框输入「证书」→ 查看证书 → 证书颁发机构 → 导入 → 选 `ztmdcg-ca.crt` → 勾选「信任由此证书颁发机构来标识网站」→ 确定。

不用 Firefox 就跳过这一步。

#### 3.8.4 不想导入证书的替代做法

直接在警告页点「高级」→「继续前往 117.72.220.94（不安全）」也能正常使用 GitLab 的全部功能。代价是每次新开浏览器都要点一遍——Chrome 对 IP 地址的证书例外不做持久保存。

偶尔看一眼流水线的话，这样完全够用，不必折腾导入。

#### 3.8.5 HTTPS 克隆（可选）

推荐用 SSH（第 8 章），不需要任何证书配置。确实要用 HTTPS 克隆时，给这个地址单独指定 CA：

```powershell
git config --global http."https://117.72.220.94/".sslCAInfo "$env:USERPROFILE\.ssh\ztmdcg-ca.crt"
```

注意这条命令里的地址**必须带结尾斜杠**，否则不匹配。验证：

```powershell
git config --global --get-regexp "http\..*sslCAInfo"
```

**不要**用 `git config --global http.sslVerify false`。那会对所有仓库关闭证书校验，包括 GitHub 和公司内网的其他 Git 服务，等于把整台机器的 Git 传输安全关掉，而你只是想解决一个地址的问题。

#### 3.8.6 撤销

备案通过切回域名、或这台电脑不再需要访问 GitLab 时，按需清理：

```powershell
# 移除导入的根证书（管理员 PowerShell，Thumbprint 换成 3.8.2 查到的值）
Get-ChildItem Cert:\LocalMachine\Root |
  Where-Object { $_.Subject -like '*ztmdcg*' } | Remove-Item

# 移除 git 的 CA 配置
git config --global --unset http."https://117.72.220.94/".sslCAInfo

# 删除本地 CA 文件
Remove-Item "$env:USERPROFILE\.ssh\ztmdcg-ca.crt"
```

Firefox 里的需要回到 3.8.3 那个界面手工删除。

### 3.9 首次登录与安全设置

```bash
sudo cat /etc/gitlab/initial_root_password
```

浏览器打开 `https://117.72.220.94`，使用 `root` 登录并立即：

1. 修改 root 密码。
2. 添加管理员 SSH Key。
3. 条件允许时启用双因素认证。
4. 确认注册入口已关闭。

初始密码文件约 24 小时后自动删除。

未导入 CA 时浏览器会显示证书警告，证书详情里的颁发者应为 `ztmdcg internal CA`。**如果颁发者是别的名字，立即停止并排查中间人风险**，不要点继续。

### 3.10 创建 Group 与两个私有项目

在 GitLab 页面创建私有 Group：`ztmdcg`，再创建两个空项目：

- `ztmdcg/llm-gateway`
- `ztmdcg/soft-training`

创建时不要初始化 README，避免第一次 push 产生无关历史。首次 push 完成后，将 `main` 设置为默认分支并保护：允许 Maintainer push/merge，禁止普通 Developer 直接推生产分支。

两个项目都进入 `设置 → CI/CD → 制品库和容器镜像库`（旧版路径为 `Settings → Packages and registries → Container Registry`），启用清理策略，至少保留最近 5 个镜像标签；镜像标签是提交 SHA，不依赖 `latest`。菜单名随版本变动时，认准「清理策略 / Cleanup policies」即可。

此时两个项目的镜像地址分别是：

- `117.72.220.94:5050/ztmdcg/llm-gateway/gateway`、`.../ui`
- `117.72.220.94:5050/ztmdcg/soft-training/backend`、`.../frontend`

CI 中由 `$CI_REGISTRY_IMAGE` 自动生成，不需要手写；本文只在 `.env.example` 里出现示例值。

## 4. 安装与注册 GitLab Runner

### 4.1 两台机器安装 Runner 软件包

**京东云上先确认 dpkg 是干净的**，否则 `apt` 会拒绝安装任何新包，而报错信息看起来像是 Runner 的问题：

```bash
sudo dpkg --configure -a
dpkg -l gitlab-ce | tail -n 1
```

输出必须以 `ii` 开头（已安装且配置完成）。若是 `iF`／`iU`，说明第 3 章的 `gitlab-ce` 还没收尾，回 3.4 处理完再继续。腾讯云没装 GitLab，跳过这一步。

`packages.gitlab.com` 在国内下载极慢，两台都改用清华镜像。GPG key 仍从官方取（仅 3KB），几十 MB 的包体走镜像：

```bash
# 清掉可能已写入的官方源，否则每次 apt update 仍会连那个慢地址
sudo rm -f /etc/apt/sources.list.d/runner_gitlab-runner.list

curl -fsSL https://packages.gitlab.com/runner/gitlab-runner/gpgkey \
  | sudo gpg --dearmor -o /usr/share/keyrings/gitlab-runner-archive-keyring.gpg

. /etc/os-release
echo "deb [signed-by=/usr/share/keyrings/gitlab-runner-archive-keyring.gpg] https://mirrors.tuna.tsinghua.edu.cn/gitlab-runner/ubuntu ${VERSION_CODENAME} main" \
  | sudo tee /etc/apt/sources.list.d/gitlab-runner.list

sudo apt-get update
sudo apt-get install -y gitlab-runner
sudo systemctl enable --now gitlab-runner
gitlab-runner --version
```

成功标志：`gitlab-runner --version` 输出的 `Version` 与 GitLab 服务端主版本一致（服务端版本见 `sudo gitlab-rake gitlab:env:info`）。Runner 比服务端低一个大版本以上时部分特性不可用，尽量对齐。

镜像失效或版本落后时，改回官方源即可，只是慢：

```bash
curl -fsSL https://packages.gitlab.com/install/repositories/runner/gitlab-runner/script.deb.sh | sudo bash
sudo apt-get install -y gitlab-runner
```

### 4.2 京东云注册构建 Runner

#### 4.2.1 在网页上创建 Runner 条目

**入口不在「设置 → CI/CD」。** GitLab 16 之后 Runner 的列表与创建按钮移到了「构建」下；`设置 → CI/CD → Runner` 那一节现在只剩三个开关（是否启用实例 Runner、是否允许项目覆盖、是否允许旧版注册令牌），没有创建按钮。

左侧边栏 **构建 (Build) → Runners**，或直接访问：

```text
https://117.72.220.94/groups/ztmdcg/-/runners
```

点右上角「新建群组 runner」/「New group runner」，创建页地址是 `.../-/runners/new`。按下表填写：

| 表单项 | 填什么 | 说明 |
|---|---|---|
| 平台 / Platform | Linux | |
| 标签 / Tags | **留空** | 与下一行必须同时满足 |
| 运行未标记的作业 / Run untagged jobs | **勾选** | 构建 Job 未打 tag，不勾则此 Runner 收不到任务 |
| 描述 / Description | `jd-build-runner` | |
| 受保护 / Protected | **不勾** | 勾选后普通分支无法运行测试 |
| 最长作业超时 / Maximum job timeout | `3600` | Maven 与 npm 首次构建较慢 |

提交后页面显示一段含 `glrt-` 开头令牌的注册命令。**该令牌只显示一次**，先复制保存。

网页给出的命令是简化版，**缺 `--tls-ca-file`**，私有 CA 环境下直接照抄会让每个 Job 在克隆阶段报 `x509: certificate signed by unknown authority`。用下面 4.2.2 的完整命令。

#### 4.2.2 在京东云执行注册

先把 CA 放到 Runner 的证书目录，注册时显式指定，Runner 才能克隆代码和上传产物：

```bash
sudo install -d -m 755 /etc/gitlab-runner/certs
sudo install -m 644 /etc/gitlab/ssl/ztmdcg-ca.crt /etc/gitlab-runner/certs/ztmdcg-ca.crt
```

复制一次性显示的 `glrt-...` 认证令牌，在京东云执行：

```bash
sudo gitlab-runner register --non-interactive \
  --url "https://117.72.220.94" \
  --token "<JD_BUILD_RUNNER_TOKEN>" \
  --tls-ca-file "/etc/gitlab-runner/certs/ztmdcg-ca.crt" \
  --executor "docker" \
  --docker-image "alpine:3.21" \
  --description "jd-build-runner"
```

漏掉 `--tls-ca-file` 会让每个 Job 在 `git clone` 阶段就报 `x509: certificate signed by unknown authority`。Runner 会把这个 CA 自动传递给 Job 容器用于克隆和 artifacts，但**不会**传给 dind 服务容器，镜像推送另需下一节处理。

#### 4.2.3 调整 `config.toml`

注册命令已经写好了 `url`、`token`、`tls-ca-file`、`executor` 等字段，**只需要改一处、加一处**：

```bash
sudo vim /etc/gitlab-runner/config.toml
```

| 位置 | 原值 | 改成 | 为什么 |
|---|---|---|---|
| `[runners.docker]` 下 | `privileged = false` | `privileged = true` | `build_images` 用 `docker:27-dind` 构建镜像，dind 必须特权模式，否则报 `Cannot connect to the Docker daemon` |
| `[[runners]]` 下（新增一行） | — | `request_concurrency = 1` | 这台机器同时跑着 GitLab 本身，限制并发避免构建吃光内存 |

顶层 `concurrent = 1` 注册时已是默认值，确认存在即可。改完大致长这样（省略号处保留注册生成的原有字段，一个都不要删）：

```toml
concurrent = 1

[[runners]]
  name = "jd-build-runner"
  url = "https://117.72.220.94"
  token = "glrt-..."
  tls-ca-file = "/etc/gitlab-runner/certs/ztmdcg-ca.crt"
  executor = "docker"
  request_concurrency = 1
  ...
  [runners.docker]
    image = "alpine:3.21"
    privileged = true
    volumes = ["/cache"]
    ...
```

注意配置文件里的键名是 `tls-ca-file`（连字符），与命令行参数 `--tls-ca-file` 一致，不是下划线。

`[runners.docker]` 里的 `tls_verify = false` **保持默认不要动**——它控制的是「Runner 连接远程 Docker daemon 时是否校验证书」，与私有 CA 和 Registry 无关，容易与证书问题混淆。

改完确认并重启：

```bash
sudo grep -nE "^concurrent|privileged|request_concurrency|tls-ca-file" /etc/gitlab-runner/config.toml
sudo gitlab-runner verify
sudo systemctl restart gitlab-runner
sudo journalctl -u gitlab-runner -n 50 --no-pager | grep -iE "x509|certificate|error" || echo "no TLS errors"
```

成功标志：`concurrent = 1`、`privileged = true`、`tls-ca-file` 三项都在，`verify` 退出码为 0，日志无 `x509` 报错。回到网页的 Runners 列表刷新，`jd-build-runner` 应为绿色在线。

#### 4.2.4 让京东云 Docker 信任私有 CA

京东云的 Docker daemon 需要拉两类镜像：CI 测试镜像（`ci-test`，见 4.5）作为 Job 容器，以及构建产物。两者都在 `117.72.220.94:5050`，因此本机 daemon 也必须信任自签 CA——**这一步与 3.7 章给腾讯云做的是同一件事，只是换成京东云自己**：

```bash
sudo install -d -m 755 '/etc/docker/certs.d/117.72.220.94:5050'
sudo install -m 644 /etc/gitlab/ssl/ztmdcg-ca.crt \
  '/etc/docker/certs.d/117.72.220.94:5050/ca.crt'
sudo docker login 117.72.220.94:5050
```

用 GitLab 用户名和密码（或个人访问令牌）登录，成功后应输出 `Login Succeeded`。目录名必须精确等于 `117.72.220.94:5050`；报 `x509` 就先核对这个目录名。Docker 读取 `certs.d` 不需要重启 daemon。

漏掉这一步的典型症状：Job 一开始就失败在拉取镜像阶段，报 `x509: certificate signed by unknown authority`，日志里还没跑到任何测试。

#### 4.2.5 dind 与私有 CA Registry

构建 Job 在 `docker:27-dind` 服务容器里 `docker push`。dind 的 Docker daemon 不读宿主机的 `/etc/docker/certs.d`，也拿不到 Runner 的 CA；而 Docker 卷挂载语法用冒号分隔，无法把宿主目录挂到 `/etc/docker/certs.d/117.72.220.94:5050/` 这种带端口的路径上。

因此两个仓库的 `.gitlab-ci.yml` 都给 dind 加了一行参数（已随本次修订写入仓库，无需手改）：

```yaml
    - name: docker:27-dind
      alias: docker
      command:
        - --insecure-registry=117.72.220.94:5050
        - --registry-mirror=https://docker.m.daocloud.io
        - --registry-mirror=https://docker.1ms.run
```

需要理解这个取舍的边界：`--insecure-registry` 让 dind **跳过证书校验**，但连接仍是 TLS，不会退回明文；且这一跳发生在京东云同一台机器内部（dind → 本机 Registry），不穿越公网。真正跨机的那一跳是京东云 → 腾讯云拉镜像，那一跳由 3.7 章的 `certs.d` 做**完整证书校验**，没有放宽。

不要因为图省事就在腾讯云的 `/etc/docker/daemon.json` 里也加 `insecure-registries`——那会让公网跨机传输失去校验，是本方案里唯一不能妥协的地方。

### 4.3 腾讯云注册部署 Runner

在同一 Group 新建另一个 Runner：

- Description：`tencent-production-runner`
- Tag：`ztmdcg-production`
- Run untagged jobs：关闭
- Protected：开启
- Scope：只属于 `ztmdcg` Group；若界面显示 “Lock to current projects”，将其锁定到 `llm-gateway` 与 `soft-training`，不要注册成全实例共享 Runner

在腾讯云执行。这里必须先完成 3.7 章的 CA 安装，否则注册就会失败：

```bash
sudo install -d -m 755 /etc/gitlab-runner/certs
sudo install -m 644 /usr/local/share/ca-certificates/ztmdcg-ca.crt \
  /etc/gitlab-runner/certs/ztmdcg-ca.crt

sudo gitlab-runner register --non-interactive \
  --url "https://117.72.220.94" \
  --token "<TENCENT_DEPLOY_RUNNER_TOKEN>" \
  --tls-ca-file "/etc/gitlab-runner/certs/ztmdcg-ca.crt" \
  --executor "shell" \
  --description "tencent-production-runner"

sudo usermod -aG docker gitlab-runner
sudo systemctl restart gitlab-runner
sudo -u gitlab-runner -H docker version
sudo -u gitlab-runner -H docker compose version
```

确认部署 Runner 能以 `gitlab-runner` 身份登录 Registry 并完成证书校验：

```bash
sudo -u gitlab-runner -H curl -sS -o /dev/null \
  -w 'registry from runner: %{http_code}\n' https://117.72.220.94:5050/v2/
```

成功标志：输出 `registry from runner: 401`，没有任何 TLS 报错。

确认 `/etc/gitlab-runner/config.toml` 顶层为 `concurrent = 1`，然后执行：

```bash
sudo gitlab-runner verify
sudo journalctl -u gitlab-runner -n 100 --no-pager
```

成功标志：GitLab Group 页面中两个 Runner 均为绿色在线，部署 Runner 只接收带 `ztmdcg-production` 标签且来自受保护分支的 Job。部署脚本的 `/var/lock/ztmdcg-deploy.lock` 负责两个项目之间的互斥，CI 中的 `resource_group` 负责项目内串行。

> `gitlab-runner` 加入 `docker` 组后等同拥有主机 root 级能力，因此腾讯云必须作为专用部署机，Runner 只能绑定受控的私有项目。

### 4.4 构建 CI 测试镜像

后端测试需要真实的 MySQL 和 Redis。本方案把两个中间件**预装进一个自建镜像**，而不是用 GitLab 的 `services:`。两个原因：

第一个是硬性的。`RedisResponseCacheIntegrationTest` 写死连 `127.0.0.1:16379`。`services:` 起的容器只能通过**别名主机名**访问（如 `redis:6379`），Job 容器里的 `127.0.0.1` 指向自己，永远连不上。把 Redis 放进 Job 容器内，`127.0.0.1:16379` 自然成立，测试源码一行都不用改。

第二个是维护性。中间件随镜像构建一次，之后每条流水线直接复用：不再拉 service 镜像、不再跑"等端口通"的探测循环、不在 `before_script` 里临时 `apt install`。要改中间件配置，只改 `deploy/ci/` 下的文件再重建镜像，不必逐个改流水线。

镜像相关文件都在 `llm-gateway-project/deploy/ci/`：

| 文件 | 作用 |
|---|---|
| `Dockerfile` | 基于 `maven:3.9-eclipse-temurin-21`，装 MySQL、Redis，构建期完成 MySQL datadir 初始化 |
| `ci-services.sh` | 容器内启动两个服务并**等到真正可用**；任一超时即非零退出 |
| `mysql-init.sql` | 启动时设置 root 密码、建 `llm_gateway` 与 `soft_training` 两个库 |
| `mysqld-ci.cnf` | 只监听回环、关闭 binlog、限制 buffer pool 到 256M |
| `redis-ci.conf` | 监听 `16379`、关闭持久化、`maxmemory 256mb` |

在**京东云**执行构建与推送（需先完成 4.2.4 的 `docker login`）。京东云本机已在 3.2 把私有 CA 写入系统信任库，因此可直接用 HTTPS 克隆，不需要给这台机器配 SSH key：

```bash
sudo install -d -m 755 /srv/build
cd /srv/build && sudo rm -rf llm-gateway-ci
sudo git clone --depth 1 https://117.72.220.94/ztmdcg/llm-gateway.git llm-gateway-ci
cd llm-gateway-ci
sudo docker build -t 117.72.220.94:5050/ztmdcg/llm-gateway/ci-test:1 deploy/ci
sudo docker push 117.72.220.94:5050/ztmdcg/llm-gateway/ci-test:1
```

克隆私有仓库时会提示输入用户名和密码：用户名填 GitLab 账号，密码处填**个人访问令牌**（Preferences → Access Tokens，勾 `read_repository`），不是登录密码。若提示 `SSL certificate problem`，说明 3.2 的系统信任库那步没做，回去补 `update-ca-certificates`。

也可以完全不克隆——`deploy/ci` 只有 5 个小文件，用 `scp` 从本地电脑传更省事：

```powershell
scp -r "C:\practice\llm-gateway-project\deploy\ci" root@117.72.220.94:/srv/build/ci
```

然后在京东云 `sudo docker build -t 117.72.220.94:5050/ztmdcg/llm-gateway/ci-test:1 /srv/build/ci`。

推送若报 `error from registry: blob unknown to registry`，且前面所有层都显示 `Pushed`，那是 BuildKit 的已知问题：它上传的 manifest index 带有无效引用，注册表按清单去取 blob 时找不到。用经典构建器重建即可绕开。**必须先 `rmi` 删掉本地镜像**，否则会直接复用缓存里那个有问题的清单，等于没改：

```bash
sudo docker rmi 117.72.220.94:5050/ztmdcg/llm-gateway/ci-test:1
sudo DOCKER_BUILDKIT=0 docker build \
  -t 117.72.220.94:5050/ztmdcg/llm-gateway/ci-test:1 /srv/build/ci
sudo docker push 117.72.220.94:5050/ztmdcg/llm-gateway/ci-test:1
```

想继续用 BuildKit 则显式关掉证明信息：`docker buildx build --provenance=false --sbom=false --load -t ... /srv/build/ci`。

推送前顺带确认 Registry 存储没写满：

```bash
df -h /var/opt/gitlab
sudo du -sh /var/opt/gitlab/gitlab-rails/shared/registry
```

首次构建要下载 JDK 层、MySQL 与 Redis 的 APT 包，视网络约 5-15 分钟。`Dockerfile` 已把 APT 源换成阿里云；这个耗时只发生在构建镜像时，不进入日常流水线。

成功标志：`docker push` 结束后，GitLab 项目页 Deploy → Container Registry 里能看到 `ci-test` 仓库和 `1` 这个 tag。

自检镜像本身可用，不必等流水线：

```bash
sudo docker run --rm 117.72.220.94:5050/ztmdcg/llm-gateway/ci-test:1 \
  bash -lc 'ci-services start && mysql -h127.0.0.1 -uroot -pci-test-mysql-password \
    -e "SHOW DATABASES" && redis-cli -h 127.0.0.1 -p 16379 ping'
```

成功标志：输出 `[ci-services] all services ready`、库列表含 `llm_gateway` 与 `soft_training`、最后一行 `PONG`。

**维护规则：改动 `deploy/ci/` 后必须递增 tag**（`:2`、`:3`……），同时改 `.gitlab-ci.yml` 里的 `CI_TEST_IMAGE`。不要覆盖推送同一个 tag——Runner 主机上可能留有同名旧层，会出现"明明改了却没生效"，这类问题很难从 Job 日志看出来。

### 4.5 CI 镜像与生产的版本差异

这是一个需要你知情的取舍。生产用 `mysql:8.4` 和 `redis:7.4-alpine`，而 CI 镜像用 Ubuntu 22.04 仓库自带的 **MySQL 8.0** 与 **Redis 6.x**。

选择 Ubuntu 自带包是因为它稳定可得。装 MySQL 8.4 需要走 MySQL 官方 APT 源，我无法在你的环境里核实该源从京东云的可达性，把不确定的外部依赖放进构建链会让镜像随时构建失败。

判断这个差异是否可接受，看测试实际用到什么：当前 Flyway 迁移 `V1`–`V8` 都是通用 SQL，不含任何 8.4 专有语法；Redis 侧只用到 `SET`/`GET`/`hasKey`/`flushDb` 这类基础命令，以及一个自写的 Lua 脚本，都不涉及 7.x 新特性。因此这些用例在 8.0/6.x 上的行为与生产一致。

要注意的边界：**该差异不为将来兜底**。如果以后的迁移脚本用到 8.4 新语法，或代码用到 Redis 7 的新命令，CI 会通过而生产可能失败。真正需要严格对齐时，改用 MySQL 官方 APT 源重建本镜像（先在京东云 `curl -I https://repo.mysql.com/` 确认可达），或把 MySQL 退回 `services: mysql:8.4`、只在镜像里保留 Redis——后者能保住 MySQL 的完整版本一致性，代价是恢复一次 service 镜像拉取。

`软项智训` 的后端测试当前仍用 `services:`，未改动。若也要换用本镜像，需在 GitLab 里放开跨项目拉取（Settings → CI/CD → Job token permissions 把 `软项智训` 加入 `llm-gateway` 的允许列表），否则 Job 会因无权拉取 `ci-test` 而失败。

## 5. 腾讯云初始化目录、Nginx 与权限

本章只在 `119.29.120.205` 操作。

### 5.1 安装 Nginx、Certbot 与运维工具

```bash
sudo apt-get update
sudo apt-get install -y nginx certbot jq curl util-linux
sudo systemctl enable --now nginx
```

### 5.2 创建生产目录

```bash
sudo install -d -o root -g gitlab-runner -m 750 \
  /opt/ztmdcg \
  /opt/ztmdcg/secrets

sudo install -d -o gitlab-runner -g gitlab-runner -m 750 \
  /opt/ztmdcg/platform \
  /opt/ztmdcg/platform/mysql/init \
  /opt/ztmdcg/platform/nacos-init \
  /opt/ztmdcg/apps \
  /opt/ztmdcg/apps/llm-gateway \
  /opt/ztmdcg/apps/soft-training \
  /opt/ztmdcg/nginx \
  /opt/ztmdcg/nginx/conf.d \
  /opt/ztmdcg/nginx/snippets \
  /opt/ztmdcg/scripts

sudo install -d -m 755 \
  /data/ztmdcg/mysql \
  /data/ztmdcg/redis-soft \
  /data/ztmdcg/minio \
  /data/ztmdcg/qdrant \
  /data/ztmdcg/nacos \
  /data/ztmdcg/backups \
  /var/www/certbot

sudo docker network inspect ztmdcg-net >/dev/null 2>&1 || sudo docker network create ztmdcg-net
sudo touch /var/lock/ztmdcg-deploy.lock
sudo chown gitlab-runner:gitlab-runner /var/lock/ztmdcg-deploy.lock
sudo chmod 660 /var/lock/ztmdcg-deploy.lock

sudo -u gitlab-runner test -w /opt/ztmdcg/platform
sudo -u gitlab-runner test -w /opt/ztmdcg/apps/llm-gateway
sudo -u gitlab-runner test -w /opt/ztmdcg/nginx/conf.d
sudo -u gitlab-runner test ! -w /opt/ztmdcg/secrets
echo 'directory permissions OK'
```

成功标志：最后输出 `directory permissions OK`。部署目录必须由 `gitlab-runner` 可写，密钥目录必须保持 `root:gitlab-runner` 且 Runner 不可写；否则流水线会在 `install` 阶段失败或密钥边界失效。

### 5.3 限制部署 Runner 的 sudo 权限

```bash
sudo tee /etc/sudoers.d/ztmdcg-gitlab-runner >/dev/null <<'EOF'
gitlab-runner ALL=(root) NOPASSWD: /usr/sbin/nginx -t, /bin/systemctl reload nginx
EOF
sudo chmod 440 /etc/sudoers.d/ztmdcg-gitlab-runner
sudo visudo -cf /etc/sudoers.d/ztmdcg-gitlab-runner
```

不要给 `gitlab-runner` 无限制 `NOPASSWD: ALL`。

## 6. 腾讯云创建生产密钥文件

密钥只保存在 `/opt/ztmdcg/secrets/`，不提交 Git、不放镜像、不放普通 CI 变量。

### 6.1 创建 `platform.env`

```bash
sudo -i
umask 027

MYSQL_ROOT_PASSWORD="$(openssl rand -hex 32)"
SOFT_MYSQL_PASSWORD="$(openssl rand -hex 32)"
GATEWAY_MYSQL_PASSWORD="$(openssl rand -hex 32)"
SOFT_REDIS_PASSWORD="$(openssl rand -hex 32)"
GATEWAY_REDIS_PASSWORD="$(openssl rand -hex 32)"
MINIO_ROOT_PASSWORD="$(openssl rand -hex 32)"

cat > /opt/ztmdcg/secrets/platform.env <<EOF
MYSQL_ROOT_PASSWORD=${MYSQL_ROOT_PASSWORD}
SOFT_MYSQL_DATABASE=soft_training
SOFT_MYSQL_USER=soft_training_app
SOFT_MYSQL_PASSWORD=${SOFT_MYSQL_PASSWORD}
GATEWAY_MYSQL_DATABASE=llm_gateway
GATEWAY_MYSQL_USER=llm_gateway_app
GATEWAY_MYSQL_PASSWORD=${GATEWAY_MYSQL_PASSWORD}
SOFT_REDIS_PASSWORD=${SOFT_REDIS_PASSWORD}
GATEWAY_REDIS_PASSWORD=${GATEWAY_REDIS_PASSWORD}
MINIO_ROOT_USER=ztmdcgadmin
MINIO_ROOT_PASSWORD=${MINIO_ROOT_PASSWORD}
EOF

chown root:gitlab-runner /opt/ztmdcg/secrets/platform.env
chmod 640 /opt/ztmdcg/secrets/platform.env
```

MySQL 初始化后不要直接改这些密码；初始化脚本只在空数据目录首次执行。确需轮换时，要同步修改数据库账号和应用密钥文件。

### 6.2 创建 `llm-gateway.env`

仍在 root shell：

```bash
. /opt/ztmdcg/secrets/platform.env

cat > /opt/ztmdcg/secrets/llm-gateway.env <<EOF
GATEWAY_MYSQL_DATABASE=${GATEWAY_MYSQL_DATABASE}
GATEWAY_MYSQL_USER=${GATEWAY_MYSQL_USER}
GATEWAY_MYSQL_PASSWORD=${GATEWAY_MYSQL_PASSWORD}
GATEWAY_REDIS_PASSWORD=${GATEWAY_REDIS_PASSWORD}
EOF

chown root:gitlab-runner /opt/ztmdcg/secrets/llm-gateway.env
chmod 640 /opt/ztmdcg/secrets/llm-gateway.env
exit
```

`soft-training.env` 要等 Gateway 业务 API Key 创建后再写，见第 10 章。

## 7. 腾讯云配置外挂 Nginx 与 HTTPS

### 7.1 从本地电脑复制配置源文件

在本地 PowerShell 执行：

```powershell
scp "C:\practice\llm-gateway-project\deploy\nginx\00-acme-bootstrap.conf" root@119.29.120.205:/tmp/
scp "C:\practice\llm-gateway-project\deploy\nginx\proxy-common.conf" root@119.29.120.205:/tmp/
scp "C:\practice\llm-gateway-project\deploy\nginx\gateway.ztmdcg.cn.conf" root@119.29.120.205:/tmp/
scp "C:\practice\软项智训\deploy\nginx\ztmdcg.cn.conf" root@119.29.120.205:/tmp/
```

### 7.2 启用 ACME 临时配置

回到腾讯云：

```bash
sudo install -m 640 /tmp/00-acme-bootstrap.conf /opt/ztmdcg/nginx/conf.d/00-acme-bootstrap.conf
sudo rm -f /etc/nginx/sites-enabled/default
sudo ln -sfn /opt/ztmdcg/nginx/conf.d/00-acme-bootstrap.conf /etc/nginx/conf.d/00-acme-bootstrap.conf
sudo nginx -t
sudo systemctl reload nginx
```

成功标志：`http://ztmdcg.cn` 与 `http://gateway.ztmdcg.cn` 可连接并返回 `503`，而不是超时。

### 7.3 签发两个证书

先把邮箱存进变量再引用。**不要**直接把 `--email <YOUR_EMAIL>` 原样粘进终端：bash 会把不带引号的 `<` 当成输入重定向，去找一个名为 `YOUR_EMAIL` 的文件，报 `-bash: YOUR_EMAIL: No such file or directory`，certbot 根本没启动，而报错信息完全不提示你忘了替换占位符。

邮箱用于接收 Let's Encrypt 的证书到期提醒，填一个你能正常收信的地址：

```bash
read -rp 'ACME contact email: ' ACME_EMAIL
[[ "$ACME_EMAIL" == *@*.* ]] || { echo 'invalid email' >&2; }
echo "will use: $ACME_EMAIL"
```

确认回显正确后再签发：

```bash
sudo certbot certonly --webroot -w /var/www/certbot \
  --email "$ACME_EMAIL" --agree-tos --no-eff-email \
  -d ztmdcg.cn -d www.ztmdcg.cn

sudo certbot certonly --webroot -w /var/www/certbot \
  --email "$ACME_EMAIL" --agree-tos --no-eff-email \
  -d gateway.ztmdcg.cn

sudo certbot certificates
```

成功标志：`certbot certificates` 列出两条证书，`Domains` 分别为 `ztmdcg.cn www.ztmdcg.cn` 与 `gateway.ztmdcg.cn`，`Expiry Date` 约 90 天后。若显示 `No certificates found`，说明上面的签发命令没有真正执行成功，先回看它的输出，不要往下走。

腾讯云域名已备案，HTTP-01 校验可正常完成，不会出现京东云那个 `403`。若这里仍报 `403` 或超时，检查 7.2 的 ACME 临时配置是否已 reload、`80` 端口安全组是否放通、DNS 是否解析到 `119.29.120.205`。

### 7.4 切换最终 Nginx 配置

```bash
sudo install -m 640 /tmp/proxy-common.conf /opt/ztmdcg/nginx/snippets/proxy-common.conf
sudo install -m 640 /tmp/gateway.ztmdcg.cn.conf /opt/ztmdcg/nginx/conf.d/gateway.ztmdcg.cn.conf
sudo install -m 640 /tmp/ztmdcg.cn.conf /opt/ztmdcg/nginx/conf.d/ztmdcg.cn.conf

sudo rm -f /etc/nginx/conf.d/00-acme-bootstrap.conf
sudo ln -sfn /opt/ztmdcg/nginx/conf.d/gateway.ztmdcg.cn.conf /etc/nginx/conf.d/gateway.ztmdcg.cn.conf
sudo ln -sfn /opt/ztmdcg/nginx/conf.d/ztmdcg.cn.conf /etc/nginx/conf.d/ztmdcg.cn.conf

sudo nginx -t
sudo systemctl reload nginx
sudo certbot renew --dry-run
```

此时业务容器尚未启动，访问 HTTPS 可能返回 `502`，属于正常现象；证书和 Nginx 配置检查必须成功。

生产 Nginx 的实际外挂位置：

- `/opt/ztmdcg/nginx/conf.d/ztmdcg.cn.conf`
- `/opt/ztmdcg/nginx/conf.d/gateway.ztmdcg.cn.conf`
- `/opt/ztmdcg/nginx/snippets/proxy-common.conf`

临时修改后先执行 `sudo nginx -t` 再 reload。流水线会用仓库版本覆盖服务器文件，因此长期修改必须回写仓库并 push。

## 8. 推送两个本地仓库到 GitLab

先确认需要推送的业务改动都已 review 并提交；不要把 `.env`、真实密码或 API Key 加入 Git。

### 8.1 配置 SSH Key

推送统一走 SSH。SSH 不依赖 HTTPS 证书，因此不用在本地电脑做任何 CA 配置。

本节全部在**本地电脑的 PowerShell** 执行，不在服务器上。

先确认是否已有 Key，避免覆盖掉正在用的旧钥匙：

```powershell
Test-Path "$env:USERPROFILE\.ssh\id_ed25519"
```

返回 `True` 时跳过生成，直接执行下面的 `Get-Content`。返回 `False` 时再生成：

```powershell
ssh-keygen -t ed25519 -C "linyihui@work-laptop"
Get-Content "$env:USERPROFILE\.ssh\id_ed25519.pub"
```

`-C` 只是给这把钥匙贴的注释标签，用来在 GitLab 的 Key 列表里区分是哪台机器，GitLab 不校验它，也不需要与账号邮箱一致。多台电脑时写成 `用户名@机器名` 比写邮箱更好认。

生成过程会问两个问题：保存路径直接回车用默认；`Enter passphrase` 建议设一个密码，私钥文件泄露时它是最后一道防线，图方便留空也能用。

把公钥添加到 GitLab：

```text
https://117.72.220.94/-/user_settings/ssh_keys
```

界面路径是右上角头像 → 编辑个人资料 → 左侧 SSH 密钥 → 添加新密钥（GitLab 16 之前在 `Preferences → SSH Keys`，新版访问旧地址会自动跳转）。

粘贴 `Get-Content` 输出的**整行**内容，以 `ssh-ed25519 ` 开头、以注释标签结尾。注意贴的是带 `.pub` 后缀的公钥，**不要**贴无后缀的私钥文件。

首次连接会提示确认主机指纹。**先在京东云读出真实指纹再比对**，不要盲目输 `yes`：

```bash
# 京东云执行
ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub
```

```powershell
# 本地电脑执行，比对上一步输出的指纹
ssh -T git@117.72.220.94
```

成功标志：指纹一致，且返回 `Welcome to GitLab, @<你的用户名>!`。

### 8.2 添加远程并推送

```powershell
git -C "C:\practice\llm-gateway-project" remote add gitlab git@117.72.220.94:ztmdcg/llm-gateway.git
git -C "C:\practice\软项智训" remote add gitlab git@117.72.220.94:ztmdcg/soft-training.git

git -C "C:\practice\llm-gateway-project" remote -v
git -C "C:\practice\软项智训" remote -v
```

如果已经存在名为 `gitlab` 的 remote（例如之前指向 `gitlab.ztmdcg.cn`），用 `set-url` 改掉，不要重复添加：

```powershell
git -C "C:\practice\llm-gateway-project" remote set-url gitlab git@117.72.220.94:ztmdcg/llm-gateway.git
git -C "C:\practice\软项智训" remote set-url gitlab git@117.72.220.94:ztmdcg/soft-training.git
```

将你确认好的分支推为远程 `main`：

```powershell
git -C "C:\practice\llm-gateway-project" push -u gitlab HEAD:main
git -C "C:\practice\软项智训" push -u gitlab HEAD:main
```

禁止使用 `--force` 覆盖已有生产历史。首次 push 后在 GitLab 保护 `main`。

### 8.3 推送前先在腾讯云预拉平台镜像

**这一步不能跳过。** 首次 `deploy_production` 要拉 7 个平台镜像共约 1.5GB。国内到 Docker Hub 的实测速度常低到 30-50 KB/s，`deploy_production` 未设 `timeout:`、按 GitLab 默认 60 分钟计算根本拉不完，Job 会在 `docker compose up -d --wait` 处被超时杀掉，结果是 `/opt/ztmdcg/platform/docker-compose.yml` 已写入但 `docker ps -a` 为空。

先确认第 2 章配置的镜像加速真的生效，而不只是写进了文件：

```bash
docker info | grep -A 3 "Registry Mirrors"
```

成功标志：列出 `https://docker.m.daocloud.io`。若为空，说明 `daemon.json` 未加载，回到第 2 章重新执行 `dockerd --validate` 与 `systemctl restart docker`，不要带着无效加速继续。

再预拉镜像。用 root 执行即可，镜像存在共享的 Docker daemon 里，`gitlab-runner` 用户的 CI Job 能直接命中缓存：

```bash
sudo docker compose --env-file /opt/ztmdcg/secrets/platform.env \
  -f /opt/ztmdcg/platform/docker-compose.yml pull
```

首次运行慢属正常，全程可能几十分钟，让它跑完不要中断。若 `/opt/ztmdcg/platform/docker-compose.yml` 尚不存在（还没跑过任何流水线），先手工装一份：

```bash
sudo install -d -m 750 /opt/ztmdcg/platform
sudo install -m 640 <本地仓库路径>/deploy/platform/docker-compose.yml /opt/ztmdcg/platform/docker-compose.yml
```

拉完核对：

```bash
sudo docker images --format '{{.Repository}}:{{.Tag}}' | sort
```

成功标志：`mysql:8.4`、`redis:7.4-alpine`、`minio/minio`、`qdrant/qdrant:v1.17.0`、`nacos/nacos-server:v3.1.1`、`bladex/sentinel-dashboard:1.8.8`、`curlimages/curl:8.10.1` 全部在列。

预拉之后可以顺手把平台栈先跑起来，把问题和 CI 解耦——这样第一次流水线只需要处理应用容器：

```bash
sudo docker compose --env-file /opt/ztmdcg/secrets/platform.env \
  -f /opt/ztmdcg/platform/docker-compose.yml up -d --wait
sudo docker ps --format 'table {{.Names}}\t{{.Status}}'
```

成功标志：7 个平台容器均为 `healthy`。这一步失败时报错会直接打在终端上，比翻 CI 日志好定位。

## 9. 第一次 Gateway 发布

### 9.1 观察流水线

进入 `ztmdcg/llm-gateway → Build → Pipelines`。正常顺序：

1. `backend_test`
2. `frontend_build` / `frontend_format`
3. `build_images`
4. `deploy_production`

第一次 `deploy_production` 很可能标红：平台组件已经启动，但 Gateway 因 Nacos 中 `GATEWAY_JWT_SECRET` 尚为空而健康检查失败。这是首次引导的预期行为。

腾讯云检查：

```bash
sudo docker compose --env-file /opt/ztmdcg/secrets/platform.env \
  -f /opt/ztmdcg/platform/docker-compose.yml ps
sudo docker ps --format 'table {{.Names}}\t{{.Status}}\t{{.Ports}}'
```

MySQL、两个 Redis、MinIO、Qdrant、Nacos、Sentinel 应已运行。

### 9.2 通过 SSH 隧道配置 Nacos

**前提：`deploy_production` 必须已经执行过至少一次。** 平台组件（含 Nacos）由 `deploy/scripts/deploy-production.sh` 拉起，而该脚本只在 `deploy_production` Job 里运行。若 `build_images` 失败，`deploy_production` 因 `needs: [build_images]` 根本不会执行，腾讯云上不会有任何容器。

先在腾讯云确认 Nacos 在跑，再开隧道：

```bash
sudo docker ps --format 'table {{.Names}}\t{{.Status}}\t{{.Ports}}' | grep -E 'NAMES|nacos'
sudo ss -lntp | grep 8850
```

成功标志：Nacos 容器状态含 `healthy`，且 `8850` 有 `docker-proxy` 监听。若 `docker ps` 为空，说明部署从未成功，回到第 9.1 节先让流水线跑通，不要在这里排查隧道。

在本地电脑保持以下命令运行：

```powershell
ssh -N -L 8850:127.0.0.1:8850 root@119.29.120.205
```

隧道报 `channel N: open failed: connect failed: Connection refused` 时，refused 来自**远端**：SSH 已连通，但腾讯云本机的 `127.0.0.1:8850` 没有进程监听。这是容器没起来，不是隧道或防火墙问题，按上面两条命令查。

浏览器打开 `http://127.0.0.1:8850/`，进入配置管理，编辑 `DEFAULT_GROUP` 下的 `llm-gateway.yaml`：

**注意路径是根路径，不带 `/nacos`。** Nacos 3.x 把控制台从 2.x 的 `http://IP:8848/nacos` 改到了独立端口的根路径 `http://IP:8080/`（本文映射为宿主机 `8850`）。访问 `/nacos` 会得到 Spring 的 `No static resource nacos.` 报错——看到这个说明 Nacos 其实是正常的，只是路径用了旧版写法。

```yaml
GATEWAY_JWT_SECRET: "<至少32字符随机值>"
ADMIN_USERNAME: "<管理员用户名>"
ADMIN_PASSWORD: "<管理员强密码>"
DEEPSEEK_API_KEY: "<真实Key或留空>"
OPENAI_API_KEY: "<真实Key或留空>"
ANTHROPIC_API_KEY: "<真实Key或留空>"
```

随机 JWT 可在腾讯云生成：

```bash
openssl rand -hex 32
```

发布配置后，在 GitLab 重新运行失败的 `deploy_production` Job，或在腾讯云执行：

```bash
sudo docker compose -f /opt/ztmdcg/apps/llm-gateway/docker-compose.yml restart gateway
```

流水线重跑更推荐，因为会完整执行镜像、健康检查、状态记录和 Nginx reload。

### 9.3 Gateway 验收并创建业务 API Key

```bash
curl -I https://gateway.ztmdcg.cn
sudo docker compose -f /opt/ztmdcg/apps/llm-gateway/docker-compose.yml \
  exec -T gateway curl -fsS http://127.0.0.1:9090/actuator/health
```

浏览器打开 `https://gateway.ztmdcg.cn`，用 Nacos 中管理员账号登录。在“API Key”页面创建软项智训专用 Key；`sk-gw-...` 通常只展示一次，立即保存到密码管理器。

## 10. 创建软项智训密钥并首次发布

### 10.1 创建 `soft-training.env`

在腾讯云执行：

```bash
sudo -i
umask 027
. /opt/ztmdcg/secrets/platform.env

read -rsp 'Gateway business API Key: ' LLM_GATEWAY_API_KEY; echo
read -rsp 'DashScope API Key: ' DASHSCOPE_API_KEY; echo
read -rp 'Bootstrap admin username: ' BOOTSTRAP_ADMIN_USERNAME
read -rsp 'Bootstrap admin temporary password: ' BOOTSTRAP_ADMIN_PASSWORD; echo
[[ "$BOOTSTRAP_ADMIN_USERNAME" =~ ^[A-Za-z0-9._-]{3,64}$ ]] || { echo 'invalid bootstrap admin username' >&2; exit 1; }
[[ ${#BOOTSTRAP_ADMIN_PASSWORD} -ge 16 ]] || { echo 'bootstrap admin password must be at least 16 characters' >&2; exit 1; }
JWT_SECRET="$(openssl rand -hex 32)"

cat > /opt/ztmdcg/secrets/soft-training.env <<EOF
SOFT_MYSQL_DATABASE=${SOFT_MYSQL_DATABASE}
SOFT_MYSQL_USER=${SOFT_MYSQL_USER}
SOFT_MYSQL_PASSWORD=${SOFT_MYSQL_PASSWORD}
SOFT_REDIS_PASSWORD=${SOFT_REDIS_PASSWORD}
MINIO_ROOT_USER=${MINIO_ROOT_USER}
MINIO_ROOT_PASSWORD=${MINIO_ROOT_PASSWORD}
JWT_SECRET=${JWT_SECRET}
JWT_ACCESS_EXPIRATION_SECONDS=900
JWT_REFRESH_EXPIRATION_SECONDS=604800
BOOTSTRAP_ADMIN_ENABLED=true
BOOTSTRAP_ADMIN_USERNAME=${BOOTSTRAP_ADMIN_USERNAME}
BOOTSTRAP_ADMIN_PASSWORD=${BOOTSTRAP_ADMIN_PASSWORD}
BOOTSTRAP_ADMIN_REAL_NAME=平台管理员
LLM_GATEWAY_API_KEY=${LLM_GATEWAY_API_KEY}
DASHSCOPE_API_KEY=${DASHSCOPE_API_KEY}
EOF

chown root:gitlab-runner /opt/ztmdcg/secrets/soft-training.env
chmod 640 /opt/ztmdcg/secrets/soft-training.env
stat -c '%U %G %a %n' /opt/ztmdcg/secrets/soft-training.env
unset LLM_GATEWAY_API_KEY DASHSCOPE_API_KEY BOOTSTRAP_ADMIN_PASSWORD JWT_SECRET
exit
```

成功标志：文件权限显示 `root gitlab-runner 640`。生产迁移会清除 `20240001`、`teacher`、`admin` 演示账号，首次登录必须使用这里创建的一次性管理员；该账号会被要求立即改密。

### 10.2 触发并观察发布

推送软项智训 `main`，进入 `ztmdcg/soft-training → Build → Pipelines`。正常顺序：

1. 后端测试（常驻 MySQL + Testcontainers）
2. 前端 lint/build
3. 构建 backend/frontend 镜像
4. 腾讯云自动部署

腾讯云查看：

```bash
sudo docker compose -f /opt/ztmdcg/apps/soft-training/docker-compose.yml ps
curl -fsS http://127.0.0.1:18090/actuator/health
curl -fsS http://127.0.0.1:18080/health
curl -I https://ztmdcg.cn
```

软项智训后端通过 `http://llm-gateway:8080/v1` 访问 Gateway，不使用公网 DNS，也不使用 `host.docker.internal`。

首次使用一次性管理员登录并完成强制改密后，编辑 `/opt/ztmdcg/secrets/soft-training.env`，将 `BOOTSTRAP_ADMIN_ENABLED=false`，并清空 `BOOTSTRAP_ADMIN_PASSWORD`；随后在 GitLab 重新运行当前成功 Pipeline 的 `deploy_production` Job。确认登录仍正常后，密码管理器中只保留新密码，不再保留临时密码。

## 11. 日常推送即部署

日常流程：

```powershell
git status
git add <本次确认的文件>
git commit -m "feat: ..."
git push gitlab main
```

每次发布使用 `$CI_COMMIT_SHA` 镜像标签：

1. 测试失败：不构建、不部署。
2. 镜像构建或推送失败：线上版本不变。
3. 腾讯云拉取失败：不替换容器。
4. 新容器健康检查失败：部署脚本尝试恢复上一镜像 SHA。
5. Nginx 配置检查失败：恢复旧配置，不 reload。
6. 两个项目同时推送：`/var/lock/ztmdcg-deploy.lock` 保证串行。

常用状态命令：

```bash
# 京东云
sudo gitlab-ctl status
sudo gitlab-ctl tail
sudo journalctl -u gitlab-runner -f
df -h

# 腾讯云
sudo docker compose --env-file /opt/ztmdcg/secrets/platform.env -f /opt/ztmdcg/platform/docker-compose.yml ps
sudo docker compose -f /opt/ztmdcg/apps/llm-gateway/docker-compose.yml ps
sudo docker compose -f /opt/ztmdcg/apps/soft-training/docker-compose.yml ps
sudo docker stats --no-stream
sudo docker system df
sudo nginx -t
sudo journalctl -u gitlab-runner -f
```

查看日志：

```bash
sudo docker compose -f /opt/ztmdcg/apps/llm-gateway/docker-compose.yml logs --tail=200 gateway
sudo docker compose -f /opt/ztmdcg/apps/soft-training/docker-compose.yml logs --tail=200 backend
sudo tail -n 200 /var/log/nginx/error.log
```

## 12. 回滚

### 12.1 自动回滚

每个应用目录都有 `release.env`：

- `/opt/ztmdcg/apps/llm-gateway/release.env`
- `/opt/ztmdcg/apps/soft-training/release.env`

部署成功才更新当前镜像；新版本健康检查失败时，脚本重新拉取并启动上一版本。

### 12.2 手动回滚到旧提交

在 GitLab 找到已验证成功的旧 Pipeline，重新运行该提交的 `deploy_production` Job。不要手工改成 `latest`。

若必须在腾讯云排查：

```bash
sudo cat /opt/ztmdcg/apps/llm-gateway/release.env
sudo cat /opt/ztmdcg/apps/soft-training/release.env
```

不要把 Registry 清理策略设置得少于 5 个版本。

## 13. 备份与恢复

### 13.1 手工执行业务备份

Gateway 首次部署后会把运维脚本同步到 `/opt/ztmdcg/scripts/`：

```bash
sudo /opt/ztmdcg/scripts/backup-runtime.sh
latest_backup="$(sudo find /data/ztmdcg/backups -mindepth 1 -maxdepth 1 -type d ! -name '*.partial' -printf '%f\n' | sort | tail -n 1)"
sudo sh -c "cd '/data/ztmdcg/backups/$latest_backup' && sha256sum -c --quiet SHA256SUMS"
sudo find "/data/ztmdcg/backups/$latest_backup" -maxdepth 2 -type f -printf '%p %s bytes\n'
```

备份内容：

- 两个 MySQL 数据库的逻辑备份。
- MinIO bucket 清单和逐 bucket 全量对象镜像。
- 每个 Qdrant collection 的快照；下载成功后删除 Qdrant 节点上的临时快照，避免重复占用生产磁盘。
- `SHA256SUMS` 完整性文件。

本机 60GB 磁盘只建议保留最近 7 天；至少每周把一份同步到京东云独立目录、腾讯云 COS 或其他对象存储，并保留 4 周。删除前先执行 `find ... -print` 核对目标，禁止对 `/data/ztmdcg` 根目录做递归删除。

### 13.2 配置每日 systemd timer

```bash
sudo tee /etc/systemd/system/ztmdcg-backup.service >/dev/null <<'EOF'
[Unit]
Description=Backup ztmdcg runtime data
After=docker.service
Requires=docker.service

[Service]
Type=oneshot
ExecStart=/opt/ztmdcg/scripts/backup-runtime.sh
EOF

sudo tee /etc/systemd/system/ztmdcg-backup.timer >/dev/null <<'EOF'
[Unit]
Description=Daily ztmdcg backup

[Timer]
OnCalendar=*-*-* 03:30:00
Persistent=true
RandomizedDelaySec=300

[Install]
WantedBy=timers.target
EOF

sudo systemctl daemon-reload
sudo systemctl enable --now ztmdcg-backup.timer
sudo systemctl list-timers ztmdcg-backup.timer
sudo systemctl start ztmdcg-backup.service
sudo journalctl -u ztmdcg-backup.service -n 100 --no-pager
```

### 13.3 MySQL 受控恢复演练

恢复会获取与发布脚本相同的全局锁、校验整个备份集、停止两个应用、恢复两个数据库，再等待两套应用健康。必须在维护窗口执行；先额外做一次当前状态备份，并确认没有 Pipeline 正在部署：

```bash
sudo /opt/ztmdcg/scripts/backup-runtime.sh
sudo systemctl stop gitlab-runner
if ! sudo /opt/ztmdcg/scripts/restore-mysql.sh \
    /data/ztmdcg/backups/YYYY-MM-DD_HHMMSS/mysql/databases.sql \
    --confirm; then
  sudo systemctl start gitlab-runner
  echo 'restore failed; applications may still be stopped or unhealthy' >&2
  exit 1
fi
sudo systemctl start gitlab-runner

sudo docker compose -f /opt/ztmdcg/apps/llm-gateway/docker-compose.yml ps
sudo docker compose -f /opt/ztmdcg/apps/soft-training/docker-compose.yml ps
```

成功标志：脚本输出 `MySQL restore complete and both application stacks are healthy.`，两个 Compose 中所有业务容器为 `running/healthy`，登录和关键数据抽查通过。脚本失败时，一个或两个应用可能保持停止或处于不健康状态；不要先强行启动，先检查 MySQL 导入错误、`SHA256SUMS`、镜像版本与 Compose 日志。若 `gitlab-runner` 因中途失败仍为停止状态，故障处理结束后显式执行 `sudo systemctl start gitlab-runner`。

`restore-mysql.sh` 只恢复 MySQL。MinIO 与 Qdrant 备份用于灾难重建：先校验同一备份目录的 `SHA256SUMS`，再按 MinIO `mc mirror` 和 Qdrant snapshot upload/recover 流程恢复；不要把未经演练的对象/向量恢复直接用于生产事故。

### 13.4 GitLab 自身备份

京东云执行：

```bash
sudo gitlab-backup create
sudo gitlab-ctl backup-etc
sudo ls -lh /var/opt/gitlab/backups /etc/gitlab/config_backup
```

GitLab 备份与业务备份分开保存。

## 14. 完整验收清单

### 14.1 域名与 HTTPS

对外域名（腾讯云，已备案）：

```bash
curl -I http://ztmdcg.cn
curl -I https://ztmdcg.cn
curl -I https://gateway.ztmdcg.cn
```

- HTTP 返回 301 到 HTTPS。
- 两个站点证书由 Let's Encrypt 签发且可信。

内部设施（京东云，IP + 私有 CA）。在**腾讯云**执行，验证系统信任库生效：

```bash
curl -sS -o /dev/null -w 'gitlab: %{http_code}\n' https://117.72.220.94/users/sign_in
curl -sS -o /dev/null -w 'registry: %{http_code}\n' https://117.72.220.94:5050/v2/
```

- GitLab 返回 `200` 或 `302`，Registry `/v2/` 返回 `401`。
- 两条命令都没有 `--cacert`、没有 `-k`，也没有证书报错。
- 证书颁发者确认为自建 CA：

```bash
echo | openssl s_client -connect 117.72.220.94:5050 2>/dev/null \
  | openssl x509 -noout -issuer -subject -dates -ext subjectAltName
```

成功标志：`issuer` 含 `CN = ztmdcg internal CA`，SAN 含 `IP Address:117.72.220.94`，`notAfter` 未过期。

### 14.2 Gateway API

将 `<GATEWAY_BUSINESS_API_KEY>` 换成管理台创建的 Key：

```bash
curl -sS https://gateway.ztmdcg.cn/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer <GATEWAY_BUSINESS_API_KEY>' \
  -d '{"model":"default","messages":[{"role":"user","content":"你好"}],"stream":false}'

curl -N https://gateway.ztmdcg.cn/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer <GATEWAY_BUSINESS_API_KEY>' \
  -d '{"model":"default","messages":[{"role":"user","content":"请分段回答"}],"stream":true}'
```

### 14.3 软项智训

先在浏览器使用一次性管理员登录，按提示修改密码，再用修改后的密码执行以下本地 PowerShell 验收。`Get-Credential` 避免把密码写进命令历史：

```powershell
$credential = Get-Credential -UserName '<BOOTSTRAP_ADMIN_USERNAME>'
$loginBody = @{
  username = $credential.UserName
  password = $credential.GetNetworkCredential().Password
} | ConvertTo-Json
$login = Invoke-RestMethod -Method Post -Uri 'https://ztmdcg.cn/api/auth/login' `
  -ContentType 'application/json' -Body $loginBody
$token = $login.data.accessToken
$headers = @{ Authorization = "Bearer $token" }

Invoke-RestMethod -Headers $headers -Uri 'https://ztmdcg.cn/api/ai/status'

$uploadFile = Join-Path $env:TEMP 'ztmdcg-upload-acceptance.bin'
$stream = [IO.File]::Open($uploadFile, [IO.FileMode]::Create, [IO.FileAccess]::Write)
$stream.SetLength(2MB)
$stream.Dispose()
curl.exe --fail-with-body -sS `
  -H "Authorization: Bearer $token" `
  -F "file=@$uploadFile" `
  -F 'bizType=ACCEPTANCE' `
  https://ztmdcg.cn/api/files/upload
Remove-Item -LiteralPath $uploadFile

$gradeBody = @{
  taskRequirement = '识别需求变更影响并给出处置步骤'
  rubric = '问题识别、影响分析、决策合理性、表达'
  studentAnswer = '记录变更请求，分析范围、进度、成本和风险，再提交审批并更新基线。'
} | ConvertTo-Json
$grade = Invoke-RestMethod -Method Post -Headers $headers `
  -Uri 'https://ztmdcg.cn/api/ai/grading/evaluate' `
  -ContentType 'application/json' -Body $gradeBody
$grade.data
```

成功标志：

- 登录响应包含 `data.accessToken`，浏览器刷新任意 SPA 路由不返回 404。
- `/api/ai/status` 中 Gateway、DashScope 与 Qdrant 状态均为 `READY`。
- 2MB 文件上传返回 `code=0`、`data.size=2097152`，证明 Nginx 55MB 限制、后端与 MinIO 链路正常。
- AI 辅助评分返回 `status=AI_PENDING_REVIEW` 且 `teacherReviewRequired=true`，证明软项智训经 Docker 别名 `llm-gateway` 调用了 Gateway；若要同时验收 Embedding/Qdrant/Rerank，再在“AI 助教”中为真实课程入库一份知识并完成带来源问答。

### 14.4 CI/CD 与失败演练

- 修改前端可见文案，push `main` 后页面自动替换。
- 在临时分支制造一次编译失败，确认不会生成生产镜像。
- 在维护窗口用一个必定失败的健康检查提交做回滚演练，确认线上恢复上一 SHA 后立即 revert。
- 两个仓库同时 push，确认部署 Runner 日志显示串行等待锁。
- `certbot renew --dry-run`、业务备份和 MySQL 恢复各成功一次。
- `df -h`、`docker system df`、`docker stats --no-stream` 没有异常增长或 OOM。

## 15. 故障排查表

| 现象 | 优先检查 | 处理 |
|---|---|---|
| 证书签发返回 `403` | 是否还在对京东云跑 certbot / Let's Encrypt | 未备案实例拿不到证书，改用第 3 章 IP + 私有 CA；确认 `letsencrypt['enable'] = false` |
| 装包时 `reconfigure` 失败、`dpkg` 报 `gitlab-ce` 配置错误 | `gitlab.rb` 里的 `external_url` 是否仍为域名 | 按 3.4 补救：不用重装，改配置后 `reconfigure` 再 `dpkg --configure -a` |
| 改了 `EXTERNAL_URL=` 却没生效 | `/etc/gitlab/gitlab.rb` 是否已存在 | 该变量只在文件不存在时生效；直接编辑 `gitlab.rb` 里的 `external_url` |
| `problem with public attributes` | 是否刚改过主机名、`/opt/gitlab/embedded/nodes/` 里的文件名 | 改配置后跑 `gitlab-ctl reconfigure` 会按新主机名重建；不要先跑 reconfigure 再改配置 |
| `x509: certificate signed by unknown authority` | 该客户端是否装了 CA | 腾讯云补 3.7，Runner 补 `--tls-ca-file`，dind 见 4.2.1；不要用 `-k` 绕过 |
| `doesn't contain any IP SANs` | 叶证书 SAN | 证书只写了 CN，按 3.1 用 `gitlab-ip.ext` 重签 |
| `certificate has expired` | 叶证书 825 天有效期 | 按 3.6 重签叶证书并重启 nginx/registry，CA 不动 |
| 京东云 GitLab 打不开 | 安全组 `443`、来源 IP 是否变化 | 家宽换 IP 后回控制台更新来源；不要放开 `0.0.0.0/0` |
| `docker push` 连不上 Registry | 本机能否访问自身公网 IP | 按 3.5 的 `curl` 判断，必要时加 NAT 折回规则 |
| 腾讯云连 `5050` 超时、连 `443` 正常 | 京东云安全组是否只加了 443 | `5050` 要单独加一条来源 `119.29.120.205/32` 的规则 |
| `Connection timed out` | 安全组/防火墙丢包 | 与 `Connection refused` 区分：refused 才是服务没起来，见 3.7 的对照表 |
| `dpkg was interrupted` / `Unit file gitlab-runner.service does not exist` | 京东云 `gitlab-ce` 是否仍是半配置 | 先 `sudo dpkg --configure -a`，`dpkg -l gitlab-ce` 显示 `ii` 后再装其他包 |
| `packages.gitlab.com` 下载极慢 | 是否仍在用官方源 | 换清华镜像，见 4.1；GPG key 仍从官方取，只有 3KB |
| `blob unknown to registry`（所有层已 Pushed） | 是否用 BuildKit 构建 | BuildKit 的 index 引用问题；`rmi` 后用 `DOCKER_BUILDKIT=0` 重建，见 4.4。同时查 `df -h /var/opt/gitlab` |
| 域名超时 | DNS、安全组、`ss -lntp` | 先修网络，不要反复重装服务 |
| GitLab 502 | `gitlab-ctl status`、内存、swap | `gitlab-ctl tail`，确认 Puma/Sidekiq 未 OOM |
| Registry 登录失败 | `/v2/`、证书、项目 Registry 开关 | 确认 `117.72.220.94:5050` HTTPS 与清理策略 |
| Runner pending | Runner 在线状态、tag、Protected | 构建 Job 应由 untagged Runner 接，部署 Job 要 `ztmdcg-production` |
| Docker 构建失败 | DIND、镜像源、磁盘 | 查 Job 日志和 `docker system df` |
| 部署提示密钥文件缺失 | `/opt/ztmdcg/secrets/*.env` | 按提示补文件并设置 `640 root:gitlab-runner` |
| Gateway 反复重启 | Nacos JWT/管理员/供应商 Key | SSH 隧道进入 Nacos，发布配置后重跑部署 |
| 软项智训首次部署后无账号 | `BOOTSTRAP_ADMIN_*` 是否已传入生产容器 | 按第 10 章补一次性管理员，重跑部署；首次改密后关闭 bootstrap |
| 软项智训 AI 失败 | Gateway API Key、DashScope Key、Docker 网络 | 容器内确认 `llm-gateway:8080` 可解析，不走公网 |
| Nginx 502 | `nginx -t`、回环端口、容器健康 | 查对应 Compose `ps/logs` |
| SSE 一次性返回或中断 | Nginx 缓冲、330 秒超时 | 确认 Gateway 配置含 `proxy_buffering off` |
| 磁盘快速增长 | 镜像、GitLab artifacts、日志、备份 | 清理已确认无用资源，调整 Registry/Artifact 保留策略 |
| MySQL 初始化账号不匹配 | `platform.env` 是否在首次启动后被改 | 在数据库中显式轮换账号，不要只改 env |

## 16. 禁止操作与安全规则

生产环境禁止：

```bash
docker compose down -v
```

`-v` 会删除数据库、Nacos、对象存储或日志卷。正常停机只使用：

```bash
docker compose stop
# 或确有需要时使用不带 -v 的 docker compose down
```

同时遵守：

- 不把真实 `.env`、令牌、密码、私钥提交到 Git。
- 不把 CA 私钥 `/etc/gitlab/ssl/ca-private/ztmdcg-ca.key` 复制出京东云；它能签发任意站点的可信证书。
- 不在腾讯云 `daemon.json` 里配置 `insecure-registries`；跨机拉镜像必须完整校验证书。
- 不用 `curl -k`、`git config http.sslVerify false` 或 `docker --tls-verify=false` 绕过证书问题，应补齐 CA。
- 不把京东云 `443`、`5050` 放开到 `0.0.0.0/0`。
- 不在备案通过前重试对 `gitlab.ztmdcg.cn` 的证书签发。
- 不把 MySQL、Redis、MinIO、Qdrant、Nacos、Sentinel 直接暴露公网。
- 不在部署服务器编译源码；服务器只拉 Registry 镜像。
- 不直接编辑服务器 Compose 后忘记回写仓库。
- 不使用 `latest` 作为生产回滚依据。
- 不对 `/data/ztmdcg`、`/opt/ztmdcg` 或系统根目录执行未经核对的递归删除。

## 17. 首次部署顺序摘要

1. 重装两台 Ubuntu 22.04。
2. 配置 DNS（只保留 `@`、`www`、`gateway` 三条，删除 `gitlab`/`registry`）、安全组、时区、4GB swap 和 Docker。
3. 京东云生成私有 CA 与含 `IP:117.72.220.94` 的证书，安装 GitLab（`letsencrypt['enable'] = false`）、`:5050` Registry。
4. 把 CA 分发到腾讯云系统信任库与 `/etc/docker/certs.d/117.72.220.94:5050/`，开发人员按需导入浏览器。
5. 注册两个 Runner，都带 `--tls-ca-file`；构建 Job 的 dind 使用 `--insecure-registry=117.72.220.94:5050`。
6. 腾讯云安装 Nginx、Certbot，创建目录和密钥。
7. 腾讯云为 `ztmdcg.cn`、`gateway.ztmdcg.cn` 签发 Let's Encrypt 证书并启用外挂 Nginx 配置。
8. 用 SSH 远程 `git@117.72.220.94` 推送 `llm-gateway`，允许第一次部署在平台启动后因 Nacos 空密钥失败。
9. 通过 SSH 隧道填写 Nacos，重跑 Gateway 部署。
10. 在 Gateway 管理台创建软项智训业务 API Key。
11. 创建含一次性管理员的 `soft-training.env`，推送软项智训；首次改密后关闭 bootstrap 并重跑部署。
12. 完成域名、证书、登录、非流式/SSE、上传、AI、备份、恢复和回滚验收。

## 18. 官方参考

- GitLab Linux package 安装：<https://docs.gitlab.com/install/package/ubuntu/>
- GitLab 安装资源要求：<https://docs.gitlab.com/install/requirements/>
- GitLab Runner 安装与注册：<https://docs.gitlab.com/runner/install/linux-repository/>、<https://docs.gitlab.com/runner/register/>
- Docker Engine Ubuntu 安装：<https://docs.docker.com/engine/install/ubuntu/>
- Certbot 使用说明：<https://eff-certbot.readthedocs.io/en/stable/using.html>
- MinIO `mc mirror`：<https://docs.min.io/community/minio-object-store/reference/minio-mc/mc-mirror.html>
- Qdrant snapshots：<https://qdrant.tech/documentation/operations/snapshots/>

软件包仓库与 Runner 注册界面可能随版本变化；若页面菜单或参数不同，以安装当日官方文档为准，但本文的双机职责、端口边界、密钥位置和发布流程不变。
