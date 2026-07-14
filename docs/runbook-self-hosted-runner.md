# Self-hosted Runner 安装与安全设置(Windows)

一次性操作。完成后 push 任意分支即自动部署本机 Docker 服务。

## 前置

- Docker Desktop 已安装,并建议设为开机自启(Settings → General → Start Docker Desktop when you sign in)
- Git for Windows 已安装(runner 的 bash 步骤依赖)
- **部署环境变量**(一次性):敏感配置不入库,runner 的全新 checkout 里没有 `.env`,
  部署脚本统一从 `~/.llm-gateway/deploy.env` 读取:

  ```bash
  mkdir -p ~/.llm-gateway && cp llm-gateway/.env ~/.llm-gateway/deploy.env
  ```

  以后改了 `llm-gateway/.env` 记得同步这份拷贝(或直接改 deploy.env)。

## 安装 runner

1. 打开 https://github.com/shiyuanjava/llm-gateway/settings/actions/runners/new
   选 Windows / x64,按页面给出的命令操作(token 是一次性的,以页面为准):

   ```powershell
   mkdir C:\actions-runner; cd C:\actions-runner   # 独立目录,不要放进任何 git 仓库
   # 下载并解压页面指定的 actions-runner-win-x64-*.zip
   ./config.cmd --url https://github.com/shiyuanjava/llm-gateway --token <页面上的一次性token>
   # 提示项全部回车用默认值(runner group / name / labels / work folder)
   ```

2. 常驻方式二选一:

   **前台模式**(简单,当前采用):双击或命令行执行 `run.cmd`,窗口开着就在工作。

   **Windows 服务**(开机自启,推荐稳定后切换)。注意:Windows 版 runner **没有 `svc.cmd`**
   (那是 Linux/macOS 的),服务化要在注册时加 `--runasservice`,且必须用管理员 PowerShell:

   ```powershell
   cd C:\actions-runner
   # 先解除现有注册(token 从 Runners 页面删除对话框或 new runner 页面获取)
   ./config.cmd remove --token <token>
   # 重新注册并注册为服务;服务账户用你自己的账户(NETWORK SERVICE 访问不了 Docker Desktop 的管道)
   ./config.cmd --url https://github.com/shiyuanjava/llm-gateway --token <token> `
     --unattended --runasservice --windowslogonaccount <你的用户名> --windowslogonpassword <你的密码>
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

- 部署历史/日志:仓库 Actions 页 → deploy-local;本机排查看 `C:\actions-runner\_diag\`
- Docker Desktop 没开导致部署失败:开 Docker 后在失败的 run 页面点 **Re-run all jobs**
- 前台模式:关掉 run.cmd 窗口即暂停自动部署,重新执行 `run.cmd` 恢复
- 服务模式:`Get-Service "actions.runner.*"` 查看;`Stop-Service`/`Start-Service` 控制
- 卸载 runner:先停止,然后 `./config.cmd remove --token <token>`

## 已踩过的坑

- **服务模式下 `shell: bash` 会解析到 WSL 的 `C:\Windows\system32\bash.exe`**(服务 PATH 与交互
  会话不同),导致步骤秒败 exit 1。因此 workflow 用 `shell: cmd` + Git Bash 绝对路径
  `"C:\Program Files\Git\bin\bash.exe"` 显式调用,不依赖 PATH。
- runner 的全新 checkout 没有 `.env`,部署脚本从 `~/.llm-gateway/deploy.env` 读取(见"前置")。
- runner 日志里偶发 `BrokerServer SocketException` 退避重试,是本机代理对长轮询的干扰,
  不影响任务派发,可忽略。
