#!/usr/bin/env node

"use strict";

const path = require("node:path");
const { spawn } = require("node:child_process");
const PLATFORMS = require("../platforms.json");

const PLATFORM_BY_ID = new Map(
  PLATFORMS.map((platform) => [platform.id, platform]),
);

function resolveGatewayBinary(
  platform = process.platform,
  arch = process.arch,
  resolvePackage = require.resolve,
) {
  const target = `${platform}-${arch}`;
  const definition = PLATFORM_BY_ID.get(target);

  if (!definition) {
    const supported = PLATFORMS.map(({ id }) => id).join(", ");
    throw new Error(
      `PinkCollab does not provide a Gateway binary for ${target}. ` +
        `Supported platforms: ${supported}.`,
    );
  }

  let manifestPath;
  try {
    manifestPath = resolvePackage(`${definition.package}/package.json`);
  } catch (error) {
    const detail = error instanceof Error ? `\n${error.message}` : "";
    throw new Error(
      `The optional platform package ${definition.package} is not installed. ` +
        "Reinstall with optional dependencies enabled, for example: " +
        "npm install -g pinkcollab --include=optional" +
        detail,
    );
  }

  return path.join(path.dirname(manifestPath), "bin", definition.binary);
}

function run(binaryPath, args = process.argv.slice(2), spawnChild = spawn) {
  const child = spawnChild(binaryPath, args, { stdio: "inherit" });
  const signals = ["SIGINT", "SIGTERM"];
  const handlers = new Map();

  for (const signal of signals) {
    const handler = () => {
      try {
        child.kill(signal);
      } catch {
        // The child may already have exited between the signal and this handler.
      }
    };
    handlers.set(signal, handler);
    process.once(signal, handler);
  }

  const removeSignalHandlers = () => {
    for (const [signal, handler] of handlers) {
      process.removeListener(signal, handler);
    }
  };

  child.once("error", (error) => {
    removeSignalHandlers();
    console.error(
      `pinkcollab: failed to start ${binaryPath}: ${error.message}`,
    );
    process.exitCode = 1;
  });

  child.once("exit", (code, signal) => {
    removeSignalHandlers();
    if (signal) {
      try {
        process.kill(process.pid, signal);
      } catch {
        process.exitCode = 1;
      }
      return;
    }
    process.exitCode = code ?? 1;
  });

  return child;
}

function main() {
  try {
    run(resolveGatewayBinary());
  } catch (error) {
    console.error(
      `pinkcollab: ${error instanceof Error ? error.message : error}`,
    );
    process.exitCode = 1;
  }
}

if (require.main === module) {
  main();
}

module.exports = { PLATFORMS, resolveGatewayBinary, run };
