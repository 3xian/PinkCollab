# Development

Build and test PinkCollab from this repository. Source builds use Gateway API v2; the published v0.1.1 app and Gateway use API v1. Build both components from this checkout when testing them together. For publishing, use the [release process](npm-release.md).

## What you need

| Area | Requirements |
| --- | --- |
| Gateway | Rust 1.89+ |
| Android | JDK 17+, Android SDK 36, and a Gateway with API v2 |
| npm and website checks | Node.js 18+; Chrome or Chromium only when regenerating the social card |

The npm packages ship prebuilt binaries. `npm install -g pinkcollab` does not compile Rust.

## Build the Gateway

From the repository root, on the machine that will run it:

```sh
cd gateway
cargo build --release --locked --bin pinkcollab-gateway
./target/release/pinkcollab-gateway --version
```

On Windows, the last command is `.\target\release\pinkcollab-gateway.exe --version`.

Use that binary in place of `pinkcollab` for `init`, `status`, `serve`, and `pair`. Copy it to a stable path before pointing a service at it; `target/` is not a service location. See [deployment](deployment.md#updates).

## Build Android

From the repository root, with JDK 17+ and Android SDK 36:

```sh
cd android
./gradlew :app:assembleDebug
```

On Windows, run `gradlew.bat` instead of `./gradlew`. Set `sdk.dir` in `android/local.properties`, or set `ANDROID_HOME`. The debug APK is `android/app/build/outputs/apk/debug/app-debug.apk`.

Install that debug APK on a connected phone:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The debug APK uses a different signing key from the release app. To switch between them, uninstall the existing app first. This removes pairings stored on the phone; revoke the device on the host separately.

On some Windows/JDK setups Gradle fails with `Unable to establish loopback connection`. Create `C:/tmp` and set `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=C:/tmp`.

A release APK needs the signing key installed under `~/.pinkcollab-signing/`, or all four `ANDROID_*` environment variables described in [npm-release.md](npm-release.md). A partial environment does not override an installed key. With neither, the release build fails instead of producing an unsigned APK. End users should install the signed APK from GitHub Releases, not a developer build.

The app's `minSdk` is 26. `compileSdk` and `targetSdk` are 36.

## Validation

Gateway:

```sh
cd gateway
cargo fmt --check
cargo clippy --all-targets --all-features --locked -- -D warnings
cargo test --all-features --locked
cargo test --test omp_smoke -- --ignored
```

`omp_smoke` is `#[ignore]`d. It needs OMP installed, or `OMP_EXECUTABLE` set. `omp-fixture`, the deterministic stand-in for OMP, builds only with the `test-fixtures` feature.

Android, from `android/`:

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

npm launcher, from the repository root:

```sh
node npm/scripts/check-release.mjs
npm test --prefix npm/pinkcollab
npm pack ./npm/pinkcollab --dry-run
```

Website:

```sh
node .github/actions/check-website/check-site.mjs
```

That check has no dependencies. CI runs it in the `website` job, and a failure blocks the Pages deploy. Regenerate the social card with `node .github/actions/check-website/build-social-card.mjs` when the phone mockup changes. That command needs Chrome: `CHROME_PATH`, a Playwright install, or `google-chrome` / `chromium` on `PATH`.

## Project layout

```text
gateway/src/                     api · config · events · funnel · model · omp · session · storage · workspace · windows
android/app/src/main/java/dev/pinkcollab/   data · ui · ui/theme
npm/                             npm launcher · platform package manifests · release validation
website/                         GitHub Pages site: index.html · styles.css · app.js · assets
.github/actions/check-website/   static site check and the social-card builder
docs/                            further reading; install and first use are in the repository README
```

For API changes, see the [protocol](protocol.md).
