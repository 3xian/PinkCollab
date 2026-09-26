# Development

Build the Android app and Gateway from the same checkout. Requirements: Rust 1.89+, JDK 17 and Android SDK 36; Node.js 18+ for npm and website checks. Publishing is covered in [Releases](npm-release.md).

## Run the Gateway during development

From the repository root, with OMP on `PATH` and an existing workspace:

```sh
cd gateway
cargo run -- init --workspace /absolute/path/to/projects  # once
cargo run
```

On later runs, just use `cargo run` from `gateway/`; it builds and serves in the foreground without file watching. `--` separates Cargo options from Gateway commands (`cargo run -- pair`). The default data directory is `~/.pinkcollab`; pass `--data-dir <path>` after `--` to **every** Gateway command if you want separate development state. See [Deployment](deployment.md#tailscale-funnel) for HTTPS and pairing.

## Build the Gateway

For a release binary, from `gateway/`:

```sh
cargo build --release --locked --bin pinkcollab-gateway
```

The binary is `target/release/pinkcollab-gateway` (`.exe` on Windows). It takes the same arguments as the installed `pinkcollab` CLI. Copy it to a stable path before using it in a [background service](deployment.md#run-as-a-background-service); `target/` is not a service location.

## Build Android

From the repository root, with JDK 17 and SDK 36:

```sh
cd android
sh ./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On Windows use `gradlew.bat` instead; set `sdk.dir` in `android/local.properties` or `ANDROID_HOME`. The debug APK uses a different signing key than the release APK: switching between them requires uninstalling the existing app, which removes phone-side pairings. Revoke old device credentials on the host separately. Release signing is described in [Releases](npm-release.md).

The session pager's page keys must be Bundle-saveable. `SessionKey.pagerKey()` keeps both host and session IDs in one stable `String`; using the `SessionKey` data class directly crashes Android when a session page is composed. Smoke-test startup with at least one saved session, not only an empty session list.

## Validation

Run from `gateway/`:

```sh
cargo fmt --check
cargo clippy --all-targets --all-features --locked -- -D warnings
cargo test --all-features --locked
```

The optional real-OMP smoke test is `cargo test --test omp_smoke -- --ignored` (requires OMP or `OMP_EXECUTABLE`). `omp-fixture` is a deterministic test binary enabled only with `test-fixtures`.

From `android/`, run `sh ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug` (or `gradlew.bat` on Windows). From the repository root, check the npm launcher and website:

```sh
node npm/scripts/check-release.mjs
npm test --prefix npm/pinkcollab
npm pack ./npm/pinkcollab --dry-run
node .github/actions/check-website/check-site.mjs
```

For API changes, see the [protocol](protocol.md).
