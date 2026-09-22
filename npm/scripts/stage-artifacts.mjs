import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const npmRoot = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "..",
);
const platforms = JSON.parse(
  fs.readFileSync(path.join(npmRoot, "pinkcollab", "platforms.json"), "utf8"),
);
const [mode, artifactsArgument, destinationArgument] = process.argv.slice(2);

if (
  !new Set(["packages", "release"]).has(mode) ||
  !artifactsArgument ||
  !destinationArgument
) {
  throw new Error(
    "Usage: node stage-artifacts.mjs <packages|release> <artifacts-dir> <destination-dir>",
  );
}

const artifactsRoot = path.resolve(artifactsArgument);
const destinationRoot = path.resolve(destinationArgument);

for (const platform of platforms) {
  const source = path.join(
    artifactsRoot,
    `gateway-${platform.id}`,
    platform.binary,
  );
  if (!fs.existsSync(source)) {
    throw new Error(`Missing build artifact: ${source}`);
  }

  const destination =
    mode === "packages"
      ? path.join(destinationRoot, platform.id, "bin", platform.binary)
      : path.join(
          destinationRoot,
          `pinkcollab-gateway-${platform.id}${platform.binary.endsWith(".exe") ? ".exe" : ""}`,
        );

  fs.mkdirSync(path.dirname(destination), { recursive: true });
  fs.copyFileSync(source, destination);
  if (!platform.binary.endsWith(".exe")) {
    fs.chmodSync(destination, 0o755);
  }
  console.log(`${platform.id}: ${destination}`);
}
