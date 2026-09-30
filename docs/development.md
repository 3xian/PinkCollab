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

From `android/`: `sh ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug` (`gradlew.bat` on Windows).
Android unit tests run offline; `:app:connectedDebugAndroidTest` needs an attached test device or emulator.

CI also runs the inline-code rendering regression through the optimized `releaseTest` variant:

```sh
sh ./gradlew :app:connectedReleaseTestAndroidTest -PtestBuildType=releaseTest -Pandroid.testInstrumentationRunnerArguments.class=dev.pinkcollab.ui.ReleaseMarkdownDeviceTest
```

This variant inherits release R8/resource shrinking, uses debug signing, and installs as `dev.pinkcollab.releaseTest` alongside the production app. Its test-only `Spanned` probe preserves the instrumentation entry point without keeping renderer or Markwon internals alive. Test harness rules preserve shared tracing/Kotlin APIs; production release rules and signing are unchanged. Run this focused test, not the debug UI suite, against the optimized variant. The regression must fail if the `CorePlugin` class-boundary keep rule is removed.

From the repository root:

```sh
node npm/scripts/check-release.mjs
npm test --prefix npm/pinkcollab
npm pack ./npm/pinkcollab --dry-run
node .github/actions/check-website/check-site.mjs
```

API changes must match the [protocol](protocol.md).

## Discovery regression checks

`cargo test --all-features --locked` includes the external-OMP discovery/adoption fixtures. `cargo test --all-features --locked --test discovery` isolates the REST, history, race and restart cases. If Windows has the normal debug executable running, pass `--target-dir target-discovery` to Cargo instead of stopping that service. Run Android unit tests and `:app:assembleDebugAndroidTest` to compile the device suites; run `:app:connectedDebugAndroidTest` only with an attached test device.

With OMP installed, run `cargo test --all-features --locked --test discovery real_omp_resumes -- --ignored` for a real resume without a provider request. Set `OMP_EXECUTABLE` when it is not on PATH.

## Android app updates

The update dialog downloads the release's `pinkcollab-android.apk` directly into private cache storage and displays progress. Downloads can be cancelled or retried; APKs must match the app package, advertised version, and installed signing certificate before installation. A release without an HTTPS APK asset displays an unavailable message instead of opening a browser. Debug installations cannot upgrade to production APKs because their signing certificates differ.

After downloading, PinkCollab opens Android's installation confirmation through a narrowly scoped FileProvider URI. If needed, it first opens the per-app “Install unknown apps” setting and continues after permission is granted. Cancelling the installer leaves an Install button to retry without downloading again. Downloads survive activity recreation through the ViewModel; after process termination they must be started again.

Download state belongs to the ViewModel's dispatcher. Worker progress is dispatched to that owner and accepted only for the current downloading attempt; queued callbacks cannot revive a cancelled download or overwrite a completed result.

Startup timing: HTTP responses include `Server-Timing: gateway;dur=...` for handler time (excluding body transfer/compression). For local profiling, run `PINKCOLLAB_STARTUP_TIMING=1 ... serve` to log host/session snapshot capture durations without credentials or transcript text. Compare cold launch through the first visible history frame; Android requests 25 initial messages via the optional gzip WebSocket transport.
