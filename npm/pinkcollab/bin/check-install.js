#!/usr/bin/env node
"use strict";

const fs = require("node:fs");
const path = require("node:path");

function findShadowedInstall({
  env = process.env,
  platform = process.platform,
  cwd = process.cwd(),
  launcher = path.join(__dirname, "pinkcollab.js"),
} = {}) {
  // npm lifecycle scripts replace PATH with package-local entries and do not
  // expose the original PATH. Only a caller-run check can inspect shell lookup.
  if (env.npm_lifecycle_event) {
    throw new Error("npm lifecycle scripts change PATH; run this script directly with node from your shell after npm finishes");
  }

  const windows = platform === "win32";
  const extensions = windows
    ? (env.PATHEXT || ".COM;.EXE;.BAT;.CMD").split(";").map((extension) => extension.toLowerCase())
    : [""];
  const installedLauncher = fs.realpathSync(launcher);
  const prefix = windows
    ? fs.realpathSync(env.npm_config_prefix || path.resolve(path.dirname(launcher), "../../.."))
    : null;
  for (const directory of (env.PATH || "").split(windows ? ";" : ":")) {
    for (const extension of extensions) {
      const candidate = path.resolve(cwd, directory || ".", `pinkcollab${extension}`);
      let resolved;
      try {
        if (!fs.statSync(candidate).isFile()) continue;
        fs.accessSync(candidate, windows ? fs.constants.F_OK : fs.constants.X_OK);
        resolved = fs.realpathSync(candidate);
      } catch {
        continue;
      }
      const current = windows
        ? extension === ".cmd" && path.dirname(resolved).toLowerCase() === prefix.toLowerCase()
        : resolved === installedLauncher;
      return current ? null : { candidate, launcher: installedLauncher };
    }
  }
  return null;
}

function main() {
  try {
    const shadow = findShadowedInstall();
    if (!shadow) return;
    console.warn(
      "pinkcollab: WARNING: another installation takes precedence on PATH.\n" +
      `  Shell command: ${shadow.candidate}\n` +
      `  Newly installed npm launcher: ${shadow.launcher}\n` +
      "Running pinkcollab setup may still run the other installation.\n" +
      "Run the new launcher explicitly with node, or correct PATH before setup.\n" +
      "Remove an older npm installation only using its original global prefix.\n" +
      "Then run setup or service install to update the background Gateway.\n" +
      "Gateway updates can stop active OMP runtimes; sessions and pairings remain.",
    );
  } catch (error) {
    // Report diagnostic failures without changing PATH or other installations.
    console.warn(`pinkcollab: could not check PATH: ${error.message}`);
  }
}

if (require.main === module) main();
module.exports = { findShadowedInstall };
