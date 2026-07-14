# 本机 push 即部署流水线 — 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** push 任意分支到 GitHub 后,本机自托管 runner 自动 checkout 该 commit 并重建/更新 Docker Compose 的 `gateway`、`ui` 服务,部署结果在 Actions 页面可追溯。

**Architecture:** 仓库新增一个 `on: push` 的 workflow,跑在本机 self-hosted runner(Windows 服务)上;部署逻辑全部收在 `scripts/deploy-local.sh`(可独立手动执行);runner 安装与公开仓库安全设置写成 runbook 文档。

**Tech Stack:** GitHub Actions(self-hosted runner,Windows x64)、bash(Git for Windows)、Docker Compose v2(`--wait` 健康门禁)。

**Spec:** `docs/superpowers/specs/2026-07-14-local-deploy-pipeline-design.md`

**测试说明:** shell/YAML 无测试框架,验证方式为 `bash -n` 语法检查 + 脚本本地真实执行(幂等,等价于一次手动部署)+ 端到端 push 触发验证。命令均在仓库根 `C:\practice` 执行。

---

### Task 1: 根目录 .gitattributes(保证脚本 LF)

**Files:**
- Create: `.gitattributes`(仓库根;现有的 `llm-gateway/.gitattributes` 只对其子目录生效)

- [ ] **Step 1: 创建文件**

内容:

```
*.sh text eol=lf
```

- [ ] **Step 2: Commit**

```bash
git add .gitattributes
git commit -m "chore: 根目录 .sh 强制 LF,为部署脚本铺路"
```

---

### Task 2: 部署脚本 scripts/deploy-local.sh

**Files:**
- Create: `scripts/deploy-local.sh`

- [ ] **Step 1: 创建脚本**

```bash
#!/usr/bin/env bash
# 本机部署:重建随代码变化的 gateway/ui 并等待健康。
# 由 GitHub Actions self-hosted runner 调用;也可在仓库根手动执行:bash scripts/deploy-local.sh
set -euo pipefail

cd "$(dirname "$0")/.."
compose() { docker compose -f llm-gateway/docker-compose.yml "$@"; }

echo "==> build gateway ui"
compose build gateway ui

echo "==> up -d --wait gateway ui"
compose up -d --wait gateway ui

echo "==> prune dangling images"
docker image prune -f

echo "==> deploy done"
```

说明:`up --wait` 会等 `gateway` 的 healthcheck 通过(`ui` 无 healthcheck,达到 running 即算就绪),不健康则非 0 退出使 workflow 标红;构建失败时旧容器不受影响。

- [ ] **Step 2: 语法检查**

Run: `bash -n scripts/deploy-local.sh`
Expected: 无输出,退出码 0

- [ ] **Step 3: 本地真实执行一次(等价手动部署,验证脚本正确)**

前置:Docker Desktop 运行中。

Run: `bash scripts/deploy-local.sh`
Expected: 依次输出 `==> build gateway ui`(大量构建日志,缓存命中时很快)、`==> up -d --wait gateway ui`、容器 Healthy/Started、`==> deploy done`,退出码 0

Run: `docker ps --filter name=llm-gateway-gateway-1 --format "{{.Status}}"`
Expected: `Up ... (healthy)`

- [ ] **Step 4: 验证失败路径(脚本失败时非 0 退出)**

用一个连不上的 DOCKER_HOST 让 docker 命令必然失败:

Run: `DOCKER_HOST=tcp://127.0.0.1:1 bash scripts/deploy-local.sh; echo "exit=$?"`
Expected: 报 docker 连接错误,输出 `exit=` 后跟非 0 值(证明 `set -euo pipefail` 会把失败传给 workflow 标红)

- [ ] **Step 5: 确认行尾为 LF**

Run: `git ls-files --eol scripts/deploy-local.sh`(需先 `git add`)
Expected: 含 `w/lf` 且属性列显示 `text eol=lf`

- [ ] **Step 6: Commit**

```bash
git add scripts/deploy-local.sh
git commit -m "feat: 本机部署脚本(compose 重建 gateway/ui 并等待健康)"
```

---

### Task 3: Workflow .github/workflows/deploy-local.yml

**Files:**
- Create: `.github/workflows/deploy-local.yml`

- [ ] **Step 1: 创建 workflow**

```yaml
name: deploy-local

# 安全约定(仓库为 Public):本 workflow 只允许 push 触发。
# 任何 pull_request 触发的 workflow 一律 runs-on: ubuntu-latest,禁止使用 self-hosted。
on:
  push:

concurrency:
  group: local-deploy
  cancel-in-progress: true

jobs:
  deploy:
    if: github.repository == 'shiyuanjava/llm-gateway'
    runs-on: self-hosted
    steps:
      - uses: actions/checkout@v4
      - name: Deploy to local docker compose
        shell: bash
        run: bash scripts/deploy-local.sh
```

- [ ] **Step 2: YAML 结构自查**

Run: `docker run --rm -v "$(pwd)/.github/workflows:/w" mikefarah/yq -e '.jobs.deploy."runs-on"' /w/deploy-local.yml`
Expected: 输出 `self-hosted`

(若拉取 yq 镜像不便,退化为人工核对缩进与字段名后跳过本步。)

- [ ] **Step 3: Commit**

```bash
git add .github/workflows/deploy-local.yml
git commit -m "ci: push 触发本机 self-hosted runner 部署"
```

---

### Task 4: Runner 安装 runbook

**Files:**
- Create: `docs/runbook-self-hosted-runner.md`

- [ ] **Step 1: 创建 runbook**

````markdown
# Self-hosted Runner 安装与安全设置(Windows)

一次性操作。完成后 push 任意分支即自动部署本机 Docker 服务。

## 前置

- Docker Desktop 已安装,并建议设为开机自启(Settings → General → Start Docker Desktop when you sign in)
- Git for Windows 已安装(runner 的 bash 步骤依赖)

## 安装 runner

1. 打开 https://github.com/shiyuanjava/llm-gateway/settings/actions/runners/new
   选 Windows / x64,按页面给出的命令操作(token 是一次性的,以页面为准):

   ```powershell
   mkdir C:\actions-runner; cd C:\actions-runner   # 独立目录,不要放进任何 git 仓库
   # 下载并解压页面指定的 actions-runner-win-x64-*.zip
   ./config.cmd --url https://github.com/shiyuanjava/llm-gateway --token <页面上的一次性token>
   # 提示项全部回车用默认值(runner group / name / labels / work folder)
   ```

2. 注册为 Windows 服务并启动(管理员 PowerShell):

   ```powershell
   cd C:\actions-runner
   ./svc.cmd install
   ./svc.cmd start
   ```

3. 验证:仓库 Settings → Actions → Runners 应显示该 runner 为 **Idle**。

## 公开仓库安全设置(必做)

仓库 Settings → Actions → General:

- Fork pull request workflows from outside collaborators:
  选 **Require approval for all external contributors**

约定(写给未来的自己):

- `deploy-local.yml` 只允许 `on: push`,禁止加 `pull_request` 触发;
- 今后任何 `pull_request` 触发的 workflow 一律 `runs-on: ubuntu-latest`,不得用 self-hosted。

## 日常运维

- 部署历史/日志:仓库 Actions 页 → deploy-local
- Docker Desktop 没开导致部署失败:开 Docker 后在失败的 run 页面点 **Re-run all jobs**
- 暂停自动部署:`./svc.cmd stop`;恢复:`./svc.cmd start`
- 卸载 runner:`./svc.cmd uninstall`,然后 `./config.cmd remove --token <新token>`
````

- [ ] **Step 2: Commit**

```bash
git add docs/runbook-self-hosted-runner.md
git commit -m "docs: self-hosted runner 安装与安全设置 runbook"
```

---

### Task 5: 端到端验证(需要用户在浏览器配合装 runner)

**Files:** 无代码改动

- [ ] **Step 1: 推送分支**

```bash
git push -u origin feat/local-deploy-pipeline
```

Expected: 推送成功。此时 Actions 会触发 deploy-local,但因还没有 runner,run 停在 **Queued**(正常)。

- [ ] **Step 2: 用户按 runbook 安装 runner 并完成安全设置**

引导用户完成 `docs/runbook-self-hosted-runner.md` 的"安装 runner"与"公开仓库安全设置"两节;完成标志:Runners 页面显示 Idle。

- [ ] **Step 3: 观察排队中的 run 被领取并执行**

Runner 上线后,Queued 的 run 应自动开始执行。
Expected: run 转绿;日志里能看到 `==> deploy done`。

- [ ] **Step 4: 验证本机服务已更新**

Run: `docker ps --filter name=llm-gateway --format "{{.Names}} {{.Status}}" | head -5`
Expected: `gateway`/`ui` 为刚重启的时间且 gateway `(healthy)`

Run: `curl -s --noproxy '*' -o /dev/null -w "%{http_code}" http://localhost:8081/`
Expected: `200`

- [ ] **Step 5: 全链路冒烟——push 一个可见小改动**

做一个无害改动(如 README 加一行),push,观察 Actions 自动执行并绿勾,确认"push 即部署"闭环。

```bash
git commit --allow-empty -m "ci: 流水线端到端冒烟验证"
git push
```

Expected: 新 run 自动执行并成功;这次构建全走缓存,应在 1~2 分钟内完成。
