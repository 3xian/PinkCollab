<p align="center">
  <img src="docs/assets/pinkcollab-logo.png" width="96" alt="PinkCollab logo" />
</p>

<h1 align="center">PinkCollab</h1>

<p align="center"><strong>Spawn and Control Oh My Pi (OMP) Tasks from Android</strong></p>

[![Android](https://img.shields.io/badge/Android-3DDC84?logo=android&logoColor=white)](#build-android)
[![Min SDK: API 26+](https://img.shields.io/badge/Min_SDK-API_26%2B-3DDC84?logo=android&logoColor=white)](android/app/build.gradle.kts)
[![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF?logo=kotlin&logoColor=white)](android/)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack_Compose-4285F4?logo=jetpackcompose&logoColor=white)](android/app/src/main/java/dev/pinkcollab/ui/)

[Website](https://3xian.github.io/PinkCollab/) · [Setup guide](#quick-start) · [Deployment guide](docs/deployment.md) · [API documentation](docs/protocol.md)

**Spawn new OMP tasks remotely — not just control existing ones.** PinkCollab lets you start and manage **Oh My Pi (OMP)** tasks on your own computers from an Android phone.

- Pick an allowed project directory, send the first prompt, and spawn a real OMP task there.
- Watch streaming replies, tool activity, and questions the agent asks.
- Send **Prompt / Steer**, answer those questions, **Interrupt**, or **Stop**.
- See tasks from every paired host in one list, newest first, each with its live status.

Gateway: **Rust / Tokio / Axum / SQLite**, shipped as one standalone executable.
Android: **Kotlin / Jetpack Compose / Material 3**.
Model provider credentials stay on the host — the phone talks to your Gateway, never to a model provider.

<p align="center">
  <img src="docs/assets/pinkcollab-better-life.webp" width="480" alt="Before PinkCollab: late-night work at a desk. With PinkCollab: relaxing while managing agent tasks from a phone." />
  <br />
  <em>Same code. Brighter tomorrow. Keep your agents close, and enjoy life beyond the desk.</em>
</p>

## How a task runs

```mermaid
flowchart LR
    A[Android] -->|Spawn and control over HTTPS / WSS| B[Gateway] -->|Spawns OMP subprocesses via NDJSON| C[OMP]
```

- **Android** selects a workspace, sends the first prompt to spawn a task, and keeps one WebSocket open for live updates and control.
- **Gateway** authenticates the caller, enforces the workspace allowlist, spawns OMP, and owns the task lifecycle.
- **OMP** runs as one subprocess per task (`omp --mode rpc-ui`); the Gateway speaks NDJSON to it over stdin/stdout.

*Why one subprocess per task: a crashed task cannot take down the others.*

## Quick start

This path builds the Gateway and Android app, then pairs one computer with one phone. The phone can use any supported HTTPS front end; Tailscale Funnel is optional.

### 1. Install the prerequisites

On the computer that will run tasks, install:

- **OMP**, with `omp --version` working in your terminal
- **Rust 1.89+**
- **JDK 17+ and Android SDK 36** to build the Android app

### 2. Build and initialize the Gateway

From the repository root, replace `/absolute/path/to/projects` with a directory that contains the projects the phone may access. Repeat `--workspace` to allow more than one directory.

```sh
cd gateway
cargo build --release --locked --bin pinkcollab-gateway
./target/release/pinkcollab-gateway init --workspace /absolute/path/to/projects
./target/release/pinkcollab-gateway status
```

On Windows, use `.\target\release\pinkcollab-gateway.exe` instead of `./target/release/pinkcollab-gateway` and a workspace such as `C:\code`.

Run `init` only once. It creates `~/.pinkcollab/config.yaml`; edit its `workspaces` list later if you need to change the allowed directories. Continue only when `status` ends with `status: ready`.

### 3. Build and install the Android app

```sh
cd ../android
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On Windows, run the build with `cmd.exe /c gradlew.bat :app:assembleDebug`. You can also open `android/` in Android Studio and install the app from there. See [Build Android](#build-android) if the SDK or `adb` is not configured yet.

### 4. Start the Gateway

Return to `gateway/` and keep this terminal open:

```sh
cd ../gateway
./target/release/pinkcollab-gateway serve
```

### 5. Connect and pair

The phone needs an HTTPS address that forwards to the Gateway. In a second terminal, from `gateway/`, choose one option:

```sh
# Use an HTTPS front end you already run:
./target/release/pinkcollab-gateway pair --url https://gateway.example.com

# Or set up Tailscale Funnel and pair in one command:
./target/release/pinkcollab-gateway setup-funnel --pair
```

If `public_url` is already set in `config.yaml`, run `pinkcollab-gateway pair` without `--url`. You do not need to recreate an existing Funnel, Serve, or reverse-proxy setup each time.

Each command prints a single-use QR code. Open PinkCollab on the phone and scan it within **5 minutes**. Then choose a workspace, create a task, and enter your first prompt. See [Deployment and networking](docs/deployment.md) for all networking options, security details, and background-service setup.

> All commands use `~/.pinkcollab` by default. If you pass `--data-dir`, use the same value for every command and for the background service.

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
| `workspaces` | `[]` | Allowed root directories — the Gateway refuses to touch anything outside them. *This is the sandbox for all file access.* |
| `omp` | absolute path `init` resolved | The OMP executable. `init` stores the absolute path it found, which is what a service needs: their `PATH` differs from your terminal's, so a bare `omp` fails there. |
| `omp_args` | `[]` | Extra OMP flags; may not override `--mode` or session storage. |
| `max_sessions` | `8` | Concurrent **live** OMP processes (1–100). A session frees its slot as soon as its process exits — on completion, failure, or **Stop** — so finished tasks do not count against it. |

Startup validation rejects: empty `workspaces`, `max_sessions` outside 1–100, a non-loopback `listen`, a `public_url` that is not a bare root URL, and `omp_args` that override mode or session storage.

## Build Android

```sh
cd android
./gradlew :app:assembleDebug     # Windows: gradlew.bat
```

Set `sdk.dir` in `local.properties`, or set `ANDROID_HOME`. The APK is written to `android/app/build/outputs/apk/debug/app-debug.apk`.

Install on a connected device:

```sh
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

To start a task: **Workspaces → choose a directory → create a task → enter the first prompt in the task composer**. The directory browser shows each project's Git branch and working-tree status when `git` is available.

Actions on a task:

- **Prompt / Steer** — the button label and the `streamingBehavior` sent to OMP both key off `status == "running"`: a running session is steered, anything else (idle, completed, failed) starts a new turn.
- **Interrupt** sends OMP `abort` and keeps the runtime attached, so you can keep prompting.
- **Stop** closes OMP's stdin and terminates the process; the session then takes no further commands.
- **Model** opens a single-choice list matching OMP's Ctrl+P cycle (including role order and thinking level), and stays in the composer action row with Interrupt, Stop, and Send.
- **Delete** is not exposed in the app: `DELETE /api/v1/sessions/:id` drops Gateway-side management metadata once the session is stopped, and never touches OMP's own session files.

On some Windows/JDK setups Gradle fails with `Unable to establish loopback connection` — create `C:/tmp` and set `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=C:/tmp`.

## What survives a restart

| Data | Survives | Note |
| --- | --- | --- |
| Host identity, credentials, session metadata | Yes | SQLite (WAL) in the data dir |
| Running OMP processes | No | Their metadata is kept and they show as **offline** |
| Conversation history | Yes | Read on demand from OMP's JSONL session files |

*Why the split: live activity is buffered in memory for speed, while transcripts stay in OMP's own files so nothing is duplicated.*

## Validation

```sh
cd gateway
cargo fmt --check
cargo clippy --all-targets --all-features --locked -- -D warnings
cargo test --all-features --locked
cargo test --test omp_smoke -- --ignored   # #[ignore]d: needs OMP installed, or OMP_EXECUTABLE set

cd ../android
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

`omp-fixture`, the deterministic stand-in for a real OMP, builds only with the `test-fixtures` feature.

## Project layout

```text
gateway/src/   api · config · events · funnel · model · omp · session · storage · workspace · windows
android/app/src/main/java/dev/pinkcollab/   data · ui · ui/theme
docs/          deployment and networking (deployment.md) · API protocol (protocol.md)
```

## Current limits

- No terminal emulator, Git client, code editor, or Cloud Relay.
- No multi-user permissions — one host identity with paired devices.
- Sessions are managed, not adopted: one started by hand in a terminal cannot be attached, and an exited process is not restored on restart (it shows as **offline**).
- Live tool events are buffered in memory; after a restart, history is restored from OMP files without the full tool transcript.
- Android background notifications are planned; a task waiting on your input only shows it while the app is open.
