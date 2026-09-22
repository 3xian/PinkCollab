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
const platforms = readJson(path.join(npmRoot, "pinkcollab", "platforms.json"));

function readJson(file) {
  return JSON.parse(fs.readFileSync(file, "utf8"));
}

function fail(message) {
  throw new Error(message);
}

function assertUnique(field) {
  const values = platforms.map((platform) => platform[field]);
  if (new Set(values).size !== values.length) {
    fail(`Platform field ${field} contains duplicate values`);
  }
}

if (!Array.isArray(platforms) || platforms.length === 0) {
  fail("pinkcollab/platforms.json must contain at least one platform");
}
for (const field of ["id", "package", "target"]) {
  assertUnique(field);
}

const expectedDirectories = platforms.map((platform) => platform.id).sort();
const actualDirectories = fs
  .readdirSync(path.join(npmRoot, "platforms"), { withFileTypes: true })
  .filter((entry) => entry.isDirectory())
  .map((entry) => entry.name)
  .sort();
if (JSON.stringify(actualDirectories) !== JSON.stringify(expectedDirectories)) {
  fail(
    `Platform directories must exactly match platforms.json: expected ${expectedDirectories.join(", ")}; got ${actualDirectories.join(", ")}`,
  );
}

const mainManifest = readJson(path.join(npmRoot, "pinkcollab", "package.json"));
const version = mainManifest.version;

if (
  !/^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?$/.test(
    version,
  )
) {
  fail(`Invalid npm version: ${version}`);
}

const metadata = JSON.parse(
  execFileSync(
    "cargo",
    ["metadata", "--no-deps", "--format-version", "1", "--locked"],
    {
      cwd: gatewayRoot,
      encoding: "utf8",
    },
  ),
);
const gatewayPackage = metadata.packages.find(
  (candidate) => candidate.name === "pinkcollab-gateway",
);
if (!gatewayPackage) {
  fail("cargo metadata does not contain pinkcollab-gateway");
}
if (gatewayPackage.version !== version) {
  fail(
    `Version mismatch: pinkcollab-gateway=${gatewayPackage.version}, npm=${version}`,
  );
}

const expectedDependencies = new Map();
for (const platform of platforms) {
  for (const field of [
    "id",
    "package",
    "os",
    "cpu",
    "runner",
    "target",
    "binary",
  ]) {
    if (typeof platform[field] !== "string" || platform[field].length === 0) {
      fail(`Platform ${platform.id ?? "<unknown>"} is missing ${field}`);
    }
  }
  if (platform.id !== `${platform.os}-${platform.cpu}`) {
    fail(`${platform.id}: id must equal ${platform.os}-${platform.cpu}`);
  }

  const manifest = readJson(
    path.join(npmRoot, "platforms", platform.id, "package.json"),
  );
  if (manifest.name !== platform.package || manifest.version !== version) {
    fail(
      `${platform.id}: expected ${platform.package}@${version}, got ${manifest.name}@${manifest.version}`,
    );
  }
  if (manifest.os?.length !== 1 || manifest.os[0] !== platform.os) {
    fail(`${platform.id}: os must be ["${platform.os}"]`);
  }
  if (manifest.cpu?.length !== 1 || manifest.cpu[0] !== platform.cpu) {
    fail(`${platform.id}: cpu must be ["${platform.cpu}"]`);
  }
  expectedDependencies.set(platform.package, version);
}

const actualDependencies = new Map(
  Object.entries(mainManifest.optionalDependencies ?? {}),
);
if (actualDependencies.size !== expectedDependencies.size) {
  fail(
    "pinkcollab optionalDependencies do not exactly match the platform packages",
  );
}
for (const [packageName, expectedVersion] of expectedDependencies) {
  if (actualDependencies.get(packageName) !== expectedVersion) {
    fail(
      `pinkcollab optionalDependencies must contain ${packageName}@${expectedVersion}`,
    );
  }
}

const tag = process.argv[2] || process.env.GITHUB_REF_NAME;
if (tag && tag !== `v${version}`) {
  fail(`Release tag ${tag} does not match package version v${version}`);
}

console.log(
  `Release metadata is consistent at version ${version}${tag ? ` (${tag})` : ""}.`,
);
