# Development

Build and test PinkCollab from this repository. Installing a release APK or the npm Gateway does not require this page. Publishing a release is [npm-release.md](npm-release.md), not a step here.

## Contents

- [What you need](#what-you-need)
- [Build the Gateway](#build-the-gateway)
- [Build Android](#build-android)
- [Validation](#validation)
- [Project layout](#project-layout)

## What you need

| You are… | You need | You do not need |
| --- | --- | --- |
| Installing the release app and Gateway | The steps in the [README](../README.md) | Rust, a JDK, or the Android SDK |
| Changing the Android client | JDK 17 or newer, Android SDK 36, and a Gateway that speaks protocol 1 | To publish npm packages |
| Changing the Gateway | Rust 1.89+ | The Android SDK, unless you are also changing the app |
| Checking the website | Node.js 18 or newer, and Chrome or Chromium for the browser checks | A Rust compile, unless you are also changing the Gateway |
| Cutting a release | Maintainer credentials and the [release process](npm-release.md) | This page's local commands as a substitute for the workflow |

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

Windows ADB in this workspace is `F:/AndroidSDK/platform-tools/adb.exe` when it is not on `PATH`. The debug APK is not signed with the release key. Android will not install it over the release app. Uninstall the other signing first. That removes pairings stored on the phone; revoke the device on the host separately.

Phone operations after install — Workspaces, create, steer, Interrupt, Stop — belong in [usage](usage.md), not in this build section.

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

Next: [protocol](protocol.md) if the change crosses the HTTP boundary, or [npm-release.md](npm-release.md) if you are the person cutting a tag.
