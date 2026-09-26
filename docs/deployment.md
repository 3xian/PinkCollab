# Deployment and networking

The Gateway listens only on loopback (`127.0.0.1:8787`); Tailscale or a reverse proxy supplies HTTPS for the phone. Release Android builds require HTTPS. First-time installation is in the [README](../README.md); CLI options are in [Reference](reference.md).

## Tailscale Funnel

Funnel publishes the Gateway to the **public internet**. The URL is not a secret; pairing credentials and the workspace allowlist provide access control. Tailscale terminates TLS. Your tailnet must allow the node's `funnel` attribute.

### Installed Gateway

After `pinkcollab init`, keep `pinkcollab serve` running in one terminal. With Tailscale connected, run in another:

```sh
pinkcollab funnel --dry-run
pinkcollab funnel
tailscale funnel status
pinkcollab pair
```

`pinkcollab funnel` publishes `127.0.0.1:8787`, writes its HTTPS address to `~/.pinkcollab/config.yaml` as `public_url`, then exits; it does not start the Gateway. The dry run changes nothing. `pair` prints a one-use, five-minute code and a QR in an interactive terminal.

### Source checkout on macOS (CLI Tailscale)

1. Install the CLI with `brew install tailscale` if needed. In terminal A, run `sudo tailscaled` and leave it open. In terminal B, run `tailscale up`, complete the login/approval, and check `tailscale status`. `up` joins the tailnet; it does **not** publish Funnel. The foreground daemon does not start automatically after reboot.
2. In terminal C, from the repository root, run:

   ```sh
   cd gateway
   cargo run -- init --workspace /absolute/path/to/projects  # once
   cargo run
   ```

   Keep `cargo run` open. OMP must be on `PATH`, and the workspace must exist. A `public_url is empty` startup notice is expected before the next step.
3. In terminal B, publish interactively (approve any Tailscale prompt rather than bypassing it):

   ```sh
   tailscale funnel --bg --https=443 http://127.0.0.1:8787
   tailscale funnel status
   ```

   Set `public_url: https://<hostname>.ts.net` in `~/.pinkcollab/config.yaml`, replacing the placeholder with the **actual URL printed by Funnel**. Direct `tailscale funnel` does not update Gateway config; use the same data directory as `init`.
4. In another terminal, from `gateway/`, run `cargo run -- pair` and scan the QR on the phone. From a network outside the tailnet, an unauthenticated request to `https://<hostname>.ts.net/api/v2/host` should return `401 authentication_required`. A request from the host may resolve through its private tailnet route, so it does not prove public reachability.

`tailscale funnel --https=443 off` removes the public mapping. Stopping the Gateway leaves the mapping with no backend; `tailscale down` disconnects the node without stopping `tailscaled`. Funnel supports public HTTPS ports 443, 8443, and 10000. Funnel and Serve share configuration, so publishing on the same port can replace an existing Serve mapping.

## Other HTTPS front ends

- **Tailscale Serve (tailnet only):** both host and phone join the tailnet. Run `tailscale serve --bg http://127.0.0.1:8787`, then `tailscale serve status`; set `public_url` to its `https://<hostname>.ts.net` root before pairing.
- **Your own proxy:** forward HTTPS to `http://127.0.0.1:8787`, including WebSocket Upgrade and `Authorization`. Set `public_url` to the phone-reachable HTTPS root. Android must trust the certificate. Plain HTTP is accepted only by debug builds for `localhost`, `127.0.0.1`, or emulator host `10.0.2.2`.

## Pairing and access

`pair` uses `public_url` or an explicit `--url`; it will not guess a Tailscale hostname. Its token works once for five minutes. Only `/api/v2/pair` is accessible without a credential; other API requests and WebSocket upgrades require a paired Bearer token. To remove a phone's **host-side** access, run `pinkcollab clients`, then `pinkcollab revoke --client <clientId>` (from source: `cargo run -- clients` / `cargo run -- revoke --client <clientId>`). Removing a host in the app alone does not revoke it.

OMP runs with the Gateway user's filesystem permissions; the workspace allowlist is not an OS sandbox. OMP sends model traffic under its own provider configuration.

## Run as a background service

Use the **same OS user and data directory** as `init`. Copy the native Gateway binary to a stable path: neither npm's global installation tree nor Cargo's `target/` is suitable for a service that survives updates. Preserve a `PATH` that lets the configured OMP launcher find `bun` or `node`. For a source build, copy `gateway/target/release/pinkcollab-gateway` after [building it](development.md#build-the-gateway); for npm, locate the platform binary as below.

### Windows

```powershell
$dir = Join-Path $env:LOCALAPPDATA "PinkCollab/bin"
New-Item -ItemType Directory -Force $dir | Out-Null
$root = Join-Path (npm root -g) "pinkcollab"
$manifest = node -e "console.log(require.resolve('@pinkcollab/gateway-win32-x64/package.json', { paths: [process.argv[1]] }))" $root
Copy-Item (Join-Path (Split-Path $manifest) "bin/pinkcollab-gateway.exe") $dir
sc.exe create PinkCollab binPath= "`"$dir\pinkcollab-gateway.exe`" --data-dir `"$env:USERPROFILE\.pinkcollab`" service" start= auto
```

In **Services → PinkCollab → Properties → Log On**, select the user who owns the data and OMP credentials (not LocalSystem), grant *Log on as a service*, then `sc.exe start PinkCollab`. Stop with `sc.exe stop PinkCollab`.

### Linux (systemd)

```sh
pkg="@pinkcollab/gateway-linux-$(node -p 'process.arch')"
root="$(npm root -g)/pinkcollab"
manifest="$(node -e 'console.log(require.resolve(process.argv[1] + "/package.json", { paths: [process.argv[2]] }))' "$pkg" "$root")"
install -Dm755 "$(dirname "$manifest")/bin/pinkcollab-gateway" ~/.local/share/pinkcollab/bin/pinkcollab-gateway
```

Write `~/.config/systemd/user/pinkcollab.service`:

```ini
[Unit]
Description=PinkCollab Gateway
[Service]
ExecStart=%h/.local/share/pinkcollab/bin/pinkcollab-gateway --data-dir %h/.pinkcollab serve
Environment=PATH=%h/.local/bin:%h/.bun/bin:/usr/local/bin:/usr/bin:/bin
Restart=on-failure
TimeoutStopSec=45
UMask=0077
[Install]
WantedBy=default.target
```

Run `systemctl --user daemon-reload && systemctl --user enable --now pinkcollab`. To keep it running after logout, enable user lingering (`loginctl enable-linger YOUR_USER`); this does not keep the machine awake or restore OMP processes after reboot.

### macOS (launchd)

```sh
pkg="@pinkcollab/gateway-darwin-$(node -p 'process.arch')"
root="$(npm root -g)/pinkcollab"
manifest="$(node -e 'console.log(require.resolve(process.argv[1] + "/package.json", { paths: [process.argv[2]] }))' "$pkg" "$root")"
mkdir -p "$HOME/Library/Application Support/PinkCollab/bin"
cp "$(dirname "$manifest")/bin/pinkcollab-gateway" "$HOME/Library/Application Support/PinkCollab/bin/pinkcollab-gateway"
chmod 755 "$HOME/Library/Application Support/PinkCollab/bin/pinkcollab-gateway"
```

Write `~/Library/LaunchAgents/dev.pinkcollab.gateway.plist`, replacing `YOUR_USER`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>Label</key><string>dev.pinkcollab.gateway</string>
  <key>ProgramArguments</key><array>
    <string>/Users/YOUR_USER/Library/Application Support/PinkCollab/bin/pinkcollab-gateway</string>
    <string>--data-dir</string><string>/Users/YOUR_USER/.pinkcollab</string>
    <string>serve</string>
  </array>
  <key>EnvironmentVariables</key><dict>
    <key>PATH</key><string>/Users/YOUR_USER/.local/bin:/Users/YOUR_USER/.bun/bin:/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin</string>
  </dict>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><dict><key>SuccessfulExit</key><false/></dict>
  <key>StandardOutPath</key><string>/Users/YOUR_USER/.pinkcollab/gateway.log</string>
  <key>StandardErrorPath</key><string>/Users/YOUR_USER/.pinkcollab/gateway-error.log</string>
</dict></plist>
```

Run `launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/dev.pinkcollab.gateway.plist` to load it. Update by stopping the service, copying the new binary, and starting it again. A graceful Gateway stop terminates its OMP runtimes; stored sessions and pairings remain, but processes do not resume automatically. See [recovery](architecture.md#data-ownership-and-recovery).

## Troubleshooting

- `tailscale up` hangs before showing a login URL: check `tailscale status` and `tailscale debug daemon-logs`. A control-plane timeout can mean DNS or proxy interference. If the host has a **verified** local HTTP proxy, restart the foreground daemon as `sudo env HTTPS_PROXY=http://127.0.0.1:<port> HTTP_PROXY=http://127.0.0.1:<port> tailscaled`, keep the proxy running, then retry `tailscale up`.
- `pair` has no URL: set `public_url` or pass `--url https://…`. A phone cannot use host loopback. If Funnel status shows a mapping but the phone cannot connect, also check that the Gateway is running. A code used or older than five minutes needs a new `pair`.
- Run `pinkcollab status` (or `cargo run -- status`) **only while the Gateway is stopped**; a live listener makes its port check fail. An unavailable OMP may be a missing executable or a service `PATH` that cannot run its launcher.
- A proxy must forward WebSocket Upgrade and `Authorization`. Remove obsolete `tls_cert`/`tls_key` from old configs; the Gateway no longer terminates TLS.
