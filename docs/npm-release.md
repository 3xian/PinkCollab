# Release Android, the Gateway, and npm

Maintainer handbook for publishing one Android APK, five native Gateway binaries, and the npm launcher. It is not an install guide. People installing PinkCollab should follow the [README](../README.md). End users never compile Rust during installation.

Versions below are placeholders, not the current release.

## One-time repository setup

1. Ensure the npm publisher can publish the public `pinkcollab` package and packages under the public `@pinkcollab` scope.
2. Add an npm publishing token as the GitHub Actions repository secret `NPM_TOKEN`. Keep Actions enabled for tag pushes; publishing uses GitHub's OIDC token to attach npm provenance.
3. Store the Android signing key as the repository secrets `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, and `ANDROID_KEY_PASSWORD`. Keep an offline backup of the keystore and credentials: losing them prevents upgrades over installed APKs.
4. Install the same signing key on every machine that builds a release APK locally, as described in [Install the Android signing key on a build machine](#install-the-android-signing-key-on-a-build-machine).

## Install the Android signing key on a build machine

A local release build uses these files in the build account's home directory; no signing environment variables are needed:

```text
~/.pinkcollab-signing/pinkcollab-release.p12
~/.pinkcollab-signing/pinkcollab-release.properties
```

On Windows `~` is `C:/Users/<you>`. The properties file holds the three remaining values, one per line:

```properties
ANDROID_KEYSTORE_PASSWORD=<store password>
ANDROID_KEY_ALIAS=pinkcollab
ANDROID_KEY_PASSWORD=<key password>
```

`android/app/build.gradle.kts` uses these files unless all four `ANDROID_KEYSTORE_FILE`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, and `ANDROID_KEY_PASSWORD` environment variables are set. A partial environment does not override the installed key. Release APK/AAB builds require a usable key; APK upgrades must use the same signing certificate as the installed app.

To install the key from the offline backup:

```sh
mkdir -p ~/.pinkcollab-signing
cp /path/to/pinkcollab-release.p12 ~/.pinkcollab-signing/
cp /path/to/pinkcollab-release.credentials ~/.pinkcollab-signing/pinkcollab-release.properties
```

Keep both files outside the repository and `~/.gradle` so cache cleanup cannot delete the key. Restrict access to the build account. `.gitignore` covers the signing files, but is not a substitute for storing them outside the checkout.

## Cut a release

Update the Cargo, Android, and npm manifests together. From the user-visible changes since the previous release, write `docs/releases/vX.Y.Z.md` and commit it with that version bump. The same file becomes the annotated tag on GitHub's Tags page and the GitHub Release body.

The first line must say what changed, not only the version (`PinkCollab vX.Y.Z: large model catalogs load without truncation`). Follow it with highlights, fixes, and any compatibility or upgrade steps. Omit empty sections and unverified claims. Review the notes with the release requester before publishing.

Run from the repository root on `main`, with unrelated changes kept out of the release commit. The version script updates all manifests and the Cargo lockfile, then validates them:

```sh
node npm/scripts/set-version.mjs X.Y.Z
# Write and review docs/releases/vX.Y.Z.md before committing.
git add gateway/Cargo.toml gateway/Cargo.lock android/app/build.gradle.kts npm/pinkcollab/package.json npm/platforms/*/package.json docs/releases/vX.Y.Z.md
git commit -m "chore(release): X.Y.Z"
git tag -a --cleanup=verbatim -F docs/releases/vX.Y.Z.md vX.Y.Z
node npm/scripts/check-tag-notes.mjs vX.Y.Z
git push origin main
git push origin vX.Y.Z
```

Use the `-F` command above: the tag must be annotated and its annotation must match the committed notes file. Missing or mismatched notes fail before the build; the workflow does not generate a commit list for new releases.

For a prerelease, use the same suffix in every manifest version, the notes filename, and the annotated tag (for example, `1.2.3-rc.1` and `docs/releases/v1.2.3-rc.1.md`). Stable releases publish all six npm packages to `latest`; prereleases publish to `next` without changing `latest`. Suffix tags become GitHub prereleases and are excluded from `/releases/latest`, which the Android updater uses.

## Verify the published release

A successful Actions run is not sufficient. Before declaring the release complete:

1. Inspect the GitHub Release: correct tag, reviewed notes, stable/prerelease status, and all seven assets (APK, five Gateway binaries, and `SHA256SUMS`).
2. Download every asset and verify the six payloads against `SHA256SUMS` (`sha256sum -c SHA256SUMS` in the download directory). For a stable release, also confirm `/releases/latest` points to the intended version.
3. Check `pinkcollab` and every package listed in `npm/pinkcollab/platforms.json` using an unauthenticated registry client. All six exact versions must be publicly downloadable, with `latest` pointing to a stable release or `next` pointing to this prerelease. A prerelease must leave `latest` unchanged.
4. In a disposable environment with no npm credentials, empty user/global npm configuration, a fresh cache, and an empty installation directory, install `pinkcollab@X.Y.Z` from `https://registry.npmjs.org` and run its installed `pinkcollab --version`. Confirm the expected version and native platform package; do not preinstall a platform tarball or reuse a global installation. Also verify installation via the intended dist-tag (`latest` or `next`).

CI smoke-tests local tarballs on all five platforms before publishing; that does not prove registry visibility, dist-tags, or a clean registry install. If a package is private, correct its visibility in npm package settings and repeat the unauthenticated checks.

## Recover a failed release

Never overwrite npm versions or existing GitHub Release assets, or move a published tag. Check all six exact npm versions and the GitHub Release before choosing a recovery path:

| Published state | Recovery |
| --- | --- |
| Only some npm packages exist | Cut a new version; do not rerun publication of the same version. |
| All six npm packages exist; no GitHub Release; complete original artifacts available | Create the Release using the original run's APK and Gateway artifacts, matching notes, prerelease status, and checksums. If checksums were not produced, generate them from those original bytes. Do not rebuild. |
| All npm packages exist; no GitHub Release; complete original artifacts unavailable | Cut a new version; there is no documented safe same-version recovery. |
| No npm versions or GitHub Release exist | Retry the original run's failed jobs only after confirming nothing was published, or cut a new version. For assets-only recovery, reuse complete original artifacts if available; otherwise use the dispatch command below. |
| GitHub Release already exists | Do not replace its assets. Repair visibility/dist-tags only if the existing packages are correct; binary changes require a new version. |

For an **assets-only** rebuild, after confirming no Release, none of the six npm versions exist, and the original artifacts are unavailable:

```sh
gh workflow run release.yml --ref main -f tag=vX.Y.Z
```

This rebuilds from the existing tag and creates the GitHub Release, but **always skips npm publication**. It does not complete an npm release; use a new version if npm distribution is required. Tags from before versioned notes receive generated Release notes; tags with a notes file must match their annotation.

### Workflow scheduling and caches

npm publication does not wait for Android, so an Android failure can leave published npm packages without a GitHub Release. Check the published state before retrying.

Avoid overlapping runs for the same tag; Release has no concurrency group. Default-branch cache warming is optional and affects build time, not correctness: every release builds and smoke-tests its own Gateway artifacts.

Build and test commands for local development stay in [development](development.md). Do not treat this workflow as a way to install a Gateway.
