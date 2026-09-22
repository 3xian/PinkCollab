import { execFileSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const npmRoot = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "..",
);
const repositoryRoot = path.resolve(npmRoot, "..");
const gatewayRoot = path.join(repositoryRoot, "gateway");
const version = process.argv[2];
const versionMatch =
  /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?$/.exec(
    version ?? "",
  );
if (!versionMatch) {
  throw new Error("Usage: node set-version.mjs <semver>");
}
const [, major, minor, patch] = versionMatch.map(Number);
if (minor > 999 || patch > 999) {
  throw new Error("Android version codes require semver minor and patch values below 1000");
}
const androidVersionCode = major * 1_000_000 + minor * 1_000 + patch;
if (androidVersionCode > 2_100_000_000) {
  throw new Error("Android version code exceeds the Play Store limit");
}

const platformConfigPath = path.join(npmRoot, "pinkcollab", "platforms.json");
const platforms = JSON.parse(fs.readFileSync(platformConfigPath, "utf8"));
const files = [
  path.join(gatewayRoot, "Cargo.toml"),
  path.join(gatewayRoot, "Cargo.lock"),
  path.join(repositoryRoot, "android", "app", "build.gradle.kts"),
  path.join(npmRoot, "pinkcollab", "package.json"),
  ...platforms.map((platform) =>
    path.join(npmRoot, "platforms", platform.id, "package.json"),
  ),
];
const originals = new Map(files.map((file) => [file, fs.readFileSync(file)]));

function writeJson(file, value) {
  fs.writeFileSync(file, `${JSON.stringify(value, null, 2)}\n`);
}

try {
  const cargoTomlPath = path.join(gatewayRoot, "Cargo.toml");
  const cargoToml = fs.readFileSync(cargoTomlPath, "utf8");
  const packageVersionPattern =
    /(^\[package\][\s\S]*?^version\s*=\s*")[^"]+("\s*$)/m;
  if (!packageVersionPattern.test(cargoToml)) {
    throw new Error(
      "Could not update the [package] version in gateway/Cargo.toml",
    );
  }
  const updatedCargoToml = cargoToml.replace(
    packageVersionPattern,
    `$1${version}$2`,
  );
  fs.writeFileSync(cargoTomlPath, updatedCargoToml);

  const androidBuildPath = path.join(
    repositoryRoot,
    "android",
    "app",
    "build.gradle.kts",
  );
  const androidBuild = fs.readFileSync(androidBuildPath, "utf8");
  if (!/versionCode = \d+/.test(androidBuild) || !/versionName = "[^"]+"/.test(androidBuild)) {
    throw new Error("Could not update Android version metadata");
  }
  fs.writeFileSync(
    androidBuildPath,
    androidBuild
      .replace(/versionCode = \d+/, `versionCode = ${androidVersionCode}`)
      .replace(/versionName = "[^"]+"/, `versionName = "${version}"`),
  );

  const mainManifestPath = path.join(npmRoot, "pinkcollab", "package.json");
  const mainManifest = JSON.parse(fs.readFileSync(mainManifestPath, "utf8"));
  mainManifest.version = version;
  mainManifest.optionalDependencies = Object.fromEntries(
    platforms.map((platform) => [platform.package, version]),
  );
  writeJson(mainManifestPath, mainManifest);

  for (const platform of platforms) {
    const manifestPath = path.join(
      npmRoot,
      "platforms",
      platform.id,
      "package.json",
    );
    const manifest = JSON.parse(fs.readFileSync(manifestPath, "utf8"));
    manifest.version = version;
    writeJson(manifestPath, manifest);
  }

  execFileSync("cargo", ["update", "--offline", "-p", "pinkcollab-gateway"], {
    cwd: gatewayRoot,
    stdio: "ignore",
  });
  execFileSync(
    process.execPath,
    [path.join(npmRoot, "scripts", "check-release.mjs")],
    {
      cwd: repositoryRoot,
      stdio: "inherit",
    },
  );
  console.log(`Updated every release manifest to ${version}.`);
} catch (error) {
  for (const [file, contents] of originals) {
    fs.writeFileSync(file, contents);
  }
  throw error;
}
