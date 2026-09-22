"use strict";

const assert = require("node:assert/strict");
const path = require("node:path");
const test = require("node:test");
const {
  PLATFORMS,
  resolveGatewayBinary,
  run,
} = require("../bin/pinkcollab.js");

test("maps every supported Node platform to its binary package", () => {
  for (const definition of PLATFORMS) {
    const { id: target, package: packageName, binary: executable } = definition;
    const separator = target.lastIndexOf("-");
    const platform = target.slice(0, separator);
    const arch = target.slice(separator + 1);
    const manifest = path.join("virtual", packageName, "package.json");
    let requested;

    const binary = resolveGatewayBinary(platform, arch, (specifier) => {
      requested = specifier;
      return manifest;
    });

    assert.equal(requested, `${packageName}/package.json`);
    assert.equal(binary, path.join(path.dirname(manifest), "bin", executable));
  }
});

test("rejects unsupported platforms before resolving a package", () => {
  assert.throws(
    () =>
      resolveGatewayBinary("freebsd", "x64", () =>
        assert.fail("resolver should not run"),
      ),
    /does not provide a Gateway binary for freebsd-x64/,
  );
});

test("explains how to restore an omitted optional dependency", () => {
  assert.throws(
    () =>
      resolveGatewayBinary("linux", "x64", () => {
        throw new Error("module not found");
      }),
    /npm install -g pinkcollab --include=optional/,
  );
});

test("forwards arguments and child exit status", async () => {
  const originalExitCode = process.exitCode;
  process.exitCode = undefined;

  const child = new (require("node:events").EventEmitter)();
  child.kill = () => true;
  let invocation;
  run(
    "/virtual/pinkcollab-gateway",
    ["status", "--data-dir", "/tmp/data"],
    (binary, args, options) => {
      invocation = { binary, args, options };
      return child;
    },
  );

  child.emit("exit", 23, null);
  await new Promise((resolve) => setImmediate(resolve));

  assert.deepEqual(invocation, {
    binary: "/virtual/pinkcollab-gateway",
    args: ["status", "--data-dir", "/tmp/data"],
    options: { stdio: "inherit" },
  });
  assert.equal(process.exitCode, 23);
  process.exitCode = originalExitCode;
});
