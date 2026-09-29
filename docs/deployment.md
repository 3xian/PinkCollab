# Deployment and networking

The Gateway listens only on loopback (`127.0.0.1:8787`); Tailscale or a reverse proxy supplies HTTPS for the phone. Release Android builds require HTTPS. First-time installation is in the [README](../README.md); commands and configuration are in the [CLI reference](#cli-and-configuration) below.

## Tailscale Funnel

Funnel publishes the Gateway to the **public internet**. The URL is not a secret; pairing credentials and the workspace allowlist provide access control. Tailscale terminates TLS. Your tailnet must allow the node's `funnel` attribute.

### Manual setup (advanced)

After `pinkcollab init`, keep `pinkcollab serve` running in one terminal. With Tailscale connected, run in another:

```sh
pinkcollab funnel --dry-run
pinkcollab funnel
tailscale funnel status
pinkcollab pair
```

`pinkcollab funnel` publishes `127.0.0.1:8787`, writes its HTTPS address to `~/.pinkcollab/config.yaml` as `public_url`, then exits; it does not start the Gateway. The dry run changes nothing. `pair` prints a one-use, five-minute code and a QR in an interactive terminal.

### Recommended Tailscale installation on macOS

For PinkCollab on macOS, we recommend the [Homebrew CLI formula](https://formulae.brew.sh/formula/tailscale):

```sh
brew install tailscale
sudo brew services start tailscale
tailscale up
tailscale status
```

The service command starts `tailscaled` now and at system startup. Complete the browser sign-in prompted by `tailscale up`, then run `pinkcollab setup`. Signing in does not enable Funnel by itself; interactive setup guides first-time Funnel/HTTPS approval. Existing Tailscale.app users can keep their installation; switching is optional.

### Source checkout on macOS (CLI Tailscale)

1. Install the CLI with `brew install tailscale` if needed. In terminal A, run `sudo tailscaled` and leave it open. In terminal B, run `tailscale up`, complete the login/approval, and check `tailscale status`. `up` joins the tailnet; it does **not** publish Funnel. The foreground daemon does not start automatically after reboot.
2. In terminal C, from the repository root, run:

   ```sh
   cd gateway
   cargo run -- init --workspace /absolute/path/to/projects  # once
   cargo run -- serve
   ```

   Keep `cargo run -- serve` open. OMP must be on `PATH`, and the workspace must exist. A `public_url is empty` startup notice is expected before the next step.
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

`pair` uses `public_url` or an explicit `--url`; it will not guess a Tailscale hostname. Its token works once for five minutes. `/health` returns only a fixed process-health marker without authentication; `/api/v3/pair` accepts the single-use pairing token. Other API requests and WebSocket upgrades require a paired Bearer token. To remove a phone's **host-side** access, run `pinkcollab clients`, then `pinkcollab revoke --client <clientId>` (from source: `cargo run -- clients` / `cargo run -- revoke --client <clientId>`). Removing a host in the app alone does not revoke it.

OMP runs with the Gateway user's filesystem permissions; the workspace allowlist is not an OS sandbox. OMP sends model traffic under its own provider configuration.

## Run as a background service

`pinkcollab setup` installs and starts the service automatically. To manage an existing configuration:

```sh
pinkcollab service install
pinkcollab service start
pinkcollab service status
pinkcollab service restart
pinkcollab service stop
pinkcollab service uninstall
```

`service install` registers a new service; use `service start` to start it, or `setup` to do both. Updating a running service restarts it when the binary, configuration, service definition or launch environment changes. Identical completed installs do not restart it. An interrupted update retains its pending activation: after fixing the error, rerun `service install` even if files were already copied.

The installer copies the currently executing native binary to a stable user directory, so npm upgrades do not remove the running service. Repeat `service install` after upgrading npm. It preserves the current absolute PATH for OMP launchers and records the data directory; a conflicting installation for another data directory is rejected. Uninstall removes the service registration, leaving configuration, sessions and paired devices intact.

On macOS this is a launchd user agent; it starts at login. Linux uses a systemd user service. Without user lingering it may stop on logout; `doctor` reports this, but PinkCollab never enables lingering automatically. Windows uses SCM with the current user's explicit credentials, never LocalSystem. Run installation in an administrator terminal as that same user and grant *Log on as a service* if Windows requires it. Passwordless accounts may need a Windows-supported service login credential or manual foreground operation. Non-interactive setup requires the Windows service to have been installed interactively already.

### Remote-access diagnostics

`setup` and `pinkcollab funnel` record the configured Funnel URL in `<data-dir>/funnel-url`. When it matches `public_url`, `status` and `doctor` check the Tailscale connection, hostname and Funnel mapping. This verifies the host configuration, not end-to-end reachability from a phone.

With no marker, or after changing `public_url`, diagnostics label remote access **Externally managed (not verified)**. This supports manual reverse proxies and private Tailscale Serve without requiring a public Funnel. An empty URL is **Local only**. Existing configurations created before this tracking was added are treated as externally managed; rerun `pinkcollab funnel` to adopt a Funnel mapping. Tailscale information in `doctor` is informational for these modes; other failed checks, including a missing background service, still affect its exit status.

### Manual service setup / troubleshooting reference

The following platform examples are for manual deployments. Normal installations should use the commands above.


Use the **same OS user and data directory** as `init`. Copy the native Gateway binary to a stable path: neither npm's global installation tree nor Cargo's `target/` is suitable for a service that survives updates. Preserve a `PATH` that lets the configured OMP launcher find `bun` or `node`. For a source build, copy `gateway/target/release/pinkcollab-gateway` after [building it](development.md#build-the-gateway); for npm, locate the platform binary as below.

### Windows

```powershell
$dir = Join-Path $env:LOCALAPPDATA "PinkCollab/bin"
New-Item -ItemType Directory -Force $dir | Out-Null
$root = Join-Path (npm root -g) "pinkcollab"
$manifest = node -e "console.log(require.resolve('@pinkcollab/gateway-win32-x64/package.json', { paths: [process.argv[1]] }))" $root
Copy-Item (Join-Path (Split-Path $manifest) "bin/pinkcollab-gateway.exe") $dir
sc.exe create PinkCollab binPath= "`"$dir\pinkcollab-gateway.exe`" --data-dir `"$env:USERPROFILE\.pinkcollab`" service-run" start= auto obj= "$env:USERDOMAIN\$env:USERNAME"
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
- First-time Funnel approval: interactive `pinkcollab setup` displays Tailscale instructions live and allows up to five minutes for browser approval. Non-interactive setup requires Funnel/HTTPS to already be enabled. If setup times out, run `tailscale funnel --bg --https=443 http://127.0.0.1:8787` in a terminal, complete approval, then rerun setup with your original options. Substitute your selected HTTPS port (443, 8443 or 10000) and Gateway loopback port. Check `tailscale funnel status` for the exact backend mapping.
- `pair` has no URL: set `public_url` or pass `--url https://…`. A phone cannot use host loopback. If Funnel status shows a mapping but the phone cannot connect, also check that the Gateway is running. A code used or older than five minutes needs a new `pair`.
- A failed service update can be retried with `pinkcollab service install`. The installer tracks unfinished updates, including restarts; copying the new binary alone does not mark the update complete.
- Setup rejects another data directory's service or an occupied unmanaged listener before changing configuration or Funnel. Use the original `--data-dir` to manage an existing service.
- Run `pinkcollab status` for operational status and `pinkcollab doctor` for detailed diagnostics, whether the Gateway is running or stopped. An unavailable OMP may be a missing executable or a service `PATH` that cannot run its launcher.
- A proxy must forward WebSocket Upgrade and `Authorization`. Remove obsolete `tls_cert`/`tls_key` from old configs; the Gateway no longer terminates TLS.

## CLI and configuration

### Invocation

```text
pinkcollab [--data-dir <path>] [<command>]
```

`--data-dir` is global. The default is the `.pinkcollab` directory under the home directory (`config::data_dir()`). If the home directory cannot be resolved, the default is `./.pinkcollab`.

Every command that reads or writes state must use the same OS user and the same data directory. A service started with a different `--data-dir` will not see the pairings and config you created in a terminal.

With no subcommand, an unconfigured data directory prints `pinkcollab setup` and exits successfully without writing files. A configured host prints the same report as `status`.

The npm package name is `pinkcollab`. The standalone and source-built executable is `pinkcollab-gateway` (`.exe` on Windows). Flags are the same.

### Commands

| Command | What it does |
| --- | --- |
| `init --workspace <dir>...` | One-time bootstrap. `--workspace` is required and repeatable. Each path must already exist, must be a directory, and is stored in canonical form. Fails if `config.yaml` already exists or `omp` cannot be found on `PATH`. Creates the data directory, writes `config.yaml` with the absolute executable entry (without resolving symlinks), and opens the database. |
| `serve` | Loads config, opens the database, and serves until SIGINT or, on Unix, SIGTERM. Foreground. Not the default: pass `serve` explicitly. A graceful shutdown stops OMP runtimes this Gateway started. |
| `setup [--workspace <dir>]... [--non-interactive] [--transport tailscale]` | Reconciles config, OMP, Tailscale, remote access and the user background service; waits for local health before showing a QR and waiting for pairing. Default workspace is cwd, with confirmation. Existing roots are merged and deduplicated. Interactive codes can be regenerated after five minutes. Only the tailscale transport is implemented. |
| `status` | Shows Gateway health, managed-service state, remote-access mode, OMP version, and workspace/device/session counts. Verifies the Funnel mapping configured by PinkCollab. An empty public URL is local-only; manual Serve/proxy URLs are externally managed, not verified. Missing Tailscale alone does not fail those modes. Exits nonzero unless the Gateway is healthy, the managed service is running, OMP is available, and a managed Funnel check passes. A healthy foreground Gateway without the managed service is degraded; that does not invalidate externally managed HTTPS. |
| `doctor` | Reports CLI version, optional npm launcher version, installed-binary SHA-256, and Config, OMP, Tailscale, Funnel, Service, Gateway, and Database sections, including failures, paths, and underlying errors. A binary fingerprint mismatch is reported and is not upgraded automatically. Does not change transport or service configuration. |
| `service install/start/stop/restart/status/uninstall` | Manages the current user's background Gateway with this data directory. Stable binary locations and OS details are below. |
| `pair [--url <root>] [--qr <file>]` | Mints a single-use pairing token, prints pairing JSON on stdout, and renders a QR on stderr when stderr is a terminal. `--qr` also writes a PNG. `--url` overrides `public_url`. If neither is set, the command fails and tells you to pass `--url` or run `funnel`. It does not guess a Tailscale hostname and does not start HTTPS. The URL must be `https`, or `http` only for `127.0.0.1`, `localhost`, or `10.0.2.2`. |
| `clients` | Lists paired devices: `clientId`, escaped name, pairing time. Prints `No paired devices.` when the list is empty. |
| `revoke --client <clientId>` | Deletes that device's credential on the host. Fails when the id is unknown. Does not contact the phone. |
| `leases` | Lists session IDs and runtime generations whose process exit was not confirmed before a Gateway restart. |
| `clear-lease --session <id> --generation <generation> --verified-exited` | Clears one exact lease. Requires the Gateway to be stopped; use only after checking the old OMP process and descendants are gone. |
| `funnel [--https 443] [--dry-run] [--tailscale <path>] [--pair]` | Publishes the loopback listener with Tailscale Funnel, writes `public_url`, and exits. It does not start the Gateway. With `--pair` it prints a pairing code. `--https` must be 443, 8443, or 10000. `--dry-run` prints the plan and does not change Tailscale or mint a code. `--tailscale` is the CLI path when `tailscale` is not on `PATH`. If the node `funnel` attribute is unset, PinkCollab reports that Funnel is not allowed for this device. |
| `service-run` | Hidden Windows-only internal entry. Runs under the Service Control Manager. Starting it from a normal terminal fails with `service must be started by the Windows Service Control Manager`. |

### Automation

```sh
pinkcollab setup --non-interactive --workspace /srv/projects
```

Requires explicit workspace roots and an already connected Tailscale installation with Funnel/HTTPS enabled. It never asks questions, starts a login flow, prints pairing credentials, or waits for a phone. It exits successfully after the managed Gateway passes its local health check; use `pinkcollab pair` separately to add devices. Errors are non-zero. `setup` does not automatically install OMP or Tailscale.

The sequence is: load configuration → check service ownership, listener conflicts and Windows service prerequisites → confirm/canonicalize workspaces → validate OMP → merge/save configuration → connect Tailscale → inspect/reconcile Funnel → install/update service → start → retry local health for up to 20 seconds → generate a five-minute QR → wait for a new paired device. Cancellation leaves completed configuration and service steps available for the next run.

PinkCollab Relay is not part of this implementation.

### Configuration

File: `<data-dir>/config.yaml`. The example in the repository is [`gateway/config.example.yaml`](../gateway/config.example.yaml). Unknown keys are rejected. `~` and `~/…` in `workspaces` expand when the file is loaded.

| Key | Default | Constraint |
| --- | --- | --- |
| `listen` | `127.0.0.1:8787` | Must be a loopback address. This is not the URL the phone dials. The Gateway does not terminate TLS. |
| `public_url` | empty | Optional until you pair without `--url`. When set, it must be an absolute `http` or `https` root URL: no userinfo, path, query, or fragment. Production phone URLs are `https`. `funnel` rewrites this line and leaves other comments in place. |
| `name` | `COMPUTERNAME`, else `HOSTNAME`, else `my-host` | Label shown for this host in Android. |
| `workspaces` | empty until `init` | At least one directory at startup. Gateway browse and session-creation boundary only. Not an OS sandbox. |
| `omp` | `omp` in the template; setup/init resolve it | Executable reference. Setup/init persist an absolute executable entry without resolving symlinks/shims to their current targets, so package-manager upgrades can replace the target without changing config. Explicit paths also retain symlinks; broken paths do not fall back to `PATH`. A manually configured bare name still depends on the Gateway's runtime `PATH`. |
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
| Windows executable lookup | Setup/init honor `PATHEXT` when resolving a bare executable name and persist the matched absolute entry, including its extension. |
| Windows service | Management commands are cross-platform; the hidden `service-run` entry is Windows-only. |
| Windows paths | Workspace display strips the `\\?\` and `\\?\UNC\` prefixes from canonical paths. |
| Unix permissions | The data directory is created mode `0700`, and new files mode `0600`, on Unix. Windows uses default ACLs. |
| Stop signals | Unix `serve` stops on SIGINT or SIGTERM. Elsewhere it stops on Ctrl+C. The Windows service also stops on Service Control stop or shutdown. |
| Standalone names | Release assets use `pinkcollab-gateway-<os>-<arch>` plus `.exe` on Windows. npm wraps those binaries behind the `pinkcollab` command. |

OMP itself is not shipped. `omp --version` must succeed for the user who runs the Gateway before sessions can start.

## Troubleshooting guided setup

`pinkcollab` now shows status (or suggests setup on an unconfigured host). Use
`pinkcollab serve` explicitly for a foreground Gateway. `status` checks Gateway
health and the managed service independently. A healthy foreground Gateway with
no running managed service is degraded and returns a nonzero exit code; this does
not invalidate externally managed HTTPS, Tailscale Serve, or custom proxies.

- **Tailscale sign-in timeout:** setup waits at most 120 seconds, including status
  probes. Finish signing in using the Tailscale app or the URL printed by its CLI,
  then rerun `pinkcollab setup`. Run `pinkcollab doctor` if it still cannot connect.
  Ctrl+C ends the login command without minting a pairing token.
- **Funnel permission missing:** enable Funnel for the device in your tailnet,
  then rerun setup. If policy prohibits Funnel, use the manual HTTPS deployment
  instructions above. There is no requirement to migrate an existing manual host.
- **Gateway service failed:** run `pinkcollab doctor` and `pinkcollab service status`.
  Inspect the platform logs described above, correct the reported issue, then rerun
  setup. Do not delete config or pairing credentials.
- **Windows service credentials:** use an elevated terminal as the same Windows
  user. Enter the account password, not Windows Hello PIN. In Windows Services,
  check PinkCollab's Log On account. Grant *Log on as a service* and check that
  *Deny log on as a service* or domain policy does not override it. Policy checks
  are advisory before credentials are supplied; SCM determines actual login
  eligibility at startup. Access denied requires elevation; wrong-account errors
  require correcting the service account, never switching to LocalSystem.
- **Linux logout behavior:** systemd user services may stop after logout. Ask your
  administrator whether enabling user lingering is appropriate for this host.
- **Service version mismatch:** `doctor` prints CLI version, executable, optional
  npm launcher version, installed binary SHA-256, service state and data directory.
  A different binary fingerprint means the background build needs review/update:
  run `pinkcollab setup`. Diagnostics never upgrade it automatically.

Repeated setup asks `Pair another phone? [y/N]` when devices already exist. Press
Enter to finish without generating a token. Non-interactive setup never generates
one. Cancelling pairing leaves the Gateway running; use `pinkcollab pair` later.
After a five-minute pairing expiry, press Enter to regenerate or Ctrl+C to finish.

## Discovering host OMP conversations

Run Gateway under the account owning the OMP sessions and allowlist their project directories. Existing supported OMP histories appear as History in Tasks. Default storage, profiles, absolute `omp_args: ["--session-dir", "/absolute/session-directory"]`, refresh budgets and service-account caveats are described in [OMP discovery](omp-discovery.md). Close an external OMP session before continuing it on the phone; concurrent writers are unsupported. Discovery does not grant access to projects outside configured workspaces.
