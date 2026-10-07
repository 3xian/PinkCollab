# Deployment and networking

Use the same OS user that owns your OMP configuration and conversations. The Gateway listens on `127.0.0.1:8787`; Tailscale or your HTTPS proxy connects the phone to it. Release Android builds require HTTPS.

## Install and pair

Install Node.js 18+ and [OMP](https://omp.sh/) on the host, plus [Tailscale](https://tailscale.com/download) for the default transport. `omp --version` must work. Install the [Android APK](https://github.com/3xian/PinkCollab/releases/latest/download/pinkcollab-android.apk) on the phone, using the **same release** as the CLI.

```sh
npm install -g pinkcollab@latest
pinkcollab --version
pinkcollab setup
```

Run setup from your projects directory, or pass `--workspace <directory>` (repeat for multiple roots). Setup confirms the workspace, guides Tailscale sign-in and Funnel approval, starts the Gateway in the background, and displays a QR to scan. It does not install OMP or Tailscale. After pairing, you can close the terminal; keep the computer awake.

The default connection uses **public HTTPS through Tailscale Funnel**. The phone does not need Tailscale. Your tailnet must permit Funnel; paired credentials protect access, and the URL is not a secret. For private access, use [Tailscale Serve or your own proxy](#other-https-front-ends).

On an existing host, setup preserves settings, conversations, and pairings. At `Pair another phone? [y/N]`, press Enter to finish. You can run `pinkcollab pair` later.

### External HTTPS setup

For a fixed Linux server with an existing HTTPS reverse proxy, Tailscale is not required. Interactive setup uses the same workspace confirmation, background-service installation/update, local health check, and pairing flow:

```sh
pinkcollab setup \
  --transport external \
  --public-url https://pink.example.com \
  --workspace /srv/projects
```

Both external setup modes require an explicit `--public-url`: an absolute HTTPS root with a host and optional port, no username/password, no path other than `/`, and no query or fragment. A trailing `/` is allowed. Setup validates this before making changes, saves it as `public_url`, and uses it for pairing; later `pinkcollab pair` defaults to that saved URL.

The Gateway stays bound to loopback (by default `127.0.0.1:8787`). Configure your Caddy/nginx reverse proxy to forward HTTPS to `http://127.0.0.1:8787`, including WebSocket Upgrade and the `Authorization` header; substitute the configured port if different. Android production builds require a trusted HTTPS certificate. PinkCollab does not create or manage this endpoint, certificates, DNS, or external reachability; `public_url` must be the URL the phone can actually reach.

External setup never invokes Tailscale and clears PinkCollab's managed-Funnel marker only after any configuration changes have been saved successfully; it does not remove an existing Tailscale mapping. A failed configuration save preserves the marker and its managed-Funnel diagnostics. It checks local Gateway health, **not the external endpoint**. Diagnostics report externally managed HTTPS as not verified; test the connection from the phone.

For unattended setup, add `--non-interactive` and provide explicit workspace roots; see [automation](#automation). For upgrades or workspace changes, rerun the same external command, including `--transport external --public-url …`. Omitting the transport selects Tailscale, even when an external URL is already saved. External setup shares the [background-service behavior](#run-as-a-background-service), including Linux's user-lingering requirement for access after logout, and preserves settings, conversations, and pairings.

## Run as a background service

Setup installs and starts the background Gateway. The same management commands work on all platforms:

| Command | Effect |
| --- | --- |
| `pinkcollab service start` | Start an installed Gateway |
| `pinkcollab service stop` | Stop it without disabling future startup |
| `pinkcollab service restart` | Stop and start it |
| `pinkcollab service status` | Check the background process |
| `pinkcollab service install` | Install or update background startup; a new installation needs `start` afterward |
| `pinkcollab service uninstall` | Stop it and remove background startup; preserve data |

| Platform | Startup and logout behavior |
| --- | --- |
| Windows | Current user's login startup; stops on logout. Closing the terminal or locking the screen leaves it running. New installations need no administrator rights or password. |
| macOS | launchd user agent; starts at login |
| Linux | systemd user service; may stop on logout without user lingering. PinkCollab reports this but does not enable lingering automatically. |

Installation copies the executable to a stable user directory and saves PATH for OMP launchers. Use the same user and `--data-dir` for all commands; conflicting installations are rejected. **Updates and graceful stops end active OMP runtimes.** Stored conversations and pairings remain, but processes do not resume automatically.

### Windows login startup

Run `pinkcollab setup` in a normal terminal. The stable executable is `%LOCALAPPDATA%/PinkCollab/bin/pinkcollab-gateway.exe`; `%APPDATA%/Microsoft/Windows/Start Menu/Programs/Startup/PinkCollab.vbs` launches it without a console window. Logs are in `<data-dir>/gateway.log`.

No service account password or service login permission is needed. After a reboot, log in to restore access. There is no automatic crash restart; use `pinkcollab service start` to recover. If Windows disables the startup entry or Windows Script Host, start it manually after login.

**Migrating an old Windows service:** run `pinkcollab setup` once in an Administrator terminal as its original user. The installer checks the account, binary, and data directory, removes the old service, and installs login startup. Later updates use a normal terminal. Configuration and pairings are preserved.

### Upgrade checks and duplicate installations

Finish active work, install the APK and npm package from the same release, then run:

```sh
npm install -g pinkcollab@latest
pinkcollab --version
pinkcollab setup
pinkcollab doctor
```

For a specific APK release, use `pinkcollab@X.Y.Z` instead of `@latest`. `doctor` should show matching CLI and installed-binary hashes and a healthy Gateway. If an update fails, fix the error and rerun setup; do not delete configuration or pairings. Identical completed installs do not restart the Gateway.

For an external HTTPS deployment, add `--transport external --public-url https://collab.example.com` to each `setup` command above and below, using your actual URL. The transport defaults to Tailscale on every invocation.

If `pinkcollab --version` disagrees with `npm list -g pinkcollab --depth=0`, another installation may be ahead on PATH. Running its setup would install that older build again. Use the current npm package directly:

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

To locate the conflict, use `Get-Command pinkcollab -All` in PowerShell or `type -a pinkcollab` on macOS/Linux. The packaged PATH diagnostic is `node (Join-Path (npm root -g) "pinkcollab/bin/check-install.js")` in PowerShell, or `node "$(npm root -g)/pinkcollab/bin/check-install.js"` on macOS/Linux. Run it directly from your shell, outside npm lifecycle scripts; it changes nothing, and shell aliases still need the shell checks.

Correct PATH, or remove only the verified obsolete npm copy with `npm uninstall -g --prefix "/verified/old/prefix" pinkcollab`. Open a new terminal and recheck the version.

## Troubleshooting setup

Start with `pinkcollab doctor`. It reports paths, versions, binary hashes, process state, and local health without changing the installation. `pinkcollab status` shows operational status; running `pinkcollab` alone shows status or suggests setup.

| Problem | Next step |
| --- | --- |
| OMP unavailable | Run `omp --version` as the Gateway user. Check the configured executable and saved PATH. |
| Tailscale sign-in times out | Finish signing in through Tailscale, check `tailscale status`, and rerun setup. Setup allows up to 120 seconds. |
| Funnel approval missing or timed out | Enable Funnel for the device. Interactive setup allows five minutes for browser approval; see [remote-access troubleshooting](#troubleshooting-remote-access). |
| Gateway stopped or update failed | Inspect logs, fix the reported error, then rerun setup. On Windows, also check login startup and Windows Script Host. |
| Installed build differs from CLI | Follow [upgrade checks](#upgrade-checks-and-duplicate-installations); diagnostics do not upgrade automatically. |
| Data-directory conflict | Manage the installation using its original `--data-dir`. |
| Listener already occupied | Stop the manually started Gateway or resolve the port conflict before setup. |
| Phone cannot connect | Check Gateway health and the HTTPS mapping. The phone cannot reach the host's loopback address. |
| Pairing code expired or already used | Run `pinkcollab pair` again. During setup, press Enter after expiry to regenerate, or Ctrl+C to finish. |

Cancelling pairing leaves the Gateway running. Cancelling setup preserves completed steps for the next run. On Linux, ask your administrator about user lingering if you need access after logout; it does not keep the computer awake.

## Pairing and access

```sh
pinkcollab pair
pinkcollab clients
pinkcollab revoke --client <clientId>
```

Pairing codes work once for five minutes. `pair` uses `public_url` or `--url https://…`; it does not guess a hostname or start HTTPS. `--qr <file>` also writes a QR PNG. Removing a host in Android does not revoke its host-side credential; use `revoke`.

`/health` exposes only a fixed process-health marker; `/api/v4/pair` accepts the pairing token. Other API requests and WebSocket upgrades require a paired Bearer token. OMP runs with the Gateway user's filesystem permissions and provider configuration. The workspace allowlist is **not an OS sandbox**.

## Tailscale Funnel

Guided setup handles Funnel. For manual deployment, initialize the configuration and keep the Gateway running:

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

`funnel` publishes the configured loopback listener and writes its HTTPS root to `public_url`; it does not start the Gateway. `--dry-run` changes nothing. `--https` accepts 443, 8443, or 10000; `--tailscale <path>` selects a CLI outside PATH.

Check mappings with `tailscale funnel status`. To remove the default mapping, run `tailscale funnel --https=443 off`. Stopping the Gateway leaves the mapping without a backend. Funnel and Serve share configuration, so publishing on the same port can replace an existing Serve mapping.

### Recommended Tailscale installation on macOS

The [Homebrew CLI formula](https://formulae.brew.sh/formula/tailscale) provides a daemon that starts at boot:

```sh
brew install tailscale
sudo brew services start tailscale
tailscale up
```

Complete sign-in, then run `pinkcollab setup` for Funnel approval and pairing. Existing Tailscale.app users can keep their installation.

### Source checkout on macOS (CLI Tailscale)

Use the Tailscale installation above and [build the Gateway](development.md#build-the-gateway). Run the built executable with `setup`, or use `cargo run -- init --workspace <directory>` and `cargo run -- serve` for foreground development. In another terminal, `cargo run -- funnel` and `cargo run -- pair` complete manual networking and pairing. All Cargo commands run from `gateway/`.

### Other HTTPS front ends

- **Tailscale Serve:** host and phone both join the tailnet. Run `tailscale serve --bg http://127.0.0.1:8787`, check `tailscale serve status`, and set `public_url` to its HTTPS root before pairing.
- **Your own proxy:** use [external HTTPS setup](#external-https-setup) to save its URL and manage the background Gateway without Tailscale.

Substitute the configured port if it differs. Debug Android builds also accept HTTP for `localhost`, `127.0.0.1`, and emulator host `10.0.2.2`.

### Remote-access diagnostics

| Report | Meaning |
| --- | --- |
| Managed Funnel | `setup`/`funnel` recorded a URL matching `public_url`; diagnostics verify Tailscale and the backend mapping |
| Externally managed (not verified) | External setup, manual Serve/proxy configuration, a changed URL, or an older configuration without a Funnel marker |
| Local only | `public_url` is empty |

These checks verify host configuration. Test public reachability from outside the tailnet: an unauthenticated request to `https://<hostname>.ts.net/api/v4/host` should return `401 authentication_required`. A host-side request may use a private route.

### Troubleshooting remote access

If first-time approval times out, run `tailscale funnel --bg --https=443 http://127.0.0.1:8787`, complete browser approval, and rerun setup. Substitute your configured ports. If tailnet policy prohibits Funnel, use Serve or your own proxy.

If `tailscale up` hangs without a login URL, inspect `tailscale status` and `tailscale debug daemon-logs` for DNS or proxy failures. For a foreground daemon that needs a verified local HTTP proxy, run `sudo env HTTPS_PROXY=http://127.0.0.1:<port> HTTP_PROXY=http://127.0.0.1:<port> tailscaled`, keep the proxy running, then retry sign-in.

## Manual service setup

For an existing manual configuration, use the built-in installer instead of writing service definitions:

```sh
pinkcollab service install
pinkcollab service start
pinkcollab doctor
```

Use the same user and data directory as `init`. For source builds or standalone downloads, substitute the native executable for `pinkcollab`; the installer copies it out of Cargo/npm directories to a stable location. It preserves the current absolute PATH, including tools needed by OMP's launcher.

| Platform | Stable binary | Startup definition / logs |
| --- | --- | --- |
| Windows | `%LOCALAPPDATA%/PinkCollab/bin/pinkcollab-gateway.exe` | [Login startup and logs](#windows-login-startup) |
| Linux | `~/.local/share/pinkcollab/bin/pinkcollab-gateway` | `~/.config/systemd/user/pinkcollab.service`; logs: `journalctl --user -u pinkcollab` |
| macOS | `~/Library/Application Support/PinkCollab/bin/pinkcollab-gateway` | `~/Library/LaunchAgents/dev.pinkcollab.gateway.plist`; logs: `<data-dir>/logs/gateway.log` and `gateway-error.log` |

## CLI and configuration

```text
pinkcollab [--data-dir <path>] [<command>]
```

The default data directory is `~/.pinkcollab` (`./.pinkcollab` if no home directory is available). Use the same `--data-dir` on every command. Standalone/source builds use `pinkcollab-gateway` (`.exe` on Windows), with the same arguments. Run `<command> --help` for all flags.

### Commands

| Command | Purpose |
| --- | --- |
| `setup [--workspace <dir>]...` | Configure, update, start, and pair; merges workspace roots. Default workspace is the current directory. `--transport tailscale` (default) uses Funnel; `--transport external --public-url https://…` uses your HTTPS front end. |
| `status` / `doctor` | Operational status / detailed diagnostics. Health and managed background state are checked independently; foreground-only operation is reported as degraded. |
| `service <action>` | [Manage background startup](#run-as-a-background-service) |
| `init --workspace <dir>...` | Manual bootstrap; requires existing directories and OMP. Fails if configuration already exists. |
| `serve` | Foreground Gateway; stops on Ctrl+C or Unix SIGTERM and closes its OMP runtimes |
| `funnel` | [Publish HTTPS](#tailscale-funnel); `--pair` also generates a pairing code |
| `pair` / `clients` / `revoke` | [Manage phone access](#pairing-and-access); pairing JSON goes to stdout and the interactive QR to stderr |
| `leases` | List runtime leases whose process exit was not confirmed |
| `clear-lease --session <id> --generation <id> --verified-exited` | With the Gateway stopped, clear one lease **only after verifying the old OMP process and descendants have exited** |

On Windows, new runtime leases record a named Job Object. After a crash or reboot, starting or sending to an inactive session automatically recovers its lease only when that exact job no longer exists. An existing job (including an empty job during startup) or a failed job lookup never permits another writer. Older leases without job evidence and Unix leases still require the stopped-Gateway, verified-exit procedure above; upgrading does not discard them or replay failed prompts.

`status` exits nonzero if local health, managed background state, OMP, or a managed Funnel check fails. Externally managed HTTPS does not require a verified Funnel. Windows' hidden `background-start`/`background-run` entries are internal; use `service start` or `serve`.

### Automation

Default Tailscale transport:

```sh
pinkcollab setup --non-interactive --workspace /srv/projects
```

Requires an already connected Tailscale installation with Funnel/HTTPS enabled.

External HTTPS transport:

```sh
pinkcollab setup --non-interactive --transport external --public-url https://collab.example.com --workspace /srv/projects
```

Requires your own HTTPS front end and the explicit root URL described in [external HTTPS setup](#external-https-setup), not Tailscale.

Both modes require explicit workspace roots. They never prompt, start a sign-in flow, print pairing credentials, or wait for a phone. They succeed after local health passes; failures exit nonzero. External endpoint reachability is not verified. Use `pinkcollab pair` separately; it defaults to the saved `public_url`.

### Configuration

File: `<data-dir>/config.yaml`. See [the example](../gateway/config.example.yaml). Unknown keys are rejected. Apply changes with setup; changed running installations restart.

| Key | Default | Notes |
| --- | --- | --- |
| `listen` | `127.0.0.1:8787` | Loopback only; HTTPS is handled by Tailscale/proxy |
| `public_url` | empty | Phone-reachable root URL with no userinfo, path, query, or fragment; production requires HTTPS. Required for pairing unless `--url` is given. |
| `name` | `COMPUTERNAME`, `HOSTNAME`, or `my-host` | Host label in Android |
| `workspaces` | empty until setup/init | At least one existing directory; `~` expands on load |
| `omp` | `omp` | Setup/init save an absolute executable entry while preserving symlinks/shims. Broken explicit paths do not fall back to PATH; bare names depend on runtime PATH. |
| `omp_args` | `[]` | Extra arguments after `--mode rpc-ui`; must not set `--mode`, `--no-session`, or `--session`, including `--mode=` and `--session=` forms |
| `max_sessions` | `8` | Integer from 1 to 100; counts live/reserved OMP runtimes |

<a id="max_sessions"></a>

#### `max_sessions`

Creating a SessionRecord uses no slot. Starting OMP reserves a slot until confirmed exit; a completed turn still occupies one while its process remains attached. At the limit, new records can be created but their runtimes cannot start. Restarts do not resume processes automatically. A new Windows runtime can recover its old lease from confirmed named-job absence when the user starts or sends again; unverifiable leases may block a session until safely cleared.

Windows executable lookup honors `PATHEXT`. Unix data directories/files use `0700`/`0600`; Windows uses default ACLs.

## Discovering host OMP conversations

Existing supported OMP histories appear as History in Tasks. Run under their owner's account and allowlist their projects. Close external OMP instances before continuing conversations on the phone; concurrent writers are unsupported.

### Storage selection

Discovery reads version-3 OMP JSONL files (last verified with OMP 18.4.2), using only the Gateway user's selected storage:

- An absolute `--session-dir` in `omp_args` selects a flat directory, for example `omp_args: ["--session-dir", "/absolute/session-directory"]`.
- Otherwise, profile selection follows `--profile`, `OMP_PROFILE`, then `PI_PROFILE`. An empty `OMP_PROFILE` selects the default.
- Default storage is `~/.omp/agent/sessions`; named profiles use `~/.omp/profiles/<profile>/agent/sessions`. Relative `PI_CONFIG_DIR` changes the `.omp` directory name; `PI_CODING_AGENT_DIR` can override the default profile's agent directory.
- On Linux/macOS, an existing `$XDG_DATA_HOME/omp` (or its `profiles/<profile>` directory) takes precedence unless the agent directory is overridden; sessions come from its `sessions` subdirectory.

Set overrides in the Gateway's launch environment, not only your terminal. Recorded project paths must be absolute, exist, and pass the canonical workspace allowlist. Missing projects, malformed paths, and escaping links are excluded.

### Refresh and discovery limits

Metadata is cached for 15 seconds and refreshed by the next list/host-snapshot request. Reopen or reconnect after that interval; there is no polling daemon.

Each refresh visits at most 8,192 entries, one level below standard storage or only the explicit session directory. It considers the newest 1,024 JSONL files among those entries and reads at most 16 KiB per file for listing metadata. Files beyond these budgets and ambiguous duplicate OMP IDs may be omitted. Discovery does not follow discovered links, scan project contents, or repair files.

### External writers

A present OMP publish lock or a file changing during a 200 ms quiet check causes `external_session_busy`. These checks do not detect an idle external process or prevent later writes. Close it before continuing on the phone and leave it closed while PinkCollab owns the runtime. Stale locks are not deleted automatically; other OMP writer versions and non-file storage have not been validated.
