# llm-gateway 本机 push 即部署流水线 — 设计文档

日期:2026-07-14
状态:已确认

## 背景与目标

llm-gateway 全套服务(gateway/ui/MySQL/Redis/Nacos/Sentinel)通过 `llm-gateway/docker-compose.yml` 跑在本机 Docker Desktop 上,但代码 push 到 GitHub 后本机容器不会更新,需要手动 `docker compose build && up -d`。

目标:push 任意分支后,本机 Docker 服务自动重建并更新为该分支代码,并在 GitHub Actions 页面留下可追溯的部署日志。

选型结论(已对比本地 git hook、GHCR+Watchtower 后确认):**GitHub Actions + 本机自托管 runner**。

## 架构与数据流

```
git push(任意分支)
  → GitHub 触发 .github/workflows/deploy-local.yml
  → 派发给本机常驻 self-hosted runner(Windows 服务,对 GitHub 出站长连接,无需公网 IP)
  → runner 在自己的工作目录 checkout 被 push 的 commit
  → 执行 scripts/deploy-local.sh
  → docker compose build gateway ui && up -d --wait gateway ui
  → 健康检查通过 → 绿勾;任何一步失败 → 红叉 + GitHub 默认邮件通知
```

- 只重建会随代码变化的 `gateway`、`ui` 两个服务;基础设施服务(mysql/redis/nacos/sentinel-dashboard)不动。
- runner 用自己 checkout 的副本构建,部署内容严格等于被 push 的 commit,与 `C:\practice` 开发工作区互不干扰。
- 未变化的服务靠 Docker layer 缓存快速跳过(如只改 UI 时 gateway 的 Maven 层全部命中缓存)。

## 组件设计

### 1. Workflow:`.github/workflows/deploy-local.yml`

- 触发:`on: push`(所有分支)。**禁止**在此 workflow 上添加 `pull_request` 触发(安全,见下)。
- `runs-on: self-hosted`;`defaults.run.shell: bash`(本机装有 Git for Windows)。
- 防 fork 误跑:job 级 `if: github.repository == 'shiyuanjava/llm-gateway'`。
- 并发控制:`concurrency: { group: local-deploy, cancel-in-progress: true }`——连续 push 时取消进行中的旧部署,始终收敛到最新 commit。
- 步骤:`actions/checkout@v4` → 运行 `scripts/deploy-local.sh`。业务逻辑全部在脚本里,workflow 只做粘合。

### 2. 部署脚本:`scripts/deploy-local.sh`

bash 脚本(LF 行尾,与仓库既有 shell 脚本约定一致),`set -euo pipefail`:

1. `docker compose -f llm-gateway/docker-compose.yml build gateway ui`
2. `docker compose -f llm-gateway/docker-compose.yml up -d --wait gateway ui`(`--wait` 等待 healthcheck 通过,不健康则非 0 退出,workflow 标红)
3. `docker image prune -f`(仅清 dangling 层,防止反复构建撑爆磁盘)

脚本可在本机直接手动执行,便于排查。

### 3. Runner 安装(一次性手动操作)

文档化到 `docs/runbook-self-hosted-runner.md`,要点:

- 仓库 Settings → Actions → Runners → New self-hosted runner(Windows x64),按页面指引下载解压到独立目录(如 `C:\actions-runner`,不要放在任何 git 仓库内)。
- `config.cmd --url https://github.com/shiyuanjava/llm-gateway --token <一次性token>`,标签用默认(`self-hosted, Windows, X64`)。
- `svc install` + `svc start` 注册为 Windows 服务,开机自启。
- 前置条件:Docker Desktop 需在运行(建议设置 Docker Desktop 开机自启)。

### 4. 公开仓库安全防护

仓库是 Public,自托管 runner 的核心风险是外部代码(fork PR)在本机执行。防护:

- 本 workflow 仅 `on: push`:fork 的 push 触发的是 fork 仓库自己的 Actions,用不到本仓库的 runner;runner 仅注册在本仓库。
- `if: github.repository == ...` 兜底防 fork 场景。
- 仓库 Actions 设置(装 runner 时一并配置,写入 runbook):Settings → Actions → General → Fork pull request workflows → **Require approval for all external contributors**。
- 长期约定:今后任何 `pull_request` 触发的 workflow 一律 `runs-on: ubuntu-latest`,不得使用 self-hosted。

## 错误处理

- 构建失败:旧容器继续运行,服务不中断;workflow 红叉。
- `--wait` 超时/不健康:workflow 红叉,容器保持 compose 的默认行为(新容器已启动但未达健康,可从 Actions 日志与 `docker logs` 排查)。
- Docker Desktop 未运行:脚本第一步即失败,红叉;重开 Docker 后在 Actions 页面 Re-run 即可。
- 部署中途被 concurrency 取消:最多残留 dangling 镜像层,由下次部署的 prune 清理,不影响运行中容器。

## 验证方式

1. runner 安装后在 Settings → Actions → Runners 显示 Idle。
2. push 一个 UI 小改动:Actions 页面出现 run 且在本机 runner 执行,绿勾。
3. `curl --noproxy '*' http://localhost:8081/` 确认 bundle hash 变化;`docker ps` 确认 gateway 为新启动且 healthy。
4. 故意 push 一个编译不过的改动(或本地手动验证脚本失败路径),确认 workflow 红叉且旧服务仍在运行,随后 revert。
