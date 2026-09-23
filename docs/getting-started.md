# Get started

This is the only full first-run tutorial. It takes an OMP install that already works and ends with a session you can see on Android.

You need:

- A host where `omp --version` succeeds and the model provider is already configured. PinkCollab does not install OMP or copy its upstream setup guide.
- An Android phone on 8.0 / API 26 or newer. There is no iOS or browser client.
- An HTTPS address the phone can reach. The default below is Tailscale Serve, which requires the host and the phone on the same tailnet.

Run host commands on the computer that owns the projects, as the user who will own `~/.pinkcollab`. The phone never connects to `127.0.0.1`.

If you already have a trusted HTTPS reverse proxy, install and initialize the Gateway, start it, then jump to [Pair the phone](#8-pair-the-phone). Funnel and a Node-free install are linked from the steps that need them; they are not a second tutorial beside this one.

## 1. Confirm OMP on the host

In a host terminal, in any directory:

```sh
omp --version
```

Continue only if that prints a version. `init` records the absolute `omp` path it finds on `PATH`.

## 2. Install the Gateway

**npm path, Node.js 18+ required.** In a host terminal, in any directory:

```sh
npm install -g pinkcollab@latest
pinkcollab --version
```

That installs the public launcher and the native package for this OS. It does not compile Rust. Without Node.js, use [Install without Node.js](#install-without-nodejs) and substitute that executable for `pinkcollab` in every command below.

## 3. Allow a workspace that already exists

Still on the host, replace the example with a real absolute directory. The path must exist. On Windows, an example shape is `C:\code`.

```sh
pinkcollab init --workspace /absolute/path/to/projects
```

Repeat `--workspace` for more roots. `init` runs once and writes `~/.pinkcollab/config.yaml`. If that file exists, edit `workspaces` instead of running `init` again. Stored paths are absolute. Changing roots later does not move or sandbox the projects.

## 4. Check the host before starting

In a host terminal, with the Gateway **not** running:

```sh
pinkcollab status
```

Continue when it prints `status: ready`. `status` is a preflight. It tries to bind the listen port, so running it against an already-started Gateway reports the port unavailable and exits non-zero. That does not mean the running Gateway is broken.

Use the same OS user and the same `--data-dir` for `status`, `serve`, `pair`, and later revocation. The default data directory is `~/.pinkcollab`.

## 5. Start the Gateway

In a host terminal:

```sh
pinkcollab serve
```

Leave this terminal open. `serve` runs in the foreground and listens on `127.0.0.1:8787` by default. A bare `pinkcollab` after `init` does the same thing. Use another terminal for the network and pairing commands.

## 6. Give the phone an HTTPS address

The listen address is not the pairing URL. `pair` does not configure HTTPS.

**Default: Tailscale Serve, private to your tailnet.** Install Tailscale on the host and the phone, join the same tailnet, then in another host terminal:

```sh
tailscale serve --bg http://127.0.0.1:8787
tailscale serve status
```

Use the printed `https://<hostname>.ts.net` address in the next step. Tailscale terminates TLS; you do not install a certificate on the Gateway. The connection is still HTTPS.

Already have a trusted HTTPS reverse proxy? Point it at `http://127.0.0.1:8787`, forward WebSocket upgrades and the `Authorization` header, and use that proxy URL below. Details: [your own reverse proxy](deployment.md#your-own-reverse-proxy).

Need the endpoint on the public internet? That is Tailscale Funnel, not this step. See [Tailscale Funnel](deployment.md#tailscale-funnel). Do not put the phone on the public internet unless you mean to.

## 7. Install the Android app

On the phone, download and install the [signed release APK](https://github.com/3xian/PinkCollab/releases/latest/download/pinkcollab-android.apk). Android may ask you to allow installation from the browser or a file manager. You do not need a JDK, the Android SDK, or Android Studio for this install.

The release APK is signed with a different key from a CI debug APK or a local source build. Android will not update one over the other. To switch, back up what you need, uninstall the old app — that deletes pairings stored on the phone — then install and pair again. Uninstalling does not revoke the phone on the host; use `pinkcollab revoke` for that.

The app in this repository requires Gateway protocol version 1. When you mix an APK and a Gateway from different releases, confirm both still speak that protocol.

## 8. Pair the phone

With `serve` still running, in the other host terminal:

```sh
pinkcollab pair --url https://gateway.example.com
```

Replace the example with the HTTPS root from step 6, such as `https://<hostname>.ts.net`. Do not use `http://127.0.0.1:8787`.

`pair` prints pairing JSON on stdout and, in an interactive terminal, a QR code on stderr. It does not start HTTPS. The token lasts five minutes and is consumed by one successful pair. If pairing fails, or the code expires, run `pair` again.

In the Android app, scan the QR, or enter the HTTPS root and the one-time token manually. The sheet accepts a Gateway root URL only: `https`, no path, query, or userinfo. A debug build also allows `http` to `localhost`, `127.0.0.1`, or the emulator host `10.0.2.2`. A release build does not.

If the phone cannot connect, check that it can open the HTTPS origin, that Serve and the phone share a tailnet, and that you did not paste the loopback address. More cases: [pairing](deployment.md#pairing-and-security).

<a id="9-create-a-task-and-read-the-reply"></a>
## 9. Create a session and read the reply

On the phone:

1. Open **Workspaces**.
2. Choose the host, then the project directory you allowed.
3. Tap **Create session here**. The app creates the session without a prompt. The Gateway starts an attached, idle OMP process in that directory.
4. In the composer, send a first prompt. An empty session uses the placeholder "What should OMP do?".

Example request, not a guaranteed transcript:

> Explain how this project's tests are organized, and suggest one small improvement. Do not modify any files.

You should see the session leave the empty state and show the agent's reply or tool activity. "Do not modify any files" is an instruction to the agent, not a sandbox.

What not to expect on this first run:

- The example does not always make OMP ask a question. Answer a prompt only if an attention card appears.
- **Steer** applies while the session status is running. The composer placeholder then says "Steer OMP…". The send button stays **Send**. After the turn completes, send another prompt only if the runtime is still attached; otherwise the composer stays disabled. See [after a session finishes](usage.md#after-a-turn-finishes).
- Closing the app does not notify you. Reopen it to see whether OMP is waiting.

## What you have, and what is next

The host is running a Gateway you started, the phone holds a credential for that Gateway, and one OMP session has produced output. Daily operations — extra instructions, questions, model choice, Interrupt, Stop, and more than one host — are in [Daily use](usage.md).

To keep the Gateway running after you close the terminal, use [Run as a background service](deployment.md#run-as-a-background-service). To list or revoke phones, stay on the same user and data directory and see [pairing and security](deployment.md#pairing-and-security).

## Install without Node.js

Download the standalone executable for the host from the [latest release](https://github.com/3xian/PinkCollab/releases/latest). Assets:

- `pinkcollab-gateway-darwin-arm64`
- `pinkcollab-gateway-darwin-x64`
- `pinkcollab-gateway-linux-arm64`
- `pinkcollab-gateway-linux-x64`
- `pinkcollab-gateway-win32-x64.exe`

Compare the download's SHA-256 with the release [`SHA256SUMS`](https://github.com/3xian/PinkCollab/releases/latest/download/SHA256SUMS).

On macOS or Linux, in the download directory:

```sh
chmod +x ./pinkcollab-gateway-darwin-arm64
./pinkcollab-gateway-darwin-arm64 --version
```

Use the filename you downloaded. On Windows PowerShell, in the download directory, run `.\pinkcollab-gateway-win32-x64.exe --version`. Windows needs no `chmod`.

Use that same path, from that directory, in place of `pinkcollab` for `init`, `status`, `serve`, and `pair`. Building from source is a developer path and needs Rust 1.89+; see [Development](development.md#build-the-gateway).
