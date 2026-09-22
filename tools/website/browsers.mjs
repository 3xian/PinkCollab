// Locates a Chromium/Chrome binary without downloading one: an explicit override
// first, then a Playwright browser cache, then the browser on PATH.
import { spawnSync } from 'node:child_process';
import { existsSync, readdirSync } from 'node:fs';
import { homedir } from 'node:os';
import { join } from 'node:path';

export function findBrowser() {
  const explicit = [
    process.env.CHROME_PATH,
    process.env.CHROME_BIN,
    process.env.PUPPETEER_EXECUTABLE_PATH,
    'C:/Program Files/Google/Chrome/Application/chrome.exe',
    'C:/Program Files (x86)/Google/Chrome/Application/chrome.exe',
    '/usr/bin/google-chrome',
    '/usr/bin/google-chrome-stable',
    '/usr/bin/chromium',
    '/usr/bin/chromium-browser',
    '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
    '/Applications/Chromium.app/Contents/MacOS/Chromium',
  ];
  for (const path of explicit) {
    if (path && existsSync(path)) return path;
  }

  const cacheRoot =
    process.env.PLAYWRIGHT_BROWSERS_PATH ||
    (process.env.LOCALAPPDATA && join(process.env.LOCALAPPDATA, 'ms-playwright')) ||
    (process.platform === 'darwin' && join(homedir(), 'Library/Caches/ms-playwright')) ||
    (process.platform === 'linux' && join(homedir(), '.cache/ms-playwright'));
  if (cacheRoot && existsSync(cacheRoot)) {
    const entries = readdirSync(cacheRoot);
    const builds = [
      ...entries.filter((entry) => /^chromium-\d/.test(entry)).sort().reverse(),
      ...entries.filter((entry) => /^chromium_headless_shell-\d/.test(entry)).sort().reverse(),
    ];
    const relative = [
      ['chrome-win64', 'chrome.exe'],
      ['chrome-win', 'chrome.exe'],
      ['chrome-headless-shell-win64', 'chrome-headless-shell.exe'],
      ['chrome-linux', 'chrome'],
      ['chrome-headless-shell-linux64', 'chrome-headless-shell'],
      ['chrome-mac', 'Chromium.app/Contents/MacOS/Chromium'],
    ];
    for (const build of builds) {
      for (const parts of relative) {
        const candidate = join(cacheRoot, build, ...parts);
        if (existsSync(candidate)) return candidate;
      }
    }
  }

  for (const command of ['google-chrome', 'google-chrome-stable', 'chromium', 'chromium-browser']) {
    if (spawnSync(command, ['--version'], { stdio: 'ignore' }).status === 0) return command;
  }
  return null;
}

export function requireBrowser() {
  const browser = findBrowser();
  if (!browser) {
    console.error('No Chromium/Chrome binary found. Set CHROME_PATH (or PUPPETEER_EXECUTABLE_PATH) and retry.');
    process.exit(1);
  }
  return browser;
}
