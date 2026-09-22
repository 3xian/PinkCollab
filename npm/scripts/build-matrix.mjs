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

process.stdout.write(JSON.stringify({ include: platforms }));
