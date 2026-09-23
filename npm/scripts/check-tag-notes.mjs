import { execFileSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";

const tag = process.argv[2];
if (!/^v\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?$/.test(tag ?? "")) {
  throw new Error("Usage: node npm/scripts/check-tag-notes.mjs vX.Y.Z");
}

const notesPath = path.join("docs", "releases", `${tag}.md`);
const notes = fs.readFileSync(notesPath, "utf8");
if (!notes.trim()) {
  throw new Error(`${notesPath} must contain release notes`);
}

let tagObject;
try {
  tagObject = execFileSync("git", ["cat-file", "tag", `refs/tags/${tag}`], {
    encoding: "utf8",
    stdio: ["ignore", "pipe", "ignore"],
  });
} catch {
  throw new Error(`${tag} must be an annotated tag (git tag -a --cleanup=verbatim -F ${notesPath} ${tag})`);
}

const normalize = (text) => text.replace(/\r\n/g, "\n").trimEnd();
const messageStart = tagObject.indexOf("\n\n");
if (messageStart < 0 || normalize(tagObject.slice(messageStart + 2)) !== normalize(notes)) {
  throw new Error(`${tag} annotation must match ${notesPath}; create the tag with git tag -a --cleanup=verbatim -F`);
}

console.log(`${tag} annotation matches ${notesPath}.`);
