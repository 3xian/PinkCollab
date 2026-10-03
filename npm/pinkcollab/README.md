# PinkCollab

This package installs the prebuilt [PinkCollab Gateway](https://github.com/3xian/PinkCollab) for the current operating system and CPU architecture. A Rust toolchain is not required.

```sh
npm install -g pinkcollab@latest
node "$(npm root -g)/pinkcollab/bin/check-install.js"
cd ~/projects
pinkcollab setup
```

Oh My Pi (OMP) remains a separate dependency; `omp --version` must work before the Gateway can run sessions.

Guided setup requires Tailscale on the host, installed separately, and an account allowed to use Funnel. The phone does not need Tailscale for Funnel. Install the Android app from the same PinkCollab release as this Gateway package. Setup guides sign-in and pairing, then keeps the Gateway running in the background. On Windows it starts at user login, needs no Windows password or administrator rights for a new installation, and stops on logout. Closing the terminal or locking the screen leaves it running. See the [deployment guide](https://github.com/3xian/PinkCollab/blob/main/docs/deployment.md) for migration from old Windows services, Linux logout behavior, and manual setup.

Supported targets are macOS arm64/x64, Linux arm64/x64, and Windows x64. The platform binary is delivered through an optional `@pinkcollab/gateway-*` dependency selected by npm.

Use `pinkcollab status` to check the host and `pinkcollab doctor` for detailed diagnostics. Manual Serve or HTTPS proxy deployments remain supported; their remote connectivity is reported as externally managed, not verified.

After upgrading this package, run `pinkcollab setup` or `pinkcollab service install` to update the stable background binary. A changed running Gateway restarts, stopping active OMP runtimes while preserving stored sessions and pairings. If an update fails, resolve the error and rerun it; pending updates are retried.

Run the PATH check directly from your shell after installing or upgrading. npm changes PATH inside lifecycle scripts, so this diagnostic is deliberately not an install hook. It warns when a different `pinkcollab` takes precedence and prints both the selected command and the installed launcher. Resolve any warning before setup. The check never deletes another installation or restarts a Gateway; shell aliases, cached lookups, and PowerShell-only script precedence require the shell checks below. In PowerShell, run `node (Join-Path (npm root -g) "pinkcollab/bin/check-install.js")`.

Before setup, compare `pinkcollab --version` with `npm list -g pinkcollab --depth=0`. If they differ, inspect `type -a pinkcollab` on macOS/Linux or `Get-Command pinkcollab -All` in PowerShell. Run the launcher from the current npm prefix explicitly or correct PATH before upgrading the Gateway; see [upgrade checks and duplicate installations](https://github.com/3xian/PinkCollab/blob/main/docs/deployment.md#upgrade-checks-and-duplicate-installations). Afterward, `pinkcollab doctor` must show matching CLI and installed-binary hashes. Start a stopped Gateway with `pinkcollab service start`.
