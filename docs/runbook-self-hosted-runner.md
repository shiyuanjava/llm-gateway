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
