<p align="center"><img src="docs/assets/pinkcollab-logo.png" width="96" alt="PinkCollab logo" /></p>

<h1 align="center">PinkCollab</h1>

<p align="center"><strong>Spawn and steer OMP sessions from Android.</strong></p>

[Oh My Pi (OMP)](https://omp.sh/) runs on a host you own. Your phone launches the work, follows it live, and answers when OMP needs you.

## Install

OMP must already work on the host (`omp --version`). You need Android 8.0 / API 26 or newer. There is no iOS or browser client.

**Gateway, on the host.** Node.js 18 or newer:

```sh
npm install -g pinkcollab@latest
```

Install Tailscale on the host, and allow Funnel for this node. The phone does not install Tailscale.

**Android app, on the phone.** Install the [signed release APK](https://github.com/3xian/PinkCollab/releases/latest/download/pinkcollab-android.apk).

## Use

On the host, allow a project directory that already exists. On Windows, a path looks like `C:\code`.

```sh
pinkcollab init --workspace /absolute/path/to/projects
pinkcollab funnel
pinkcollab serve
```

Leave that terminal open. In another terminal:

```sh
pinkcollab pair
```

`funnel` writes `public_url`, so `serve` and `pair` take no flags. Scan the QR in the app. Open **Workspaces**, choose the directory, create a session, and send a prompt.

The pairing code lasts five minutes and works once. If it expires, run `pair` again.

If Funnel is not allowed for this node, `funnel` stops. Enable the `funnel` attribute in the Tailscale admin console, then run it again.

License: MIT.
