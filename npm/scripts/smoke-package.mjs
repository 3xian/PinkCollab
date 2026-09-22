import { execFileSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const npmRoot = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "..",
);
const platforms = JSON.parse(
  fs.readFileSync(path.join(npmRoot, "pinkcollab", "platforms.json"), "utf8"),
);
const [platformId, binaryArgument] = process.argv.slice(2);
const platform = platforms.find((candidate) => candidate.id === platformId);

if (!platform || !binaryArgument) {
  throw new Error("Usage: node smoke-package.mjs <platform-id> <binary-path>");
}
if (platform.os !== process.platform || platform.cpu !== process.arch) {
  throw new Error(
    `${platformId} must be tested on ${platform.os}-${platform.cpu}, not ${process.platform}-${process.arch}`,
  );
}

const temporaryRoot = fs.mkdtempSync(
  path.join(os.tmpdir(), "pinkcollab-package-"),
);
const npmCommand = process.platform === "win32" ? "npm.cmd" : "npm";

function execute(command, args, options = {}) {
  if (process.platform === "win32") {
    return execFileSync(
      process.env.ComSpec ?? "cmd.exe",
      ["/d", "/s", "/c", command, ...args],
      options,
    );
  }
  return execFileSync(command, args, options);
}

function pack(packageDirectory, packDirectory) {
  const output = execute(
    npmCommand,
    [
      "pack",
      packageDirectory,
      "--pack-destination",
      packDirectory,
      "--loglevel=error",
    ],
    { encoding: "utf8" },
  );
  const filename = output.trim().split(/\r?\n/).at(-1);
  if (!filename?.endsWith(".tgz")) {
    throw new Error(`npm pack did not return a tarball name:\n${output}`);
  }
  return path.join(packDirectory, filename);
}

try {
  const mainDirectory = path.join(temporaryRoot, "pinkcollab");
  const platformDirectory = path.join(temporaryRoot, platform.id);
  const packDirectory = path.join(temporaryRoot, "packs");
  const installDirectory = path.join(temporaryRoot, "install");
  fs.cpSync(path.join(npmRoot, "pinkcollab"), mainDirectory, {
    recursive: true,
  });
  fs.cpSync(path.join(npmRoot, "platforms", platform.id), platformDirectory, {
    recursive: true,
  });
  fs.mkdirSync(path.join(platformDirectory, "bin"), { recursive: true });
  fs.mkdirSync(packDirectory, { recursive: true });
  fs.copyFileSync(
    path.resolve(binaryArgument),
    path.join(platformDirectory, "bin", platform.binary),
  );
  if (process.platform !== "win32") {
    fs.chmodSync(path.join(platformDirectory, "bin", platform.binary), 0o755);
  }

  const platformTarball = pack(platformDirectory, packDirectory);
  const mainTarball = pack(mainDirectory, packDirectory);
  execute(
    npmCommand,
    [
      "install",
      "--prefix",
      installDirectory,
      "--ignore-scripts",
      "--no-audit",
      "--no-fund",
      platformTarball,
      mainTarball,
    ],
    { stdio: "inherit" },
  );

  const launcher = path.join(
    installDirectory,
    "node_modules",
    ".bin",
    process.platform === "win32" ? "pinkcollab.cmd" : "pinkcollab",
  );
  execute(launcher, ["--version"], { stdio: "inherit" });
  console.log(
    `Installed tarballs and launched pinkcollab successfully on ${platformId}.`,
  );
} finally {
  fs.rmSync(temporaryRoot, { recursive: true, force: true });
}
