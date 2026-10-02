"use strict";

const assert = require("node:assert/strict");
const { spawnSync } = require("node:child_process");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const test = require("node:test");
const { findShadowedInstall } = require("../bin/check-install.js");

function installation(t) {
  const root = fs.realpathSync(fs.mkdtempSync(path.join(os.tmpdir(), "pinkcollab-install-")));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  const oldBin = path.join(root, "old", "bin");
  const newBin = path.join(root, "new", "bin");
  fs.mkdirSync(oldBin, { recursive: true });
  fs.mkdirSync(newBin, { recursive: true });
  const launcher = path.join(root, "pinkcollab.js");
  fs.writeFileSync(launcher, "#!/usr/bin/env node\n", { mode: 0o755 });
  return { root, oldBin, newBin, launcher };
}

const posix = { skip: process.platform === "win32" };

test("reports the older prefix that actually wins PATH without changing it", posix, (t) => {
  const { oldBin, newBin, launcher } = installation(t);
  const older = path.join(oldBin, "pinkcollab");
  fs.writeFileSync(older, "older installation", { mode: 0o755 });
  fs.symlinkSync(launcher, path.join(newBin, "pinkcollab"));
  const env = { PATH: `${oldBin}:${newBin}` };
  assert.deepEqual(findShadowedInstall({ env, platform: "darwin", launcher }), {
    candidate: older, launcher,
  });
  assert.equal(fs.readFileSync(older, "utf8"), "older installation");
  assert.equal(findShadowedInstall({ env: { ...env, PATH: `${newBin}:${oldBin}` }, platform: "darwin", launcher }), null);
});

test("recognizes the installed launcher through an alternate symlink", posix, (t) => {
  const { oldBin, launcher } = installation(t);
  const alias = path.join(oldBin, "pinkcollab");
  fs.symlinkSync(launcher, alias);
  assert.equal(findShadowedInstall({ env: { PATH: oldBin }, platform: "linux", launcher }), null);
});

test("ignores non-executable files and directories before the installed command", posix, (t) => {
  const { oldBin, newBin, launcher } = installation(t);
  const obstruction = path.join(oldBin, "pinkcollab");
  fs.writeFileSync(obstruction, "not executable", { mode: 0o644 });
  fs.symlinkSync(launcher, path.join(newBin, "pinkcollab"));
  const options = { env: { PATH: `${oldBin}:${newBin}` }, platform: "linux", launcher };
  assert.equal(findShadowedInstall(options), null);
  fs.unlinkSync(obstruction);
  fs.mkdirSync(obstruction);
  assert.equal(findShadowedInstall(options), null);
});

test("does not mistake npm-injected ancestor bins for the caller PATH", posix, (t) => {
  const { root, newBin, launcher } = installation(t);
  const injectedBin = path.join(root, "node_modules", ".bin");
  fs.mkdirSync(injectedBin, { recursive: true });
  fs.writeFileSync(path.join(injectedBin, "pinkcollab"), "ancestor installation", { mode: 0o755 });
  fs.symlinkSync(launcher, path.join(newBin, "pinkcollab"));
  const env = {
    npm_config_global: "true",
    npm_lifecycle_event: "postinstall",
    INIT_CWD: root,
    PATH: `${injectedBin}:${newBin}`,
  };
  assert.throws(() => findShadowedInstall({ env, cwd: path.dirname(launcher), platform: "linux", launcher }),
    /npm lifecycle scripts change PATH/);
  assert.equal(findShadowedInstall({ env: { PATH: newBin }, cwd: root, platform: "linux", launcher }), null);
  const result = spawnSync(process.execPath, [path.resolve(__dirname, "../bin/check-install.js")], {
    cwd: path.dirname(launcher), env, encoding: "utf8",
  });
  assert.equal(result.status, 0, result.stderr);
  assert.match(result.stderr, /npm lifecycle scripts change PATH/);
  assert.doesNotMatch(result.stderr, /another installation takes precedence/);
});

test("resolves relative and empty PATH entries from the caller directory", posix, (t) => {
  const { root, oldBin, newBin, launcher } = installation(t);
  fs.symlinkSync(launcher, path.join(newBin, "pinkcollab"));
  const older = path.join(oldBin, "pinkcollab");
  fs.writeFileSync(older, "older installation", { mode: 0o755 });
  const options = { cwd: root, platform: "linux", launcher };
  assert.deepEqual(findShadowedInstall({ ...options, env: { PATH: `old/bin:${newBin}` } }), {
    candidate: older, launcher,
  });
  const local = path.join(root, "pinkcollab");
  fs.writeFileSync(local, "caller-local installation", { mode: 0o755 });
  assert.deepEqual(findShadowedInstall({ ...options, env: { PATH: `:${newBin}` } }), {
    candidate: local, launcher,
  });
  for (const entry of ["old/bin", ""]) {
    const result = spawnSync(process.execPath, [path.resolve(__dirname, "../bin/check-install.js")], {
      cwd: root,
      env: { PATH: `${entry}:${newBin}` },
      encoding: "utf8",
    });
    assert.equal(result.status, 0, result.stderr);
    assert.match(result.stderr, /another installation takes precedence/);
    assert.ok(result.stderr.includes(`Shell command: ${entry ? older : local}`), result.stderr);
  }
});

test("detects Windows command shims in another prefix and respects PATHEXT precedence", (t) => {
  const { oldBin, newBin, launcher } = installation(t);
  fs.writeFileSync(path.join(oldBin, "pinkcollab.cmd"), "older shim");
  fs.writeFileSync(path.join(newBin, "pinkcollab.cmd"), "current shim");
  const env = { npm_config_prefix: newBin, PATHEXT: ".EXE;.CMD", PATH: `${oldBin};${newBin}` };
  assert.equal(findShadowedInstall({ env, platform: "win32", launcher }).candidate, path.join(oldBin, "pinkcollab.cmd"));
  const currentEnv = { ...env, PATH: `${newBin};${oldBin}` };
  assert.equal(findShadowedInstall({ env: currentEnv, platform: "win32", launcher }), null);
  fs.writeFileSync(path.join(newBin, "pinkcollab.exe"), "standalone binary");
  assert.equal(findShadowedInstall({ env: currentEnv, platform: "win32", launcher }).candidate, path.join(newBin, "pinkcollab.exe"));
});
