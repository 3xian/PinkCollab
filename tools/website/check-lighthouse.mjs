// Budget gate for the published page: Lighthouse categories plus the byte and
// stability budgets the site promises. Runs Lighthouse against a Chrome this
// machine already has, so contributors and CI measure the same thing.
//
//   node tools/website/check-lighthouse.mjs
import lighthouse from 'lighthouse';
import puppeteer from 'puppeteer-core';

import { requireBrowser } from './browsers.mjs';
import { BASE, startServer } from './serve.mjs';

// The landing page carries the strict budgets. architecture.html is one
// self-contained archify document (all markup, styles and the diagram inline), so
// it gets its own ceiling: it may be large, it may not grow without notice.
const ROUTES = [
  { name: 'home', path: `${BASE}/`, documentLimit: 60_000, totalLimit: 500_000, minPerformance: 0.8, maxShift: 0.1 },
  // architecture.html is archify output we do not author by hand, and it paints
  // from script on the first frame, so it shifts more on a slower runner: it may
  // be large and it may not grow without notice.
  { name: 'architecture', path: `${BASE}/architecture.html`, documentLimit: 900_000, totalLimit: 900_000, minPerformance: 0.55, maxShift: 0.2 },
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
  for (const { name, path, documentLimit, totalLimit, minPerformance, maxShift } of ROUTES) {
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
    checks += 1;
    if (performance < minPerformance) {
      failures.push(`${name}: performance scored ${Math.round(performance * 100)}/100, below ${minPerformance * 100}`);
    }
    console.log(`  ${performance >= minPerformance ? 'ok  ' : 'FAIL'} performance     ${String(Math.round(performance * 100)).padStart(3)}/100 (min ${minPerformance * 100})`);

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
