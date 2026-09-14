# Gateway deployment

[English](deployment.md) · [简体中文](deployment_cn.md)

The Gateway and OMP use the permissions and credential environment of the same regular OS user. Set `omp` to an absolute path to avoid differences between the service environment's PATH and the terminal's PATH. Keep the configuration under that user's `~/.pinkcollab`, and use the same `--data-dir` when running the phone pairing command.

## Networking

The default loopback listener is suitable for an HTTPS reverse proxy. Public networks, LANs, VPNs, and Tailscale can all carry this protocol. The reverse proxy must forward WebSocket Upgrade and Authorization and use `127.0.0.1:8787` as the upstream address.

To listen directly on a LAN/VPN address, configure:

```yaml
listen: 192.168.1.20:8787
public_url: https://dev-server.example.com:8787
tls_cert: /absolute/path/fullchain.pem
tls_key: /absolute/path/privkey.pem
workspaces:
  - /home/your-user/projects
omp: /home/your-user/.bun/bin/omp
```

Android must trust the certificate chain. Pair using a domain name that matches the certificate. The Gateway rejects non-loopback listeners without TLS configured.

## Linux systemd

Place the Gateway in `~/.local/bin/` and configure an absolute path for OMP. Copy `deploy/pinkcollab.service` to `~/.config/systemd/user/pinkcollab.service`:

```sh
systemctl --user daemon-reload
systemctl --user enable --now pinkcollab
journalctl --user -u pinkcollab -f
```

To keep it running after logout or before login at boot, an administrator can enable linger for the regular user: `loginctl enable-linger YOUR_USER`. The Gateway service still runs as that user.

## macOS launchd

Replace `YOUR_USER` in `deploy/dev.pinkcollab.gateway.plist` and copy it to `~/Library/LaunchAgents/`:

```sh
launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/dev.pinkcollab.gateway.plist
launchctl kickstart -k gui/$(id -u)/dev.pinkcollab.gateway
```

The LaunchAgent runs automatically when the user logs in. Use an absolute path for OMP in the configuration.

## Windows Service

The Windows binary includes a native SCM entry point, `service`. First, run init as the regular user who will run the service, and configure OMP, workspaces, and HTTPS. Then register the service as an administrator. Place the executable in a permanent location, such as `C:\PinkCollab\pinkcollab-gateway.exe`.

Example in an administrator PowerShell (replace the username and paths):

```powershell
sc.exe create PinkCollab binPath= '"C:\PinkCollab\pinkcollab-gateway.exe" --data-dir "C:\Users\YOUR_USER\.pinkcollab" service' start= auto
```

In Windows Services, open the service's Properties → Log On, change the account to that regular user, grant the Log on as a service right, and start the service. Do not use the default LocalSystem account. Restrict access to the user profile directory, OMP configuration, and workspaces to that account. Set `omp` to the absolute path of an executable that the account can run.

```powershell
sc.exe start PinkCollab
sc.exe stop PinkCollab
```

During development, run `pinkcollab-gateway.exe serve` directly and exit with Ctrl+C; service registration is not required.

## Updates and recovery

Stop the service, replace the Gateway binary, and start it again. SQLite uses WAL; identity and pairing credentials are retained. A graceful shutdown stops the OMP runtimes managed by the Gateway. After an unexpected restart, old tasks remain in Recent, and previously running tasks are marked offline. The MVP does not reattach to or restore processes, and does not take over OMP sessions started manually in a terminal.
