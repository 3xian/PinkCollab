# Gateway 部署

[English](deployment.md) · [简体中文](deployment_cn.md)

Gateway 与 OMP 使用同一个普通 OS 用户的权限和凭据环境。建议 `omp` 配置绝对路径，避免服务环境 PATH 与终端不同。配置路径固定到该用户的 `~/.pinkcollab`，手机配对命令也使用相同 `--data-dir`。

## 网络

默认回环监听适用于 HTTPS 反向代理。公网、LAN、VPN 或 Tailscale 都可以传输本协议。反向代理须转发 WebSocket Upgrade 与 Authorization，并将上游连接设为 `127.0.0.1:8787`。

直接监听 LAN/VPN 地址时配置：

```yaml
listen: 192.168.1.20:8787
public_url: https://dev-server.example.com:8787
tls_cert: /absolute/path/fullchain.pem
tls_key: /absolute/path/privkey.pem
workspaces:
  - /home/your-user/projects
omp: /home/your-user/.bun/bin/omp
```

证书链需要 Android 系统信任，使用与证书匹配的域名进行配对。Gateway 会拒绝未配置 TLS 的非回环监听。

## Linux systemd

将 Gateway 放到 `~/.local/bin/`，配置 OMP 绝对路径。复制 `deploy/pinkcollab.service` 到 `~/.config/systemd/user/pinkcollab.service`：

```sh
systemctl --user daemon-reload
systemctl --user enable --now pinkcollab
journalctl --user -u pinkcollab -f
```

若需要注销后或开机未登录时仍然运行，管理员可为该普通用户启用 linger：`loginctl enable-linger YOUR_USER`。Gateway 服务仍由该用户运行。

## macOS launchd

替换 `deploy/dev.pinkcollab.gateway.plist` 中的 `YOUR_USER`，复制到 `~/Library/LaunchAgents/`：

```sh
launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/dev.pinkcollab.gateway.plist
launchctl kickstart -k gui/$(id -u)/dev.pinkcollab.gateway
```

LaunchAgent 在用户登录时自动运行；配置中的 OMP 使用绝对路径。

## Windows Service

Windows binary 包含原生 SCM 入口 `service`。先用准备运行服务的普通用户执行 init 并配置 OMP、Workspace、HTTPS，再由管理员注册服务。将程序放到长期不移动的路径，例如 `C:\PinkCollab\pinkcollab-gateway.exe`。

管理员 PowerShell 示例（替换用户名和路径）：

```powershell
sc.exe create PinkCollab binPath= '"C:\PinkCollab\pinkcollab-gateway.exe" --data-dir "C:\Users\YOUR_USER\.pinkcollab" service' start= auto
```

在 Windows“服务”属性 → 登录 中将账户改为该普通用户，授予“作为服务登录”权限，然后启动服务。不要使用默认 LocalSystem。用户资料目录、OMP 配置和 Workspace 都应只授权给该账户；`omp` 配置为该账户可运行的 exe 绝对路径。

```powershell
sc.exe start PinkCollab
sc.exe stop PinkCollab
```

开发时可以直接 `pinkcollab-gateway.exe serve`，用 Ctrl+C 退出，不必注册服务。

## 更新与恢复

停止服务后替换 Gateway binary，再启动。SQLite 采用 WAL，identity 和配对凭据保留。正常关闭会停止 Gateway 管理的 OMP runtime。异常重启后旧任务保留在 Recent，原运行中任务标为 offline。MVP 不会重新附着或恢复进程，也不接管终端手动启动的 OMP 会话。
