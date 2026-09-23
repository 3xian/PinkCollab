# npm release process

PinkCollab is distributed as one JavaScript launcher package plus five packages containing native Gateway binaries. End users never compile Rust during installation.

## One-time repository setup

1. Ensure the npm publisher can publish the public `pinkcollab` package and packages under the public `@pinkcollab` scope.
2. Add an npm publishing token as the GitHub Actions repository secret `NPM_TOKEN`.
3. Keep GitHub Actions enabled for tag pushes. The publish job uses GitHub's OIDC token to attach npm provenance to every package.
4. Store the Android signing key as the repository secrets
   `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`,
   `ANDROID_KEY_ALIAS`, and `ANDROID_KEY_PASSWORD`. Keep an offline backup of
   the keystore and credentials: losing them prevents upgrades over installed
   APKs.

## Cut a release

Update every Cargo, Android, and npm manifest in one validated operation, then push the matching tag:

```sh
node npm/scripts/set-version.mjs 0.1.0
node npm/scripts/check-release.mjs
git tag v0.1.0
git push origin v0.1.0
```

The release workflow rejects a tag that differs from the Cargo, Android, or npm package version. It runs the Gateway, Android, and launcher tests; builds a signed Android APK and five native Gateway binaries; installs the generated npm tarballs and launches each binary on its own runner; publishes the platform packages; publishes `pinkcollab` last; and only then creates an immutable GitHub Release containing the APK, binaries, and SHA-256 checksums.

Published package versions and GitHub Release assets are never overwritten or silently skipped. If npm publishing stops after only some platform packages were published, bump to a new version rather than rerunning the same tag. If all npm packages were published but GitHub Release creation failed, create the release from that run's artifacts and checksums instead of rebuilding or replacing binaries.

If no npm package or GitHub Release was published and the failed run's artifacts
are no longer available, recover the APK and five binaries from the immutable tag:

```sh
gh workflow run release.yml --ref main -f tag=v0.1.0
```

A manual dispatch checks out and validates the requested tag, rebuilds and smoke
tests every platform binary, builds and verifies the signed APK, skips npm
publication, and creates the GitHub Release with checksums. Use it only after
confirming that the tag has no release and none of its package versions exist
on npm.

If a first publication leaves new scoped packages private, use the npm website
to change each package's access to public, then verify it from an unauthenticated
registry client. Granular access tokens cannot change package visibility.

Linux packages use musl targets to avoid tying the binaries to the glibc version on the build runner. The macOS and Windows packages are built on native GitHub-hosted runners.
