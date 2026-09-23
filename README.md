<p align="center"><img src="docs/assets/pinkcollab-logo.png" width="48" alt="PinkCollab logo" /></p>

# PinkCollab — Android control for Oh My Pi

**Your coding agent stays on your machine. You don't have to stay at your desk.**

PinkCollab is a self-hosted Android controller for [Oh My Pi (OMP)](https://omp.sh/). Start an OMP task in a project on your own host, follow its live progress, and step in from your phone when it needs you.

- **Start** — choose a directory under a host's allowed workspace roots and send the first prompt to launch a new OMP task.
- **Watch** — see streaming replies, tool activity, task status, and questions that need an answer.
- **Steer** — add instructions or answer questions; **Interrupt** aborts the current OMP turn but keeps the session, while **Stop** ends its process and accepts no further commands.

Pair more than one host to see their tasks together on your phone.

[Get started](#quick-start) · [Download Android APK](https://github.com/3xian/PinkCollab/releases/latest/download/pinkcollab-android.apk) · [Gateway downloads](https://github.com/3xian/PinkCollab/releases/latest)

[![CI](https://github.com/3xian/PinkCollab/actions/workflows/ci.yml/badge.svg)](https://github.com/3xian/PinkCollab/actions/workflows/ci.yml) [![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE) [![Release](https://img.shields.io/github/v/release/3xian/PinkCollab)](https://github.com/3xian/PinkCollab/releases/latest)

**First task:** choose a workspace on Android → enter a prompt → follow the live reply → steer or answer a question.

## How a task runs

```mermaid
flowchart LR
    A[Android] -->|HTTPS / WSS| T[HTTPS front end]
    T -->|loopback HTTP| B[Gateway on your host]
    B -->|NDJSON| C[OMP subprocess]
    C -->|model requests| P[Your model provider]
```

The Gateway runs on your computer or server, authenticates paired phones, checks workspace roots, and starts one `omp --mode rpc-ui` process per task. Its loopback listener needs an HTTPS front end such as Tailscale Serve, Tailscale Funnel, or your own reverse proxy. OMP and its provider configuration live on the host: **you do not configure model-provider credentials on the phone**. OMP may still send prompts, code, or credentials to its configured model provider according to that provider's setup. The workspace allowlist restricts what the Gateway offers and accepts; it is **not an OS sandbox**.

Pairing uses a five-minute, single-use code. On the host, `pinkcollab clients` lists paired devices and `pinkcollab revoke --client <clientId>` removes one device's host-side access; removing a host in Android alone does not revoke it. See [deployment and networking](docs/deployment.md) for the HTTPS options and [API documentation](docs/protocol.md) for protocol details.

The Gateway is written in Rust/Tokio/Axum with SQLite; Android uses Kotlin/Jetpack Compose. Live events travel over a WebSocket, while the Gateway exchanges NDJSON with OMP. See [Current limits](#current-limits) before relying on background notifications or session recovery.

## Quick start

Run host commands on the computer **that owns the projects**. The Android phone connects to its HTTPS front end, not to the Gateway's loopback socket. The [latest Release](https://github.com/3xian/PinkCollab/releases/latest) carries the signed APK and standalone Gateway binaries; npm distributes the Gateway launcher separately. OMP is a separate install. **Compatibility:** the current Android app requires Gateway protocol version 1; when mixing APK and Gateway downloads from different releases, check that both still speak the same protocol.

### 1. Prepare OMP on the host

Install and configure [Oh My Pi](https://omp.sh/) on the host, including the model provider it will use. In a **host terminal, in any directory**, confirm:

```sh
omp --version
```

PinkCollab does not install OMP or configure its provider. Keep `omp` on the host's `PATH` when you initialize the Gateway; `init` records its absolute executable path.

### 2. Get and initialize the Gateway on the host

**Recommended: npm, Node.js 18+ and npm required.** In a **host terminal, in any directory**, install the public launcher and its native package for your OS, then allow an existing absolute workspace root:

```sh
npm install -g pinkcollab@latest
pinkcollab --version
pinkcollab init --workspace /absolute/path/to/projects
pinkcollab status
```

Replace the example with the real projects directory on the host (on Windows, for example, `C:\code`). Repeat `--workspace` for additional roots. Continue when `status` reports `status: ready`; run it before starting `serve`, because it checks whether the port is available. `init` is one-time and writes `~/.pinkcollab/config.yaml`; to change allowed roots later, edit `workspaces` there.

**Without Node.js:** download the standalone executable for your host from [Gateway downloads](https://github.com/3xian/PinkCollab/releases/latest). Assets: `pinkcollab-gateway-darwin-arm64`, `pinkcollab-gateway-darwin-x64`, `pinkcollab-gateway-linux-arm64`, `pinkcollab-gateway-linux-x64`, and `pinkcollab-gateway-win32-x64.exe`. Compare your download's SHA-256 hash with the Release's [`SHA256SUMS`](https://github.com/3xian/PinkCollab/releases/latest/download/SHA256SUMS).

On **macOS or Linux**, in a **host terminal in the downloaded binary's directory**, grant execute permission. For example, on Apple Silicon:

```sh
chmod +x ./pinkcollab-gateway-darwin-arm64
./pinkcollab-gateway-darwin-arm64 --version
```

Use the filename you actually downloaded on Intel macOS or Linux. On **Windows PowerShell**, in the **download directory**, run `.\pinkcollab-gateway-win32-x64.exe --version`; Windows needs no `chmod`. On either OS, use that same executable path in place of `pinkcollab` for `init --workspace <absolute-path>`, `status`, `serve`, and `pair` below, staying in the download directory for each command. To build instead, see [Build the Gateway from source](#build-the-gateway-from-source) (Rust 1.89+).

### 3. Get the Android app

On the **Android phone**, download and install the [signed release APK](https://github.com/3xian/PinkCollab/releases/latest/download/pinkcollab-android.apk) (Android 8.0 / API 26+). Android may ask you to permit installation from the browser or file manager. The Release APK is different from CI's debug APK or a local [source build](#build-android); Android will not install an update over an app signed with a different key. If switching between those builds, first back up what you need and uninstall the old app (including its locally stored pairings), then install and pair again. You do **not** need JDK, the Android SDK, or Android Studio to install the release APK.

### 4. Start the Gateway on the host

In a **host terminal, in any directory for npm** (or the binary's directory for a download), run:

```sh
pinkcollab serve
```

Leave this terminal open: `serve` runs in the foreground and listens on `127.0.0.1:8787` by default. If you chose a downloaded binary, replace `pinkcollab` with the platform-specific relative path from step 2. Keep using the same host user and data directory for all commands (default `~/.pinkcollab`; if you use `--data-dir`, use that value consistently).

### 5. Give the phone an HTTPS address

In **another host terminal, in any directory**, configure *one* HTTPS front end to forward to `http://127.0.0.1:8787`. For a **private tailnet**, connect the host and phone to Tailscale on the same tailnet and run on the host:

```sh
tailscale serve --bg http://127.0.0.1:8787
tailscale serve status
```

Use the resulting `https://<hostname>.ts.net` address in the next step. **Tailscale Funnel** is different: it exposes the endpoint publicly and requires Funnel policy access; see [Funnel setup](docs/deployment.md#tailscale-funnel-public-simplest). Or use [your own HTTPS reverse proxy](docs/deployment.md#your-own-reverse-proxy), forwarding WebSocket upgrades and the `Authorization` header. The Gateway's loopback **listen address is not the pairing URL**. The phone needs a trusted HTTPS front-end address reachable from its network; neither Serve nor Funnel is automatic.

### 6. Pair and start a task

With `serve` still running, in **the other host terminal** run (from any directory for npm, or the binary's directory for a download):

```sh
pinkcollab pair --url https://gateway.example.com
```

Substitute the actual HTTPS front-end root URL from step 5 (`https://<hostname>.ts.net` for Tailscale Serve, or your proxy's URL). `pair` prints pairing JSON on stdout and a QR in an interactive terminal; it does **not** start or configure HTTPS. In the Android app, scan the QR or enter the URL and one-time token manually within five minutes. Then open **Workspaces**, choose an allowed project directory, create a task, and send the first prompt. If pairing fails, check the HTTPS address and reachability; see [deployment](docs/deployment.md#pairing-and-security). Later, use `pinkcollab clients` and `pinkcollab revoke --client <clientId>` on the host to revoke a device.

## Commands

| Command | What it does |
| --- | --- |
| `init --workspace <dir>...` | One-time bootstrap: creates the data dir and `config.yaml` with every `--workspace` root. |
| `serve` | Runs the Gateway. **This is the default when no subcommand is given** — after `init`, running the binary alone is enough. |
| `status` | Preflights config, roots, OMP, database, Tailscale and port state without starting the Gateway. Run it while the Gateway is stopped; exits non-zero while a problem remains. |
| `pair [--url <root>] [--qr <file>]` | Prints single-use pairing JSON on stdout and, in a terminal, a scannable QR code. `--url` defaults only to the configured `public_url`; when neither is present the command fails instead of guessing an endpoint. `--qr` additionally writes a PNG. |
| `clients` | Lists paired devices: `clientId`, name, pairing time. |
| `setup-funnel [--https 443] [--dry-run] [--tailscale <path>] [--pair]` | Publishes the loopback Gateway on the internet with Tailscale Funnel, writes `public_url`, and with `--pair` prints a pairing code straight away. |
| `revoke --client <clientId>` | Deletes a paired device's credential on the host. Fails when the id is unknown. |
| `service` *(Windows only)* | Runs under the Windows Service Control Manager. |

## Configuration

Lives at `~/.pinkcollab/config.yaml`; see [`gateway/config.example.yaml`](gateway/config.example.yaml).

| Key | Default | What it decides |
| --- | --- | --- |
| `listen` | `127.0.0.1:8787` | The socket to bind — **must be a loopback address**. |
| `public_url` | empty | The root URL the phone dials, baked into the QR code. Must be `https://…` in production. |
| `name` | hostname | The host label shown in Android. |
| `workspaces` | `[]` | Allowed root directories for Gateway browsing and task creation; **not** an OS sandbox for OMP or the host user. |
| `omp` | absolute path `init` resolved | The OMP executable. `init` stores the absolute path it found, which is what a service needs: their `PATH` differs from your terminal's, so a bare `omp` fails there. |
| `omp_args` | `[]` | Extra OMP flags; may not override `--mode` or session storage. |
| `max_sessions` | `8` | Concurrent **live** OMP processes (1–100). A session frees its slot as soon as its process exits — on completion, failure, or **Stop** — so finished tasks do not count against it. |

Startup validation rejects: empty `workspaces`, `max_sessions` outside 1–100, a non-loopback `listen`, a `public_url` that is not a bare root URL, and `omp_args` that override mode or session storage.

## Build Android

From the **repository root on a development computer** with JDK 17+ and Android SDK 36 installed:

```sh
cd android
./gradlew :app:assembleDebug     # Windows: gradlew.bat
```

Set `sdk.dir` in `android/local.properties`, or set `ANDROID_HOME`. The debug APK is written to `android/app/build/outputs/apk/debug/app-debug.apk` relative to the repository root.

Still in the **`android/` directory**, install the debug APK on a connected phone (this is not signed with the Release key):

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

To start a task: **Workspaces → choose a directory → create a task → enter the first prompt in the task composer**. The directory browser lists allowed subdirectories without running project-level inspection commands.

Actions on a task:

- **Prompt / Steer** — the button label and the `streamingBehavior` sent to OMP both key off `status == "running"`: a running session is steered, anything else (idle, completed, failed) starts a new turn.
- **Interrupt** sends OMP `abort` and keeps the runtime attached, so you can keep prompting.
- **Stop** closes OMP's stdin and terminates the process; the session then takes no further commands.
- **Model** opens a single-choice list matching OMP's Ctrl+P cycle (including role order and thinking level), and stays in the composer action row with Interrupt, Stop, and Send.
- **Delete** is not exposed in the app: `DELETE /api/v1/sessions/:id` drops Gateway-side management metadata once the session is stopped, and never touches OMP's own session files.

On some Windows/JDK setups Gradle fails with `Unable to establish loopback connection` — create `C:/tmp` and set `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=C:/tmp`.

## Build the Gateway from source

The npm packages contain prebuilt binaries and never compile Rust during installation. Gateway contributors can build the same CLI from source with Rust 1.89+:

From the **repository root on the host**:

```sh
cd gateway
cargo build --release --locked --bin pinkcollab-gateway
./target/release/pinkcollab-gateway --version
```

On Windows, run `.\target\release\pinkcollab-gateway.exe --version` for the last command.

## What survives a restart

| Data | Survives | Note |
| --- | --- | --- |
| Host identity, credentials, session metadata | Yes | SQLite (WAL) in the data dir |
| Running OMP processes | No | Their metadata is kept and they show as **offline** |
| Conversation history | Yes | Read on demand from OMP's JSONL session files |

*Why the split: live activity is buffered in memory for speed, while transcripts stay in OMP's own files so nothing is duplicated.*

## Validation

```sh
node npm/scripts/check-release.mjs
npm test --prefix npm/pinkcollab
npm pack ./npm/pinkcollab --dry-run

cd gateway
cargo fmt --check
cargo clippy --all-targets --all-features --locked -- -D warnings
cargo test --all-features --locked
cargo test --test omp_smoke -- --ignored   # #[ignore]d: needs OMP installed, or OMP_EXECUTABLE set

cd ../android
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

```sh
npm ci --prefix tools/website                 # once, for the browser checks
node tools/website/check-site.mjs             # links, metadata, README agreement, asset budgets
node tools/website/check-behavior.mjs         # responsive layout, tabs, copy, contrast, 404 page
node tools/website/check-lighthouse.mjs       # stable Lighthouse audits and budgets; diagnostic performance score
node tools/website/build-social-card.mjs      # regenerate website/assets/social-card.png
```

The last three drive a real browser and need a Chrome or Chromium this machine already ships: set `CHROME_PATH`, or have a Playwright install or a `google-chrome`/`chromium` on `PATH`. The same commands run in CI as the `website` job, and a failing check blocks the Pages deploy.

`omp-fixture`, the deterministic stand-in for a real OMP, builds only with the `test-fixtures` feature.

## Project layout

```text
gateway/src/   api · config · events · funnel · model · omp · session · storage · workspace · windows
android/app/src/main/java/dev/pinkcollab/   data · ui · ui/theme
npm/           npm launcher · platform package manifests · release validation
website/       GitHub Pages site: index.html · styles.css · app.js · architecture.html · assets
tools/website/ site checks, the social-card builder, and their dependencies
docs/          deployment and networking (deployment.md) · API protocol (protocol.md)
```

## Current limits

- No terminal emulator, Git client, code editor, or Cloud Relay.
- No multi-user permissions — one host identity with paired devices.
- Sessions are managed, not adopted: one started by hand in a terminal cannot be attached, and an exited process is not restored on restart (it shows as **offline**).
- Live tool events are buffered in memory; after a restart, history is restored from OMP files without the full tool transcript.
- Android background notifications are planned; a task waiting on your input only shows it while the app is open.
