# Deployment and networking

The Gateway listens only on loopback (`127.0.0.1:8787`); Tailscale or a reverse proxy supplies HTTPS for the phone. Release Android builds require HTTPS. First-time installation is in the [README](../README.md); commands and configuration are in the [CLI reference](#cli-and-configuration) below.

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
4. In another terminal, from `gateway/`, run `cargo run -- pair` and scan the QR on the phone. From a network outside the tailnet, an unauthenticated request to `https://<hostname>.ts.net/api/v3/host` should return `401 authentication_required`. A request from the host may resolve through its private tailnet route, so it does not prove public reachability.

`tailscale funnel --https=443 off` removes the public mapping. Stopping the Gateway leaves the mapping with no backend; `tailscale down` disconnects the node without stopping `tailscaled`. Funnel supports public HTTPS ports 443, 8443, and 10000. Funnel and Serve share configuration, so publishing on the same port can replace an existing Serve mapping.

## Other HTTPS front ends

- **Tailscale Serve (tailnet only):** both host and phone join the tailnet. Run `tailscale serve --bg http://127.0.0.1:8787`, then `tailscale serve status`; set `public_url` to its `https://<hostname>.ts.net` root before pairing.
- **Your own proxy:** forward HTTPS to `http://127.0.0.1:8787`, including WebSocket Upgrade and `Authorization`. Set `public_url` to the phone-reachable HTTPS root. Android must trust the certificate. Plain HTTP is accepted only by debug builds for `localhost`, `127.0.0.1`, or emulator host `10.0.2.2`.

## Pairing and access

`pair` uses `public_url` or an explicit `--url`; it will not guess a Tailscale hostname. Its token works once for five minutes. Only `/api/v3/pair` is accessible without a credential; other API requests and WebSocket upgrades require a paired Bearer token. To remove a phone's **host-side** access, run `pinkcollab clients`, then `pinkcollab revoke --client <clientId>` (from source: `cargo run -- clients` / `cargo run -- revoke --client <clientId>`). Removing a host in the app alone does not revoke it.

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

## CLI and configuration

### Invocation

```text
pinkcollab [--data-dir <path>] [<command>]
```

`--data-dir` is global. The default is the `.pinkcollab` directory under the home directory (`config::data_dir()`). If the home directory cannot be resolved, the default is `./.pinkcollab`.

Every command that reads or writes state must use the same OS user and the same data directory. A service started with a different `--data-dir` will not see the pairings and config you created in a terminal.

With no subcommand, the binary runs `serve`.

The npm package name is `pinkcollab`. The standalone and source-built executable is `pinkcollab-gateway` (`.exe` on Windows). Flags are the same.

### Commands

| Command | What it does |
| --- | --- |
| `init --workspace <dir>...` | One-time bootstrap. `--workspace` is required and repeatable. Each path must already exist, must be a directory, and is stored in canonical form. Fails if `config.yaml` already exists. Resolves `omp` on `PATH` to an absolute path and fails if it cannot. Creates the data directory, writes `config.yaml`, and opens the database. |
| `serve` | Loads config, opens the database, and serves until SIGINT or, on Unix, SIGTERM. Foreground. Default when no subcommand is given. A graceful shutdown stops OMP runtimes this Gateway started. |
| `status` | Preflight only. Prints config path, listen address, public URL, each workspace, the resolved OMP path and version, Tailscale state if the `tailscale` binary answers, whether the listen port can be bound, and paired-device and session counts. Prints `status: ready` and exits 0 when nothing is wrong. Exits non-zero while a problem remains. Run it while the Gateway is stopped: a running listener makes the port check fail. Tailscale being absent is printed, not by itself a failure. |
| `pair [--url <root>] [--qr <file>]` | Mints a single-use pairing token, prints pairing JSON on stdout, and renders a QR on stderr when stderr is a terminal. `--qr` also writes a PNG. `--url` overrides `public_url`. If neither is set, the command fails and tells you to pass `--url` or run `funnel`. It does not guess a Tailscale hostname and does not start HTTPS. The URL must be `https`, or `http` only for `127.0.0.1`, `localhost`, or `10.0.2.2`. |
| `clients` | Lists paired devices: `clientId`, escaped name, pairing time. Prints `No paired devices.` when the list is empty. |
| `revoke --client <clientId>` | Deletes that device's credential on the host. Fails when the id is unknown. Does not contact the phone. |
| `leases` | Lists session IDs and runtime generations whose process exit was not confirmed before a Gateway restart. |
| `clear-lease --session <id> --generation <generation> --verified-exited` | Clears one exact lease. Requires the Gateway to be stopped; use only after checking the old OMP process and descendants are gone. |
| `funnel [--https 443] [--dry-run] [--tailscale <path>] [--pair]` | Publishes the loopback listener with Tailscale Funnel, writes `public_url`, and exits. It does not start the Gateway. With `--pair` it prints a pairing code. `--https` must be 443, 8443, or 10000. `--dry-run` prints the plan and does not change Tailscale or mint a code. `--tailscale` is the CLI path when `tailscale` is not on `PATH`. Requires the node `funnel` attribute; otherwise the Tailscale CLI stops with `Funnel not available; "funnel" node attribute not set`. |
| `service` | Windows only. Runs under the Service Control Manager. Starting it from a normal terminal fails with `service must be started by the Windows Service Control Manager`. |

### Configuration

File: `<data-dir>/config.yaml`. The example in the repository is [`gateway/config.example.yaml`](../gateway/config.example.yaml). Unknown keys are rejected. `~` and `~/…` in `workspaces` expand when the file is loaded.

| Key | Default | Constraint |
| --- | --- | --- |
| `listen` | `127.0.0.1:8787` | Must be a loopback address. This is not the URL the phone dials. The Gateway does not terminate TLS. |
| `public_url` | empty | Optional until you pair without `--url`. When set, it must be an absolute `http` or `https` root URL: no userinfo, path, query, or fragment. Production phone URLs are `https`. `funnel` rewrites this line and leaves other comments in place. |
| `name` | `COMPUTERNAME`, else `HOSTNAME`, else `my-host` | Label shown for this host in Android. |
| `workspaces` | empty until `init` | At least one directory at startup. Gateway browse and session-creation boundary only. Not an OS sandbox. |
| `omp` | `omp` in the template; `init` replaces it | Executable path. `init` stores the absolute path it resolved, which a service needs. A bare `omp` fails when the service `PATH` does not match your terminal. |
| `omp_args` | `[]` | Extra arguments placed after `--mode rpc-ui`. Must not set `--mode`, `--no-session`, `--session`, or the `--mode=` / `--session=` forms. |
| `max_sessions` | `8` | Integer from 1 to 100. See below. |
| `tls_cert`, `tls_key` | unset | Accepted by the parser only so old files fail clearly. Any config that still sets either key is rejected. Move TLS to Funnel, Serve, or a reverse proxy. |

<a id="max_sessions"></a>

#### `max_sessions`

The limit counts OMP runtimes from startup reservation through confirmed process exit. A SessionRecord without a process uses no slot, while an attached process whose latest turn has finished still uses one.

A completed session whose process has not exited still occupies a slot. Creating another SessionRecord remains possible at the limit, but starting its runtime or sending its first prompt fails until a slot is free. A restart does not resume OMP processes; unresolved exit leases may still block a session until cleared safely.

### Platform differences

| Topic | Behavior |
| --- | --- |
| Windows executable lookup | `init` honors `PATHEXT` when resolving a bare `omp` name. |
| Windows service | `service` exists only in the Windows build. Install steps are in [Windows](#windows). |
| Windows paths | Workspace display strips the `\\?\` and `\\?\UNC\` prefixes from canonical paths. |
| Unix permissions | The data directory is created mode `0700`, and new files mode `0600`, on Unix. Windows uses default ACLs. |
| Stop signals | Unix `serve` stops on SIGINT or SIGTERM. Elsewhere it stops on Ctrl+C. The Windows service also stops on Service Control stop or shutdown. |
| Standalone names | Release assets use `pinkcollab-gateway-<os>-<arch>` plus `.exe` on Windows. npm wraps those binaries behind the `pinkcollab` command. |

OMP itself is not shipped. `omp --version` must succeed for the user who runs the Gateway before sessions can start.
