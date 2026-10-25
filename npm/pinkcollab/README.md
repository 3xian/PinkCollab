# PinkCollab

Install the prebuilt [PinkCollab Gateway](https://github.com/3xian/PinkCollab) for macOS arm64/x64, Linux arm64/x64, or Windows x64. Requires Node.js 18+; no Rust toolchain is needed. npm selects the native binary through an optional `@pinkcollab/gateway-*` dependency.

## Install and pair

Install [Oh My Pi (OMP)](https://omp.sh/) separately and confirm `omp --version` works under the Gateway user. Install the [Android APK](https://github.com/3xian/PinkCollab/releases/latest/download/pinkcollab-android.apk) from the same release on an Android 8.0+ phone.

```sh
npm install -g pinkcollab@latest
pinkcollab --version
cd /path/to/projects
pinkcollab setup
```

Default setup requires Tailscale on the host and a tailnet that permits public Funnel access. It guides sign-in, starts the background Gateway and displays a pairing QR. The phone does not need Tailscale. For your own HTTPS proxy, configure the proxy first, then use:

```sh
pinkcollab setup --transport external --public-url https://collab.example.com --workspace /srv/projects
```

External setup requires no Tailscale and checks local health, not remote reachability. Private Tailscale Serve also uses external setup. See [deployment](https://github.com/3xian/PinkCollab/blob/main/docs/deployment.md) for proxy requirements, platform behavior and troubleshooting.

Windows starts the Gateway at user login without a password or administrator rights for a new installation. Closing the terminal or locking the screen leaves it running; logout stops it. Existing Windows services require the documented migration. Every host must stay awake.

## Check installation and upgrade

After installing or upgrading, check the shell's selected launcher before setup or update:

```sh
node "$(npm root -g)/pinkcollab/bin/check-install.js"
npm list -g pinkcollab --depth=0
pinkcollab --version
```

In PowerShell, run `node (Join-Path (npm root -g) "pinkcollab/bin/check-install.js")`. Run this outside npm lifecycle scripts, which modify PATH. It reports shadowing installations without deleting files or restarting the Gateway; shell aliases and cached lookups need separate checks.

If versions differ, inspect `type -a pinkcollab` on macOS/Linux or `Get-Command pinkcollab -All` in PowerShell. Correct PATH or invoke the current npm launcher explicitly; see [duplicate installations](https://github.com/3xian/PinkCollab/blob/main/docs/deployment.md#upgrade-checks-and-duplicate-installations).

Finish active work, install the matching APK and npm package, then run:

```sh
pinkcollab update
pinkcollab doctor
```

`update` applies this CLI to the installed background Gateway without changing configuration. A changed running Gateway restarts, ending active OMP runtimes while preserving sessions and pairings. A stopped Gateway stays stopped unless completing an interrupted restart or reloading a still-loaded macOS agent. Use `pinkcollab service start` when needed. If an update fails, fix the error and rerun it; pending updates are retried.

`doctor` should show matching CLI and installed-binary hashes. Use `pinkcollab status` for operational status and `pinkcollab pair` to pair another phone.
