# 双服务器 GitLab CI/CD + Docker Compose 部署操作手册

- 版本：2026-08-08
- 适用系统：Ubuntu Server 22.04 LTS 64 位
- 适用仓库：`llm-gateway-project`、`软项智训`
- 目标：推送 `main` 后自动测试、构建镜像、推送 Registry、替换腾讯云容器；失败时不影响现有版本或自动回滚

本文按首次重装顺序编写。除明确标注“本地电脑”外，命令均在对应云服务器执行。所有 `<...>` 都必须替换，不要把尖括号原样粘贴。

## 阅读与执行规则

重装会清空系统盘；开始前先确认旧服务器没有仍需保留的数据，或已经完成快照/异机备份。本文不提供任何可直接使用的密码、Token 或 API Key，所有占位符都要替换为现场生成的值。

下表适用于对应阶段里的**每一条服务器变更命令**：一条命令返回非零、检查结果不符合“成功标志”，或输出中出现 `failed`/`error` 时，立即停止当前阶段，执行“失败动作”，不要继续粘贴后续命令，也不要靠反复重跑掩盖原因。

| 阶段 | 成功标志 | 失败动作 |
|---|---|---|
| 重装、时区、swap、基础包 | 系统版本/时间/swap 与本文一致 | 保留完整错误输出；先修复云控制台、APT、磁盘或 DNS，再继续 |
| Docker、GitLab、Runner | 对应 `version`、`status`、`verify` 命令退出码为 0 | 查看 `systemctl status`、`journalctl` 或 `gitlab-ctl tail`，服务未健康前不进入下一章 |
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
  ├─ push llm-gateway/main
  └─ push soft-training/main
           │
           ▼
京东云 117.72.220.94
  ├─ gitlab.ztmdcg.cn
  ├─ registry.ztmdcg.cn
  └─ Docker Runner（并发 1）
           │ HTTPS 拉取 SHA 镜像
           ▼
腾讯云 119.29.120.205
  ├─ Nginx :80/:443
  ├─ MySQL + 两套 Redis + MinIO + Qdrant + Nacos + Sentinel
  ├─ Gateway backend/UI
  └─ 软项智训 backend/frontend
```

### 0.2 公网与本机端口

公网只开放 SSH、HTTP 和 HTTPS。`80` 端口用于证书校验并将普通流量跳转到 `443`；对外 API 使用 HTTPS。

| 机器 | 监听 | 用途 | 安全组 |
|---|---:|---|---|
| 京东云 | `22` | SSH / Git over SSH | 仅管理人员公网 IP |
| 京东云 | `80`、`443` | GitLab、Registry、证书 | 公网 |
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

在腾讯云 DNSPod 为 `ztmdcg.cn` 添加：

| 主机记录 | 类型 | 记录值 |
|---|---|---|
| `@` | A | `119.29.120.205` |
| `www` | CNAME | `ztmdcg.cn` |
| `gateway` | A | `119.29.120.205` |
| `gitlab` | A | `117.72.220.94` |
| `registry` | A | `117.72.220.94` |

在本地电脑验证；任务要求使用系统自带的 `nslookup`，不要只看浏览器缓存：

```powershell
nslookup ztmdcg.cn
nslookup gateway.ztmdcg.cn
nslookup gitlab.ztmdcg.cn
nslookup registry.ztmdcg.cn
```

成功标志：四个域名分别解析到上表 IP。证书签发前必须先完成解析。

### 1.2 京东云安全组

入站规则：

- TCP `22`：仅你的固定公网 IP；多人协作时逐个添加来源。
- TCP `80`：`0.0.0.0/0`、`::/0`。
- TCP `443`：`0.0.0.0/0`、`::/0`。

不要开放 GitLab 内置 PostgreSQL、Redis、Gitaly 等内部端口。

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

## 3. 京东云安装 GitLab CE 与 HTTPS Registry

本章只在 `117.72.220.94` 操作。

### 3.1 设置主机名并安装 GitLab

```bash
sudo hostnamectl set-hostname gitlab.ztmdcg.cn
sudo apt-get update
sudo apt-get install -y curl openssh-server ca-certificates tzdata perl postfix
curl -fsSL https://packages.gitlab.com/install/repositories/gitlab/gitlab-ce/script.deb.sh | sudo bash
sudo EXTERNAL_URL="https://gitlab.ztmdcg.cn" apt-get install -y gitlab-ce
```

若安装阶段证书尚未生成，先确认 DNS 与 80/443 安全组，再执行下一节统一配置。

### 3.2 配置 `/etc/gitlab/gitlab.rb`

先备份：

```bash
sudo cp /etc/gitlab/gitlab.rb /etc/gitlab/gitlab.rb.before-ztmdcg
sudoedit /etc/gitlab/gitlab.rb
```

确认或追加以下配置，把邮箱替换为真实地址：

```ruby
external_url 'https://gitlab.ztmdcg.cn'
registry_external_url 'https://registry.ztmdcg.cn'

gitlab_rails['gitlab_signup_enabled'] = false
gitlab_rails['backup_keep_time'] = 604800

nginx['redirect_http_to_https'] = true
registry_nginx['redirect_http_to_https'] = true

letsencrypt['enable'] = true
letsencrypt['contact_emails'] = ['<YOUR_EMAIL>']
letsencrypt['auto_renew'] = true
letsencrypt['alt_names'] = ['registry.ztmdcg.cn']

# 4C8G 节省内存
puma['worker_processes'] = 0
sidekiq['concurrency'] = 5
prometheus_monitoring['enable'] = false
```

应用配置：

```bash
sudo gitlab-ctl reconfigure
sudo gitlab-ctl status
sudo gitlab-rake gitlab:check SANITIZE=true
curl -I https://gitlab.ztmdcg.cn/users/sign_in
curl -I https://registry.ztmdcg.cn/v2/
```

Registry 返回 `401 Unauthorized` 是正常成功标志，表示 HTTPS 可达但需要登录。

证书异常时先检查：

```bash
sudo gitlab-ctl renew-le-certs
sudo gitlab-ctl tail nginx
sudo ls -l /etc/gitlab/ssl
```

### 3.3 首次登录与安全设置

```bash
sudo cat /etc/gitlab/initial_root_password
```

浏览器打开 `https://gitlab.ztmdcg.cn`，使用 `root` 登录并立即：

1. 修改 root 密码。
2. 添加管理员 SSH Key。
3. 条件允许时启用双因素认证。
4. 确认注册入口已关闭。

初始密码文件约 24 小时后自动删除。

### 3.4 创建 Group 与两个私有项目

在 GitLab 页面创建私有 Group：`ztmdcg`，再创建两个空项目：

- `ztmdcg/llm-gateway`
- `ztmdcg/soft-training`

创建时不要初始化 README，避免第一次 push 产生无关历史。首次 push 完成后，将 `main` 设置为默认分支并保护：允许 Maintainer push/merge，禁止普通 Developer 直接推生产分支。

两个项目都进入：`Settings → Packages and registries → Container Registry`，启用清理策略，至少保留最近 5 个镜像标签；镜像标签是提交 SHA，不依赖 `latest`。

## 4. 安装与注册 GitLab Runner

### 4.1 两台机器安装 Runner 软件包

两台都执行：

```bash
curl -fsSL https://packages.gitlab.com/install/repositories/runner/gitlab-runner/script.deb.sh | sudo bash
sudo apt-get install -y gitlab-runner
sudo systemctl enable --now gitlab-runner
gitlab-runner --version
```

### 4.2 京东云注册构建 Runner

在 GitLab Group `ztmdcg → Settings → CI/CD → Runners` 创建 Group Runner：

- Description：`jd-build-runner`
- Run untagged jobs：开启
- Protected：关闭，使普通分支也能运行测试
- 最大超时：建议 60 分钟

复制一次性显示的 `glrt-...` 认证令牌，在京东云执行：

```bash
sudo gitlab-runner register --non-interactive \
  --url "https://gitlab.ztmdcg.cn" \
  --token "<JD_BUILD_RUNNER_TOKEN>" \
  --executor "docker" \
  --docker-image "alpine:3.21" \
  --description "jd-build-runner"
```

编辑 `/etc/gitlab-runner/config.toml`：

```toml
concurrent = 1

[[runners]]
  request_concurrency = 1
  [runners.docker]
    privileged = true
    volumes = ["/cache"]
```

不要删除注册命令生成的 `url`、`token` 等其他字段。然后：

```bash
sudo gitlab-runner verify
sudo systemctl restart gitlab-runner
sudo journalctl -u gitlab-runner -n 100 --no-pager
```

### 4.3 腾讯云注册部署 Runner

在同一 Group 新建另一个 Runner：

- Description：`tencent-production-runner`
- Tag：`ztmdcg-production`
- Run untagged jobs：关闭
- Protected：开启
- Scope：只属于 `ztmdcg` Group；若界面显示 “Lock to current projects”，将其锁定到 `llm-gateway` 与 `soft-training`，不要注册成全实例共享 Runner

在腾讯云执行：

```bash
sudo gitlab-runner register --non-interactive \
  --url "https://gitlab.ztmdcg.cn" \
  --token "<TENCENT_DEPLOY_RUNNER_TOKEN>" \
  --executor "shell" \
  --description "tencent-production-runner"

sudo usermod -aG docker gitlab-runner
sudo systemctl restart gitlab-runner
sudo -u gitlab-runner -H docker version
sudo -u gitlab-runner -H docker compose version
```

确认 `/etc/gitlab-runner/config.toml` 顶层为 `concurrent = 1`，然后执行：

```bash
sudo gitlab-runner verify
sudo journalctl -u gitlab-runner -n 100 --no-pager
```

成功标志：GitLab Group 页面中两个 Runner 均为绿色在线，部署 Runner 只接收带 `ztmdcg-production` 标签且来自受保护分支的 Job。部署脚本的 `/var/lock/ztmdcg-deploy.lock` 负责两个项目之间的互斥，CI 中的 `resource_group` 负责项目内串行。

> `gitlab-runner` 加入 `docker` 组后等同拥有主机 root 级能力，因此腾讯云必须作为专用部署机，Runner 只能绑定受控的私有项目。

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

```bash
sudo certbot certonly --webroot -w /var/www/certbot \
  --email <YOUR_EMAIL> --agree-tos --no-eff-email \
  -d ztmdcg.cn -d www.ztmdcg.cn

sudo certbot certonly --webroot -w /var/www/certbot \
  --email <YOUR_EMAIL> --agree-tos --no-eff-email \
  -d gateway.ztmdcg.cn

sudo certbot certificates
```

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

本地没有 SSH Key 时：

```powershell
ssh-keygen -t ed25519 -C "<YOUR_EMAIL>"
Get-Content $env:USERPROFILE\.ssh\id_ed25519.pub
```

把公钥添加到 GitLab：`Preferences → SSH Keys`。

### 8.2 添加远程并推送

```powershell
git -C "C:\practice\llm-gateway-project" remote add gitlab git@gitlab.ztmdcg.cn:ztmdcg/llm-gateway.git
git -C "C:\practice\软项智训" remote add gitlab git@gitlab.ztmdcg.cn:ztmdcg/soft-training.git

git -C "C:\practice\llm-gateway-project" remote -v
git -C "C:\practice\软项智训" remote -v
```

如果已经存在名为 `gitlab` 的 remote，使用 `git remote set-url`，不要重复添加。

将你确认好的分支推为远程 `main`：

```powershell
git -C "C:\practice\llm-gateway-project" push -u gitlab HEAD:main
git -C "C:\practice\软项智训" push -u gitlab HEAD:main
```

禁止使用 `--force` 覆盖已有生产历史。首次 push 后在 GitLab 保护 `main`。

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

在本地电脑保持以下命令运行：

```powershell
ssh -N -L 8850:127.0.0.1:8850 root@119.29.120.205
```

浏览器打开 `http://127.0.0.1:8850/nacos/`，进入配置管理，编辑 `DEFAULT_GROUP` 下的 `llm-gateway.yaml`：

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

```bash
curl -I http://ztmdcg.cn
curl -I https://ztmdcg.cn
curl -I https://gateway.ztmdcg.cn
curl -I https://gitlab.ztmdcg.cn
curl -I https://registry.ztmdcg.cn/v2/
```

- HTTP 返回 301 到 HTTPS。
- 三个页面证书可信。
- Registry `/v2/` 返回 401，而非超时或证书错误。

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
| 域名超时 | DNS、安全组、`ss -lntp` | 先修网络，不要反复重装服务 |
| GitLab 502 | `gitlab-ctl status`、内存、swap | `gitlab-ctl tail`，确认 Puma/Sidekiq 未 OOM |
| Registry 登录失败 | `/v2/`、证书、项目 Registry 开关 | 确认 `registry.ztmdcg.cn` HTTPS 与清理策略 |
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
- 不把 MySQL、Redis、MinIO、Qdrant、Nacos、Sentinel 直接暴露公网。
- 不在部署服务器编译源码；服务器只拉 Registry 镜像。
- 不直接编辑服务器 Compose 后忘记回写仓库。
- 不使用 `latest` 作为生产回滚依据。
- 不对 `/data/ztmdcg`、`/opt/ztmdcg` 或系统根目录执行未经核对的递归删除。

## 17. 首次部署顺序摘要

1. 重装两台 Ubuntu 22.04。
2. 配置 DNS、安全组、时区、4GB swap 和 Docker。
3. 京东云安装 GitLab、HTTPS Registry、构建 Runner。
4. 腾讯云安装 Nginx、Certbot、部署 Runner，创建目录和密钥。
5. 腾讯云签发证书并启用外挂 Nginx 配置。
6. 推送 `llm-gateway`，允许第一次部署在平台启动后因 Nacos 空密钥失败。
7. 通过 SSH 隧道填写 Nacos，重跑 Gateway 部署。
8. 在 Gateway 管理台创建软项智训业务 API Key。
9. 创建含一次性管理员的 `soft-training.env`，推送软项智训；首次改密后关闭 bootstrap 并重跑部署。
10. 完成域名、登录、非流式/SSE、上传、AI、备份、恢复和回滚验收。

## 18. 官方参考

- GitLab Linux package 安装：<https://docs.gitlab.com/install/package/ubuntu/>
- GitLab 安装资源要求：<https://docs.gitlab.com/install/requirements/>
- GitLab Runner 安装与注册：<https://docs.gitlab.com/runner/install/linux-repository/>、<https://docs.gitlab.com/runner/register/>
- Docker Engine Ubuntu 安装：<https://docs.docker.com/engine/install/ubuntu/>
- Certbot 使用说明：<https://eff-certbot.readthedocs.io/en/stable/using.html>
- MinIO `mc mirror`：<https://docs.min.io/community/minio-object-store/reference/minio-mc/mc-mirror.html>
- Qdrant snapshots：<https://qdrant.tech/documentation/operations/snapshots/>

软件包仓库与 Runner 注册界面可能随版本变化；若页面菜单或参数不同，以安装当日官方文档为准，但本文的双机职责、端口边界、密钥位置和发布流程不变。
