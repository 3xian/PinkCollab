# PinkCollab

This package installs the prebuilt [PinkCollab Gateway](https://github.com/3xian/PinkCollab) for the current operating system and CPU architecture. A Rust toolchain is not required.

```sh
npm install -g pinkcollab@latest
cd ~/projects
pinkcollab setup
```

Oh My Pi (OMP) remains a separate dependency; `omp --version` must work before the Gateway can run sessions.

Guided setup requires Tailscale on the host, installed separately, and an account allowed to use Funnel. The phone does not need Tailscale for Funnel. Install the Android app from the same PinkCollab release as this Gateway package. Setup guides sign-in and pairing, then keeps the Gateway running in the background. See the [deployment guide](https://github.com/3xian/PinkCollab/blob/main/docs/deployment.md) for Windows service credentials, Linux logout behavior, and manual setup.

Supported targets are macOS arm64/x64, Linux arm64/x64, and Windows x64. The platform binary is delivered through an optional `@pinkcollab/gateway-*` dependency selected by npm.


Use `pinkcollab status` to check the host and `pinkcollab doctor` for detailed diagnostics. Manual Serve or HTTPS proxy deployments remain supported; their remote connectivity is reported as externally managed, not verified.

After upgrading this package, run `pinkcollab setup` or `pinkcollab service install` to update the stable service binary. Changed running services restart, stopping active OMP runtimes while preserving stored sessions and pairings. If an update fails, resolve the error and rerun it; pending updates are retried.
