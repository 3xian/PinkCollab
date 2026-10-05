# Development

Build the Android app and Gateway from the same checkout. Requirements: Rust 1.89+, JDK 17, Android SDK 36, and Node.js 18+ for npm and website checks.

## Run the Gateway during development

OMP must be on `PATH`. From `gateway/`:

```sh
cargo run -- init --workspace /absolute/path/to/projects  # once
cargo run -- serve
```

`cargo run -- serve` serves in the foreground. Put `--` before Gateway arguments (`cargo run -- pair`). The data directory defaults to `~/.pinkcollab`; pass `--data-dir <path>` after `--` on every command that should use another one. HTTPS and pairing: [Deployment](deployment.md#tailscale-funnel).

## Build the Gateway

From `gateway/`:

```sh
cargo build --release --locked --bin pinkcollab-gateway
```

Binary: `target/release/pinkcollab-gateway` (`.exe` on Windows).

## Build Android

From `android/`:

```sh
sh ./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On Windows use `gradlew.bat`; set `sdk.dir` in `android/local.properties` or `ANDROID_HOME`. Debug and release APKs use different signing keys, so switching requires uninstalling the app and loses phone-side pairings. Release signing: [Releases](npm-release.md).

## Validation

From `gateway/`:

```sh
cargo fmt --check
cargo clippy --all-targets --all-features --locked -- -D warnings
cargo test --all-features --locked
```

Real-OMP smoke test (needs OMP or `OMP_EXECUTABLE`): `cargo test --test omp_smoke -- --ignored`.

On Windows, `cargo test --all-features --locked --test windows_background` checks the hidden login launcher, duplicate starts, graceful stop/restart, and preserved host identity using isolated temporary directories. It does not register real login startup or modify Windows services.

From `android/`: `sh ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug` (`gradlew.bat` on Windows).
Android unit tests run offline; `:app:connectedDebugAndroidTest` needs an attached test device or emulator.

Run the optimized Android rendering regression on an attached device or emulator:

```sh
sh ./gradlew :app:connectedReleaseTestAndroidTest -PtestBuildType=releaseTest -Pandroid.testInstrumentationRunnerArguments.class=dev.pinkcollab.ui.ReleaseMarkdownDeviceTest
```

`releaseTest` enables release R8/resource shrinking, uses debug signing, and installs alongside the production app. Use the focused test above rather than the debug UI suite.

From the repository root:

```sh
node npm/scripts/check-release.mjs
npm test --prefix npm/pinkcollab
npm pack ./npm/pinkcollab --dry-run
node .github/actions/check-website/check-site.mjs
```

API changes must match the [protocol](protocol.md).

Website layout changes also need a browser check at desktop and mobile widths. The setup-guide button and release APK link use a wrapping action row with a 16px horizontal gap and a 12px gap between rows; verify both side-by-side and wrapped layouts without horizontal overflow. After changing `website/styles.css`, update its SHA-256 prefix in the `styles.css?v=...` references in `website/index.html` and `website/404.html` so cached pages load the new styling.

## Discovery regression checks

Run `cargo test --all-features --locked --test discovery` for the discovery/adoption regressions. If the debug Gateway executable is running on Windows, add `--target-dir target-discovery` instead of stopping the service.

With OMP installed, run `cargo test --all-features --locked --test discovery real_omp_resumes -- --ignored` for a real resume without a provider request. Set `OMP_EXECUTABLE` when it is not on PATH.

## Local profiling

HTTP responses include `Server-Timing: gateway;dur=...` for handler time, excluding body transfer and compression. Run `PINKCOLLAB_STARTUP_TIMING=1 cargo run -- serve` from `gateway/` to log host/session snapshot timings without credentials or transcript text.
