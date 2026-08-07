# 双机 GitLab CI/CD 与 Docker 自动部署设计

- 日期：2026-08-07
- 状态：用户已确认
- 适用项目：`C:\practice\软项智训`、`C:\practice\llm-gateway-project`

## 1. 目标

在两台 Ubuntu 22.04 云服务器上建立一套可操作、可回滚的双机交付链路：两个项目分别推送到两个 GitLab 私有仓库，推送 `main` 后自动完成测试、构建 Docker 镜像、推送镜像仓库和腾讯云生产部署。公网只暴露 Nginx 的 80/443 端口，配置文件在宿主机外挂，证书自动续期。

## 2. 已确认的约束

| 事项 | 决定 |
|---|---|
| 服务器 | 京东云 `117.72.220.94`（4C/8G/180GB）作为 GitLab 机；腾讯云 `119.29.120.205`（4C/8G/60GB）作为业务机 |
| 旧环境 | 腾讯云重装后不保留旧 GitLab、流水线、容器或业务数据 |
| 仓库 | 两个独立仓库：`soft-training`、`llm-gateway` |
| 域名 | `ztmdcg.cn` 已完成 ICP 备案，DNS 在腾讯云 DNSPod 管理 |
| 公网入口 | `ztmdcg.cn`、`gateway.ztmdcg.cn`、`gitlab.ztmdcg.cn`、`registry.ztmdcg.cn` |
| 协议 | HTTPS；80 端口保留并跳转 443 |
| 发布 | `main` 推送后自动部署；其他分支不部署 |
| 可用性 | 允许发布时短暂停机，不做蓝绿/高可用 |
| 数据库 | 腾讯云一个 MySQL 实例，分别创建两个数据库和账号 |
| 缓存 | 腾讯云两个独立 Redis 实例，分别服务两个项目 |

## 3. 总体架构

```text
开发电脑
  ├── push soft-training/main
  └── push llm-gateway/main
          │ HTTPS
          ▼
京东云 117.72.220.94
  ├── GitLab CE Omnibus
  │    └── 内置 PostgreSQL / Redis / Gitaly
  ├── GitLab Container Registry
  └── GitLab Runner（Docker executor，构建并发 1）
          │ HTTPS 拉取镜像
          ▼
腾讯云 119.29.120.205
  ├── Nginx + Certbot（宿主机）
  ├── GitLab Runner（Shell executor）
  ├── platform Compose
  │    ├── MySQL（soft_training、llm_gateway）
  │    ├── Redis-soft
  │    ├── Redis-gateway
  │    ├── MinIO
  │    ├── Qdrant
  │    ├── Nacos
  │    └── Sentinel Dashboard
  ├── soft-training Compose
  │    ├── backend
  │    └── frontend
  └── llm-gateway Compose
       ├── gateway
       └── ui
```

所有业务 Compose 加入外部 Docker 网络 `ztmdcg-net`。软项智训后端通过 `http://llm-gateway:8080/v1` 访问网关，不经公网 DNS 和 Nginx。

## 4. 服务器职责

### 4.1 京东云 GitLab 机

只运行 GitLab 控制面、Registry 和构建 Runner。采用 GitLab Linux package（Omnibus），不再叠加业务 Compose。配置重点：

- `external_url https://gitlab.ztmdcg.cn`
- `registry_external_url https://registry.ztmdcg.cn`
- Omnibus Let’s Encrypt 为两个域名签发证书
- Puma 单进程、Sidekiq 低并发
- 关闭 GitLab 内置 Prometheus 监控
- GitLab Runner `concurrent = 1`
- Registry 项目清理策略保留最近 5 个 SHA 镜像
- 配置 4GB swap，持续监控磁盘和内存

GitLab 官方当前以 16GB/8 vCPU 作为单机基线，但允许受限环境至少 8GB；该机因此不能承担生产业务容器。[官方要求](https://docs.gitlab.com/install/requirements/)

### 4.2 腾讯云业务机

只运行生产业务和部署 Runner。宿主机 Nginx 监听 80/443，业务容器的宿主端口只绑定 `127.0.0.1`：

| 宿主机端口 | 容器 | 用途 |
|---:|---|---|
| `127.0.0.1:18080` | soft-training frontend | 软项智训页面 |
| `127.0.0.1:18090` | soft-training backend | 软项智训 API |
| `127.0.0.1:18081` | gateway ui | Gateway 管理台静态页面 |
| `127.0.0.1:18091` | gateway | Gateway `/admin`、`/v1` |

MySQL、Redis、Nacos、Sentinel、MinIO 和 Qdrant 不映射公网端口；管理控制台只通过 SSH 隧道访问。

## 5. DNS 与 HTTPS

DNSPod 中配置：

| 主机记录 | 类型 | 目标 |
|---|---|---|
| `@` | A | `119.29.120.205` |
| `gateway` | A | `119.29.120.205` |
| `gitlab` | A | `117.72.220.94` |
| `registry` | A | `117.72.220.94` |

腾讯云业务机使用 Certbot 管理：

- `ztmdcg.cn`
- `www.ztmdcg.cn`
- `gateway.ztmdcg.cn`

京东云 GitLab Omnibus 使用内置 Let’s Encrypt 管理：

- `gitlab.ztmdcg.cn`
- `registry.ztmdcg.cn`

80 端口允许 ACME HTTP-01 验证，并将普通请求 301 到 HTTPS。Registry 使用标准 443，不使用明文 HTTP Registry。

## 6. 运行时组件与资源预算

腾讯云初始容器内存上限：

| 服务 | 上限 |
|---|---:|
| soft-training backend | 768MB |
| soft-training frontend | 128MB |
| llm-gateway gateway | 768MB |
| llm-gateway ui | 128MB |
| MySQL | 1.25GB |
| Redis-soft | 256MB |
| Redis-gateway | 256MB |
| MinIO | 384MB |
| Qdrant | 512MB |
| Nacos | 512MB |
| Sentinel Dashboard | 256MB |

预计容器限制合计约 5.2GB，剩余内存供 Ubuntu、Docker、Nginx 和 Runner 使用。两台机器都配置 swap，但 swap 只用于突发峰值，不能替代内存扩容。

MySQL 初始化两个数据库和最小权限账号：

- `soft_training` / `soft_training_app`
- `llm_gateway` / `llm_gateway_app`

应用连接池在生产环境下调低：软项智训后端最大连接 10，Gateway 最大连接 10，避免两个 Java 进程同时创建过多数据库连接。

持久化目录统一位于 `/data/ztmdcg/`，包括 MySQL、两个 Redis、MinIO、Qdrant、Nacos、日志和备份。Docker 日志配置 `max-size` 与 `max-file`，防止 60GB 系统盘被日志占满。

## 7. 两个仓库的 CI/CD

### 7.1 soft-training

流水线 Job：

1. `backend_test`：Maven 测试。
2. `frontend_lint_build`：npm lint 和生产构建。
3. `build_images`：构建 backend/frontend 镜像并推送 Registry。
4. `deploy_production`：腾讯云 Shell Runner 拉取 SHA 镜像并更新 Compose。

### 7.2 llm-gateway

流水线 Job：

1. `backend_test`：Java 21 测试和临时 MySQL 集成测试。
2. `frontend_build`：管理台 npm 构建和格式检查。
3. `build_images`：构建 gateway/ui 镜像并推送 Registry。
4. `deploy_production`：更新 gateway/ui；平台基础设施只在首次安装或显式升级时操作。

两个仓库都只在默认分支 `main` 执行生产部署，部署 Job 使用 `needs` 等待镜像成功。镜像只使用不可变的 `$CI_COMMIT_SHA` 标签，不依赖易漂移的 `latest`。

构建 Runner 使用 Docker-in-Docker；部署 Runner 使用 Shell executor，只在腾讯云本机执行 Docker Compose。部署脚本获得 Docker 权限，故两个仓库必须保持 Private、`main` 必须保护、Runner 必须锁定项目。

## 8. 部署数据流与回滚

每次部署：

1. 检查本机 `.env`、外部网络和平台组件健康状态。
2. 保存当前镜像 SHA 为 `previous`。
3. 同步本项目 Compose 和 Nginx 配置到 `/opt/ztmdcg/apps/<project>/`。
4. 登录 `registry.ztmdcg.cn`，拉取当前 SHA 镜像。
5. `docker compose up -d --wait` 替换应用容器。
6. 检查应用健康端点和 Nginx 配置。
7. 成功后写入 `current` 并清理过期 dangling 镜像。

若第 5 或第 6 步失败，脚本使用 `previous` 镜像再次执行 Compose；回滚失败则保留日志并让 Job 标红，不删除数据卷。

两个项目在腾讯云使用同一个 `flock` 文件锁，保证同时推送时部署串行。发布期间允许短暂停机，但平台基础设施不随应用发布重启。

## 9. 安全边界与密钥

- 公网只开放 80/443；SSH 仅允许管理人员 IP。
- MySQL、Redis、Nacos、Sentinel、MinIO、Qdrant 仅容器网络或本机回环可访问。
- Nacos 控制台通过 SSH 隧道访问，不暴露 8848/8850。
- 基础设施密码保存在腾讯云 `/opt/ztmdcg/secrets/*.env`，权限 `640 root:gitlab-runner`。
- JWT、管理员密码、DeepSeek、OpenAI、DashScope Key 写入 Nacos 或受限密钥文件，不进 Git、镜像和普通 CI 变量。
- GitLab 关闭公开注册，启用强密码和双因素认证（条件允许时）。
- Nginx 设置 `X-Content-Type-Options`、`X-Frame-Options`、`Referrer-Policy` 等响应头。

## 10. 备份、验收与故障处理

### 备份

- 每日 MySQL 逻辑备份，保留 7 个日备和 4 个周备。
- MinIO 定期使用 `mc mirror` 同步到异机或对象存储。
- Qdrant 定期快照；向量库允许从 MySQL 索引清单重建。
- Redis 不作为业务事实源，只保留缓存和会话恢复策略。

### 验收

- 四个 HTTPS 域名解析正确并能访问。
- 两个仓库的 `main` push 都能自动跑完 verify、image、deploy。
- 软项智训登录、文件上传、AI 检索和模型调用成功。
- Gateway 管理台登录、API Key、非流式和 SSE 调用成功。
- 构建失败不会影响线上；健康检查失败会回滚上一 SHA。
- 执行一次备份恢复演练并记录耗时。

### 禁止操作

```bash
docker compose down -v
```

该命令会删除数据库、对象存储、向量库或 Nacos 数据卷，生产停机只允许 `stop` 或不带 `-v` 的 `down`。

## 11. 交付范围

后续实施计划将生成或修改：

- 两个仓库的 `.gitlab-ci.yml`。
- 两个项目的生产 Compose、`.env.example` 和健康检查。
- `llm-gateway-project/deploy/platform/` 共享基础设施 Compose 与初始化脚本。
- 两个仓库的 `deploy/nginx/` 配置源文件。
- 腾讯云部署、回滚、备份和恢复脚本。
- 一份面向人工操作的中文部署手册，包含每台服务器、每个控制台菜单、每条命令、成功标志和故障排查。

不在本设计范围内：Kubernetes/K3s、高可用、蓝绿发布、旧环境数据迁移和多节点数据库。
