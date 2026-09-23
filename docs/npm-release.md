# Release Android, the Gateway, and npm

Maintainer handbook for publishing one Android APK, five native Gateway binaries, and the npm launcher. It is not an install guide. People installing PinkCollab should follow [Get started](getting-started.md). End users never compile Rust during installation.

Versions below are placeholders, not the current release.

## One-time repository setup

1. Ensure the npm publisher can publish the public `pinkcollab` package and packages under the public `@pinkcollab` scope.
2. Add an npm publishing token as the GitHub Actions repository secret `NPM_TOKEN`. Keep Actions enabled for tag pushes; publishing uses GitHub's OIDC token to attach npm provenance.
3. Store the Android signing key as the repository secrets `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, and `ANDROID_KEY_PASSWORD`. Keep an offline backup of the keystore and credentials: losing them prevents upgrades over installed APKs.
4. Install the same signing key on every machine that builds a release APK locally, as described in [Install the Android signing key on a build machine](#install-the-android-signing-key-on-a-build-machine).

## Install the Android signing key on a build machine

A local release build reads the signing key from the user home, not from the repository and not from the Gradle cache. Cutting a release needs no environment variables:

```text
~/.pinkcollab-signing/pinkcollab-release.p12
~/.pinkcollab-signing/pinkcollab-release.properties
```

On Windows `~` is `C:/Users/<you>`. Do not put the key under `~/.gradle`: deleting that directory to clear a Gradle cache would delete the key. The properties file holds the three remaining values, one per line:

```properties
ANDROID_KEYSTORE_PASSWORD=<store password>
ANDROID_KEY_ALIAS=pinkcollab
ANDROID_KEY_PASSWORD=<key password>
```

`android/app/build.gradle.kts` uses that directory unless all four `ANDROID_KEYSTORE_FILE`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, and `ANDROID_KEY_PASSWORD` environment variables are set. A partial environment does not override the installed key. A release APK or AAB with no usable key fails instead of being emitted unsigned. An unsigned artifact installs but cannot upgrade an installed one.

To install the key from the offline backup:

```sh
mkdir -p ~/.pinkcollab-signing
cp /path/to/pinkcollab-release.p12 ~/.pinkcollab-signing/
cp /path/to/pinkcollab-release.credentials ~/.pinkcollab-signing/pinkcollab-release.properties
```

Keep both files out of the repository. `.gitignore` covers `*.p12`, `*.jks`, `*.keystore`, `*.credentials`, and `pinkcollab-release.properties`. Restrict the directory to the account that builds releases.

## Cut a release

Update the Cargo, Android, and npm manifests together. From the user-visible changes since the previous release, write `docs/releases/vX.Y.Z.md` and commit it with that version bump. The same file becomes the annotated tag on GitHub's Tags page and the GitHub Release body.

The first line must say what changed, not only the version (`PinkCollab vX.Y.Z — faster reconnects on Android`). Follow it with highlights, fixes, and any compatibility or upgrade steps. Omit empty sections and unverified claims. Review the notes with the release requester before publishing.

```sh
node npm/scripts/set-version.mjs X.Y.Z
node npm/scripts/check-release.mjs
# Commit docs/releases/vX.Y.Z.md with the version changes.
git tag -a --cleanup=verbatim -F docs/releases/vX.Y.Z.md vX.Y.Z
node npm/scripts/check-tag-notes.mjs vX.Y.Z
git push origin main
git push origin vX.Y.Z
```

Do not use a lightweight tag or `git tag -m`. A new tag without committed notes, or whose annotation does not match that file, fails before the build. The workflow does not generate a commit list.

npm versions and GitHub Release assets are never overwritten. If publishing stops after only some platform packages, cut a new version. If every npm package was published but Release creation failed, create the Release from that run's artifacts and checksums instead of rebuilding binaries.

If nothing was published and those artifacts are gone:

```sh
gh workflow run release.yml --ref main -f tag=vX.Y.Z
```

That rebuilds from the tag, skips npm, and creates the Release. Use it only after confirming the tag has no Release and none of its package versions exist on npm. Tags from before versioned notes still get generated Release notes; a tag that has a notes file must match its annotation.

If a first publication leaves new scoped packages private, change each package to public on the npm website, then verify from an unauthenticated registry client. Granular access tokens cannot change visibility.

Linux packages use musl so they are not tied to the runner's glibc. macOS and Windows packages are built on native runners.

Build and test commands for local development stay in [development](development.md). Do not treat this workflow as a way to install a Gateway.
