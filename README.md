<p align="center"><img src="docs/assets/pinkcollab-logo.png" width="96" alt="PinkCollab logo" /></p>

<h1 align="center">PinkCollab</h1>

<p align="center"><strong>Spawn and steer OMP sessions from Android.</strong></p>

[Oh My Pi (OMP)](https://omp.sh/) runs on a host you own. Your phone launches the work, follows it live, and answers when OMP needs you.

[Get started](docs/getting-started.md) · [Download Android APK](https://github.com/3xian/PinkCollab/releases/latest/download/pinkcollab-android.apk) · [Documentation](#docs)

## Between the phone and OMP

```mermaid
flowchart LR
    A[Android] -->|HTTPS / WSS| T[HTTPS front end]
    T -->|loopback HTTP| B[Gateway on your host]
    B -->|NDJSON| C[OMP session]
    C -->|model requests| P[Your model provider]
```

The Gateway listens on loopback and does not terminate TLS. The phone uses an HTTPS front end — Tailscale Serve, Tailscale Funnel, or your own reverse proxy. Pairing does not create that front end. See [deployment](docs/deployment.md).

## Install

OMP must already be installed on the host and able to reach its model provider. You also need Android 8.0 / API 26 or newer, and an HTTPS address the phone can reach. There is no iOS or browser client.

**Gateway, on the host.** Node.js 18 or newer:

```sh
npm install -g pinkcollab@latest
```

That installs the launcher. It does not compile Rust. Without Node.js, use a [standalone binary](docs/getting-started.md#install-without-nodejs).

**Android app, on the phone.** Install the [signed release APK](https://github.com/3xian/PinkCollab/releases/latest/download/pinkcollab-android.apk). You do not need a JDK, the Android SDK, or Android Studio.

To pair the phone and run a first session, see [Get started](docs/getting-started.md).

The app and the Gateway can come from different releases, but both must speak protocol version 1. These instructions match release v0.1.1.

<a id="current-limits"></a>
## Limits

- Android only, and only sessions this Gateway creates. A terminal session cannot be attached.
- No notifications. A waiting question is visible only while the app is open and connected.
- Reconnecting the phone does not revive a shut-down host. A Gateway restart does not restore running OMP processes; those sessions show as offline. Readable history is not a replay of the live stream.
- A finished turn can still hold a live process, and that process counts against `max_sessions`. See [usage](docs/usage.md#after-a-disconnect-or-restart).
- Not a terminal, editor, Git client, cloud relay, or multi-user product.

## Docs

- [Get started](docs/getting-started.md) — first session
- [Daily use](docs/usage.md) — steer, answer, interrupt, stop
- [Deployment and security](docs/deployment.md) — HTTPS, pairing, revocation
- [CLI and configuration](docs/reference.md) — commands and defaults
- [Architecture](docs/architecture.md) — what a restart restores
- [Development](docs/development.md) — build and test
- [Protocol](docs/protocol.md) — client and adapter authors
- [Release process](docs/npm-release.md) — maintainers, not installation

<!-- These anchors keep older README section links working. They render nothing. -->
<a id="quick-start"></a>
<a id="how-a-task-runs"></a>
<a id="commands"></a>
<a id="configuration"></a>
<a id="build-android"></a>
<a id="build-the-gateway-from-source"></a>
<a id="what-survives-a-restart"></a>
<a id="validation"></a>
<a id="project-layout"></a>

License: MIT.
