// Lighthouse gate for stable page contracts: accessibility, best practices,
// SEO, transfer sizes, and layout stability. The aggregate performance score is
// reported only as a diagnostic because it varies by host and between CI runs.
//
//   node tools/website/check-lighthouse.mjs
import lighthouse from 'lighthouse';
import puppeteer from 'puppeteer-core';

import { requireBrowser } from './browsers.mjs';
import { BASE, startServer } from './serve.mjs';

// Keep both shipped pages within the same document, transfer and layout budgets.
const ROUTES = [
  { name: 'home', path: `${BASE}/`, documentLimit: 60_000, totalLimit: 500_000, maxShift: 0.1 },
  { name: 'architecture', path: `${BASE}/architecture.html`, documentLimit: 20_000, totalLimit: 300_000, maxShift: 0.1 },
];

const MINIMUM_SCORES = [
  ['accessibility', 0.95],
  ['best-practices', 0.9],
  ['seo', 0.9],
];

const BYTE_BUDGETS = {
  script: 30_000,
  stylesheet: 60_000,
  image: 300_000,
  font: 120_000,
};

const { server, origin } = await startServer();
const browser = await puppeteer.launch({
  executablePath: requireBrowser(),
  args: ['--no-sandbox', '--disable-dev-shm-usage', '--remote-debugging-port=0'],
});

const failures = [];
let checks = 0;
try {
  const port = Number(new URL(browser.wsEndpoint()).port);
  for (const { name, path, documentLimit, totalLimit, maxShift } of ROUTES) {
    const result = await lighthouse(`${origin}${path}`, {
      port,
      output: 'json',
      preset: 'desktop',
      logLevel: 'error',
      onlyCategories: ['performance', 'accessibility', 'best-practices', 'seo'],
    });
    const { lhr } = result;
    console.log(`\n${name} (${path})`);
    for (const [label, minimum] of MINIMUM_SCORES) {
      const value = lhr.categories[label].score;
      checks += 1;
      const passed = Math.round(value * 100) >= Math.round(minimum * 100);
      if (!passed) failures.push(`${name}: ${label} scored ${Math.round(value * 100)}/100, below ${minimum * 100}`);
      console.log(`  ${passed ? 'ok  ' : 'FAIL'} ${label.padEnd(15)} ${String(Math.round(value * 100)).padStart(3)}/100 (min ${minimum * 100})`);
    }

    const shifts = lhr.audits['cumulative-layout-shift'].numericValue;
    checks += 1;
    if (shifts > maxShift) failures.push(`${name}: cumulative layout shift is ${shifts.toFixed(3)}, above ${maxShift}`);
    console.log(`  ${shifts <= maxShift ? 'ok  ' : 'FAIL'} layout shift    ${shifts.toFixed(3)} (max ${maxShift})`);

    const performance = lhr.categories.performance.score;
    console.log(`  info performance     ${String(Math.round(performance * 100)).padStart(3)}/100 (diagnostic)`);

    const sizes = new Map(
      (lhr.audits['resource-summary'].details?.items ?? []).map((item) => [item.resourceType, item.transferSize]),
    );
    for (const [type, limit] of [
      ['document', documentLimit],
      ...Object.entries(BYTE_BUDGETS),
      ['total', totalLimit],
    ]) {
      const measured = sizes.get(type) ?? 0;
      checks += 1;
      const passed = measured <= limit;
      if (!passed) failures.push(`${name}: ${type} transfer is ${measured} bytes, above ${limit}`);
      console.log(`  ${passed ? 'ok  ' : 'FAIL'} ${type.padEnd(15)} ${String(measured).padStart(7)} B (max ${limit})`);
    }
  }
} finally {
  await browser.close();
  server.close();
}

if (failures.length) {
  console.error(`\nbudget check failed (${failures.length} of ${checks} checks):`);
  for (const message of failures) console.error(`  - ${message}`);
  process.exit(1);
}
console.log(`\nbudget check passed (${checks} checks)`);
