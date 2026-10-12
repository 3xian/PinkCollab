# Deployment and networking

Deploy the Gateway under the **same OS user that owns your OMP configuration and conversations**. It listens on loopback (`127.0.0.1:8787` by default); Tailscale or a reverse proxy provides HTTPS to the phone. Android release builds require a trusted HTTPS certificate. The computer must remain awake and reachable; background startup does not prevent sleep.

## Choose a connection

| Connection | Host requirements | Phone requirements | Exposure |
| --- | --- | --- | --- |
| Tailscale Funnel (default) | Tailscale signed in; tailnet policy permits Funnel | Internet access; no Tailscale app needed | Public HTTPS; PinkCollab pairing credentials protect access |
| Tailscale Serve | Tailscale signed in; HTTPS enabled | Tailscale connected to the same tailnet with access to the host | Private tailnet HTTPS |
| Your own HTTPS proxy | Reachable hostname, trusted certificate, reverse proxy on the Gateway host | Access to that endpoint | Determined by your proxy/network policy |

Use Funnel for guided setup. Use [Serve](#other-https-front-ends) for tailnet-only access or [external HTTPS setup](#external-https-setup) for an existing reverse proxy. A public URL is not a secret or an access credential.

## Install and pair

Install Node.js 18+ and [OMP](https://omp.sh/) on the host. Configure OMP's provider credentials and confirm `omp --version` works as the Gateway user. For the default transport, also install [Tailscale](https://tailscale.com/download); setup does not install these prerequisites.

Install the [Android APK](https://github.com/3xian/PinkCollab/releases/latest/download/pinkcollab-android.apk) on the phone and the CLI from the **same release**:

```sh
npm install -g pinkcollab@latest
pinkcollab --version
pinkcollab setup --workspace /absolute/path/to/projects
```

Replace the workspace path with an existing project directory. Repeat `--workspace <directory>` for multiple roots; quote paths containing spaces. Alternatively, run `pinkcollab setup` from your projects directory and confirm that directory when prompted.

Setup saves the workspace roots, guides Tailscale sign-in and Funnel approval, installs and starts the background Gateway, and displays a pairing QR. Scan it in Android. After pairing, you can close the terminal.

On an existing host, setup preserves settings, conversations, and pairings, and merges the requested workspace roots. At `Pair another phone? [y/N]`, press Enter to finish without pairing again. Run `pinkcollab pair` whenever you need another code.

### External HTTPS setup

Configure your HTTPS front end, then run:

```sh
pinkcollab setup --transport external --public-url https://collab.example.com --workspace /srv/projects
```

`--public-url` is required on **every external setup invocation**, including upgrades. It must be an absolute HTTPS root with a host and optional port, no username/password, no path other than `/`, and no query or fragment. A trailing `/` is allowed. Setup saves this as `public_url`; later `pinkcollab pair` uses it by default.

The proxy must forward to the configured loopback listener, preserve the `Authorization` header, and support WebSocket upgrades and long-lived connections. Do not expose the Gateway's HTTP port directly. A minimal Caddy site on the same host is:

```caddyfile
collab.example.com {
    reverse_proxy 127.0.0.1:8787
}
```

Replace the hostname and upstream port. Caddy handles WebSocket forwarding; you must arrange DNS, certificate issuance, and network access for the HTTPS endpoint. For nginx, configure WebSocket Upgrade forwarding explicitly in addition to ordinary HTTP proxying.

External setup does not invoke Tailscale or manage DNS, certificates, or the proxy. It checks **local Gateway health, not external reachability**. Verify the connection from the phone using the [deployment checks](#verify-the-deployment).

When switching from Funnel, external setup does not remove the old Tailscale mapping. Inspect `tailscale funnel status` and remove the obsolete mapping if public access is no longer intended; see [Tailscale Funnel](#tailscale-funnel). Omitting `--transport external` selects the default Funnel flow even if an external URL is already saved.

### Automation

Non-interactive setup requires explicit workspace roots:

```sh
# Tailscale must already be connected, with Funnel/HTTPS enabled.
pinkcollab setup --non-interactive --workspace /srv/projects

# Your HTTPS proxy must already be configured.
pinkcollab setup --non-interactive --transport external --public-url https://collab.example.com --workspace /srv/projects
```

Neither mode prompts, starts sign-in, prints pairing credentials, or waits for a phone. Failures exit nonzero; success means local Gateway health passed, not that the phone can reach it. Run `pinkcollab pair` separately.

## Verify the deployment

1. Run `pinkcollab doctor` for installation paths, versions, binary hashes, background-process state, and local health. `pinkcollab status` gives operational status.
2. From a device on the intended network, request `https://<your-host>/health`. Expect HTTP `200` with `pinkcollab:ok`. For Funnel, test from outside the tailnet; a host-side request may take a private route. For Serve, test with the phone connected to the tailnet.
3. Request `https://<your-host>/api/v4/host` **without credentials**. Expect HTTP `401` with `authentication_required`, not host data.
4. Pair in Android, open an allowed project, and send a prompt. This also exercises authenticated access, WebSocket forwarding, and OMP's provider configuration.

A healthy local process does not prove that DNS, TLS, the proxy, or phone connectivity works. Diagnostics distinguish managed Funnel (mapping checked), externally managed HTTPS (not verified), and local-only configuration (`public_url` empty).

## Run as a background service

Setup installs and starts background startup. Manage it with:

| Command | Effect |
| --- | --- |
| `pinkcollab service start` | Start an installed Gateway |
| `pinkcollab service stop` | Stop it without disabling future startup |
| `pinkcollab service restart` | Stop and start it |
| `pinkcollab service status` | Check the background process |
| `pinkcollab service install` | Install or update startup; a new installation needs `start` afterward |
| `pinkcollab service uninstall` | Stop it and remove startup; preserve data |

| Platform | Startup and logout behavior | Logs |
| --- | --- | --- |
| Windows | Starts at the current user's login; stops on logout. Closing the terminal or locking the screen leaves it running. | `<data-dir>/gateway.log` |
| macOS | launchd user agent; starts at login | `<data-dir>/logs/gateway.log` and `gateway-error.log` |
| Linux | systemd user service; may stop on logout without user lingering | `journalctl --user -u pinkcollab` |

Use the same user and `--data-dir` for every command. The installer copies the executable to a stable user directory and saves PATH for OMP launchers. Conflicting installations are rejected. **Updates, restarts, and graceful stops end active OMP runtimes.** Stored conversations and pairings remain, but processes do not resume automatically.

For Linux access after logout, ask the administrator to enable lingering for the Gateway user, for example `sudo loginctl enable-linger <username>`. PinkCollab reports missing lingering but does not enable it. Lingering does not prevent system sleep. For macOS sleep behavior, see the [lid-close FAQ](faq-macos-lid-close.md).

### Windows login startup

Run setup in a normal terminal as your OMP user. New installations need no administrator rights, service-account password, or service login permission.

The stable executable is `%LOCALAPPDATA%/PinkCollab/bin/pinkcollab-gateway.exe`. `%APPDATA%/Microsoft/Windows/Start Menu/Programs/Startup/PinkCollab.vbs` launches it without a console window. After reboot, log in to restore access.

There is no automatic crash restart; use `pinkcollab service start` to recover. If Windows disables the startup entry or Windows Script Host, start it manually after login.

**Migrating an old Windows service:** run `pinkcollab setup` once in an Administrator terminal as its original user. The installer verifies ownership, binary, and data directory before replacing the old service with login startup. Settings and pairings are preserved; later updates use a normal terminal.

## Upgrade checks and duplicate installations

Finish active work, update the Android APK and npm package to the same release, then update the installed Gateway:

```sh
npm install -g pinkcollab@latest
pinkcollab --version
pinkcollab setup
pinkcollab doctor
```

For a specific APK release, use `pinkcollab@X.Y.Z` instead of `@latest`. **External HTTPS users must replace the setup command** with `pinkcollab setup --transport external --public-url https://collab.example.com`, using their actual URL. Include `--workspace` when adding roots.

`doctor` should show matching CLI and installed-binary hashes and a healthy Gateway. Identical completed installs do not restart it. If an update fails, fix the reported error and rerun setup; do not delete configuration or pairings.

If `pinkcollab --version` disagrees with `npm list -g pinkcollab --depth=0`, another installation may be ahead on PATH. Locate it with `Get-Command pinkcollab -All` in PowerShell or `type -a pinkcollab` on macOS/Linux. Avoid running setup from the obsolete copy; invoke the current npm package directly:

**Windows PowerShell:**

```powershell
$launcher = Join-Path (npm root -g) "pinkcollab/bin/pinkcollab.js"
node $launcher --version
node $launcher setup
node $launcher doctor
```

**macOS/Linux:**

```sh
launcher="$(npm root -g)/pinkcollab/bin/pinkcollab.js"
node "$launcher" --version
node "$launcher" setup
node "$launcher" doctor
```

Use the external transport flags on these setup commands too, if applicable. For a read-only PATH diagnostic, run the package's `bin/check-install.js` through Node from your shell, outside npm lifecycle scripts. Shell aliases still require the shell checks above. Correct PATH or remove only the verified obsolete npm installation, then open a new terminal and recheck the version.

### Recover a stale OMP installation path

Switching OMP installation channels (for example, from Homebrew to the official curl installer) can leave the saved `omp` entry pointing at a removed launcher. `pinkcollab doctor` shows that entry and its validation result; only when it is invalid does doctor probe `omp` on PATH. Doctor never changes configuration or services.

Find the intended launcher as the Gateway user with `type -a omp` and `command -v omp` on macOS/Linux, or `Get-Command omp -All` in PowerShell, then confirm `omp --version` works. A healthy saved launcher remains preferred even if PATH points elsewhere.

Run `pinkcollab setup` to confirm a validated PATH replacement interactively; the confirmation defaults to **No**. To authorize a specific replacement explicitly, use `pinkcollab setup --omp omp` (resolve the current PATH launcher) or `pinkcollab setup --omp "/absolute/path/to/omp"`. Setup validates the replacement before saving it. Non-interactive recovery requires both `--workspace <existing-root>` and `--omp <path-or-omp>`; it never silently replaces a broken saved launcher.

Retain `--transport external --public-url https://your-host` on every recovery setup invocation if you use external HTTPS. OMP provider credentials, PinkCollab pairings, and stored sessions are preserved. A necessary Gateway restart ends active OMP runtimes; finish active work first.

If the required service restart fails, setup exits with an error but keeps the authorized configuration. Resolve the service error, then run `pinkcollab service restart` to apply it; do not delete configuration or pairings.

Background startup uses a saved PATH snapshot, already including the user's `.local/bin` and `.bun/bin` on Unix. Changing shell PATH alone does not update an existing service; rerun setup after changing the launcher environment.

## Pairing and access

```sh
pinkcollab pair
pinkcollab clients
pinkcollab revoke --client <clientId>
```

Pairing codes work once for five minutes. `pair` uses saved `public_url` or `--url https://…`; it does not start HTTPS or guess a hostname. `--qr <file>` also writes a QR PNG. Treat pairing output and QR files as credentials: do not publish them or put them in shared logs.

Removing a host in Android does **not** revoke its host-side credential. Use `clients` to identify the device and `revoke` to remove access, including for a lost phone.

`/health` exposes only a fixed health marker; `/api/v4/pair` accepts a pairing token. Other API requests and WebSocket upgrades require a paired Bearer token. OMP runs with the Gateway user's filesystem permissions and provider configuration. Workspace roots are **not an OS sandbox**; pair only devices you trust with that account's access.

## Tailscale Funnel

Guided setup handles Funnel. For an advanced/manual installation, initialize once and keep the Gateway running:

```sh
pinkcollab init --workspace /absolute/path/to/projects
pinkcollab serve
```

In another terminal, with Tailscale connected:

```sh
pinkcollab funnel --dry-run
pinkcollab funnel
pinkcollab pair
```

`funnel` publishes the configured loopback listener and saves its HTTPS root as `public_url`; it does not start the Gateway. `--dry-run` changes nothing. `--https` accepts 443, 8443, or 10000; `--tailscale <path>` selects a CLI outside PATH.

Check mappings with `tailscale funnel status`. To remove the default mapping, run `tailscale funnel --https=443 off` (use your actual HTTPS port). Stopping the Gateway does not remove the mapping. Funnel and Serve share configuration: publishing on the same port can replace an existing Serve mapping.

### Recommended Tailscale installation on macOS

The [Homebrew CLI formula](https://formulae.brew.sh/formula/tailscale) provides a daemon that starts at boot:

```sh
brew install tailscale
sudo brew services start tailscale
tailscale up
```

Complete sign-in, then run `pinkcollab setup`. Existing Tailscale.app users can keep their installation; do not install a second daemon merely to use PinkCollab.

### Other HTTPS front ends

For **Tailscale Serve**, connect both host and phone to the tailnet, then publish the loopback listener:

```sh
tailscale serve --bg http://127.0.0.1:8787
tailscale serve status
```

Use the HTTPS root reported by Serve with `pinkcollab setup --transport external --public-url https://<your-host>.ts.net --workspace /absolute/path/to/projects`. This manages the Gateway without invoking Funnel. Substitute the configured upstream port if it differs.

For **your own proxy**, follow [external HTTPS setup](#external-https-setup). Source builds and local development belong in [Development](development.md).

### Troubleshooting remote access

If first-time Funnel approval times out, run `tailscale funnel --bg --https=443 http://127.0.0.1:8787`, complete browser approval, and rerun setup. Substitute your configured ports. If tailnet policy prohibits Funnel, use Serve or your own proxy.

For sign-in failures, inspect `tailscale status` and `tailscale debug daemon-logs` for DNS or proxy errors before rerunning setup. If local health passes but the phone cannot connect, check the selected transport, DNS, certificate trust, HTTPS mapping, and WebSocket forwarding using [deployment checks](#verify-the-deployment).

## Troubleshooting setup

Start with `pinkcollab doctor`; it diagnoses without changing the installation. Running `pinkcollab` alone shows status or suggests setup.

| Problem | Next step |
| --- | --- |
| OMP unavailable or prompts fail | Run OMP as the Gateway user; check provider credentials, configured executable, and saved PATH. Reinstall/update startup after changing the launcher environment. |
| Tailscale sign-in times out | Finish sign-in in Tailscale, check `tailscale status`, and rerun setup. |
| Funnel approval missing | Follow [remote-access troubleshooting](#troubleshooting-remote-access) or choose another transport. |
| Gateway stopped or update failed | Inspect the platform logs above, fix the error, and rerun setup. On Windows, check login startup and Windows Script Host. |
| Installed build differs from CLI | Follow [upgrade checks](#upgrade-checks-and-duplicate-installations); diagnostics do not upgrade automatically. |
| Data-directory conflict | Manage the installation with its original `--data-dir` and OS user. |
| Listener already occupied | Stop a manually started Gateway or resolve the port conflict before setup. |
| Pairing code expired or already used | Run `pinkcollab pair` again. In setup, press Enter after expiry to regenerate, or Ctrl+C to finish. |
| History missing or conversation busy | See [host OMP conversations](#discovering-host-omp-conversations); close external writers before continuing on the phone. |

Cancelling pairing leaves the Gateway running. Cancelling setup preserves completed steps for the next run. `status` exits nonzero if local health, managed background state, OMP, or a managed Funnel check fails; foreground-only operation is reported as degraded. Externally managed HTTPS does not require a verified Funnel.

## CLI and configuration

```text
pinkcollab [--data-dir <path>] [<command>]
```

The default data directory is `~/.pinkcollab` (`./.pinkcollab` if no home directory is available). Use the same `--data-dir` on every command. Standalone/source builds use `pinkcollab-gateway` (`.exe` on Windows) with the same arguments. Run `<command> --help` for all flags.

### Commands

| Command | Purpose |
| --- | --- |
| `setup` | Configure/update, install background startup, start, and pair |
| `status` / `doctor` | Operational status / detailed diagnostics |
| `service <action>` | [Manage background startup](#run-as-a-background-service) |
| `init --workspace <dir>...` | Manual bootstrap; requires existing directories and OMP; fails if configuration exists |
| `serve` | Foreground Gateway; Ctrl+C or Unix SIGTERM stops it and its OMP runtimes |
| `funnel` | [Publish HTTPS](#tailscale-funnel); `--pair` also generates a pairing code |
| `pair` / `clients` / `revoke` | [Manage phone access](#pairing-and-access) |
| `leases` | List runtime leases whose process exit was not confirmed |
| `clear-lease --session <id> --generation <id> --verified-exited` | Clear one lease with the Gateway stopped, **only after verifying the old OMP process and descendants have exited** |

For an existing manual configuration, use `service install`, `service start`, then `doctor` instead of writing your own service definition. Use the same user and data directory as `init`.

### Configuration

File: `<data-dir>/config.yaml`. See [the example](../gateway/config.example.yaml). Unknown keys are rejected. Before editing, finish active work and stop the service; after editing, rerun setup with your chosen transport, then run `doctor`. Setup merges workspace roots; remove unwanted roots by editing `workspaces` rather than rerunning setup with fewer flags.

| Key | Default | Notes |
| --- | --- | --- |
| `listen` | `127.0.0.1:8787` | Loopback only; update the HTTPS upstream when changing the port |
| `public_url` | empty | Phone-reachable root URL; production requires HTTPS; pairing requires it unless `--url` is supplied |
| `name` | `COMPUTERNAME`, `HOSTNAME`, or `my-host` | Host label in Android |
| `workspaces` | empty until setup/init | At least one existing directory; `~` expands on load |
| `omp` | `omp` | Setup/init save an absolute executable path; explicit broken paths do not fall back to PATH |
| `omp_args` | `[]` | Extra arguments after `--mode rpc-ui`; must not override `--mode`, `--no-session`, or `--session` |
| `max_sessions` | `8` | Integer from 1 to 100; limits live/reserved OMP runtimes, not stored conversations |

<a id="max_sessions"></a>

#### `max_sessions`

Creating a conversation record uses no slot. Starting OMP reserves a slot until confirmed exit; a completed turn still occupies one while its process remains attached. At the limit, records can still be created, but new runtimes cannot start. Restarting does not resume processes automatically. Unresolved runtime leases may block a session; never clear one merely to bypass the limit without verifying process exit.

### Back up deployment data

Finish active work and stop the Gateway before copying the entire data directory. It contains configuration and paired-device credentials; keep the backup private and outside public repositories. OMP provider configuration and original OMP histories live separately and need their own backup. To restore, use the same OS user, restore the data directory, check workspace/executable paths, and rerun setup with the intended transport. Do not run two Gateways against the same restored data.

## Discovering host OMP conversations

Supported existing OMP histories appear as History in Tasks. Run the Gateway under their owner's account and allowlist the projects. Close external OMP instances before continuing on the phone; concurrent writers are unsupported.

### Storage selection

Discovery reads version-3 OMP JSONL files (last verified with OMP 18.4.2) from the Gateway user's selected storage:

- An absolute `--session-dir` in `omp_args` selects a flat directory, for example `omp_args: ["--session-dir", "/absolute/session-directory"]`.
- Otherwise, profile selection follows `--profile`, `OMP_PROFILE`, then `PI_PROFILE`. An empty `OMP_PROFILE` selects the default.
- Default storage is `~/.omp/agent/sessions`; named profiles use `~/.omp/profiles/<profile>/agent/sessions`. Relative `PI_CONFIG_DIR` changes the `.omp` directory name; `PI_CODING_AGENT_DIR` can override the default profile's agent directory.
- On Linux/macOS, an existing `$XDG_DATA_HOME/omp` (or its `profiles/<profile>` directory) takes precedence unless the agent directory is overridden; sessions come from its `sessions` subdirectory.

Set overrides in the Gateway's launch environment, not only your terminal. Recorded project paths must be absolute, exist, and pass the canonical workspace allowlist. Missing projects, malformed paths, and escaping links are excluded.

### Refresh and discovery limits

Metadata is cached for 15 seconds and refreshed by the next list/host-snapshot request; reopen or reconnect after that interval. There is no polling daemon.

Each refresh visits at most 8,192 entries, one level below standard storage or only the explicit session directory. It considers the newest 1,024 JSONL files and reads at most 16 KiB per file for metadata. Older files and ambiguous duplicate OMP IDs may be omitted. Discovery does not follow discovered links, scan project contents, or repair files.

### External writers

An OMP publish lock or a file changing during the quiet check causes `external_session_busy`. This does not detect an idle external process or prevent later writes. Keep external OMP instances closed while PinkCollab owns the runtime. Stale locks are not deleted automatically; other OMP writer versions and non-file storage have not been validated.
