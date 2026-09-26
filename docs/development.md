# Development

Build the Android app and Gateway from the same checkout. Requirements: Rust 1.89+, JDK 17, Android SDK 36, and Node.js 18+ for npm and website checks. Publishing is in [Releases](npm-release.md).

## Run the Gateway during development

OMP must be on `PATH`. From `gateway/`:

```sh
cargo run -- init --workspace /absolute/path/to/projects  # once
cargo run
```

`cargo run` serves in the foreground. Put `--` before Gateway arguments (`cargo run -- pair`). The data directory defaults to `~/.pinkcollab`; pass `--data-dir <path>` after `--` on every command that should use another one. HTTPS and pairing: [Deployment](deployment.md#tailscale-funnel).

## Build the Gateway

From `gateway/`:

```sh
cargo build --release --locked --bin pinkcollab-gateway
```

Binary: `target/release/pinkcollab-gateway` (`.exe` on Windows). Copy it out of `target/` before using it as a [background service](deployment.md#run-as-a-background-service).

## Build Android

From `android/`:

```sh
sh ./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On Windows use `gradlew.bat`; set `sdk.dir` in `android/local.properties` or `ANDROID_HOME`. Debug and release APKs use different signing keys, so switching requires uninstalling the app and loses phone-side pairings. Revoke the old host credential separately. Release signing: [Releases](npm-release.md).

## Validation

From `gateway/`:

```sh
cargo fmt --check
cargo clippy --all-targets --all-features --locked -- -D warnings
cargo test --all-features --locked
```

Real-OMP smoke test (needs OMP or `OMP_EXECUTABLE`): `cargo test --test omp_smoke -- --ignored`.

From `android/`: `sh ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug` (`gradlew.bat` on Windows).

From the repository root:

```sh
node npm/scripts/check-release.mjs
npm test --prefix npm/pinkcollab
npm pack ./npm/pinkcollab --dry-run
node .github/actions/check-website/check-site.mjs
```

API changes must match the [protocol](protocol.md).
