// Behavioural checks for the GitHub Pages site: layout at real viewport widths,
// the demo tabs, the copy fallback, contrast, and the Pages base path.
//
//   node tools/website/check-behavior.mjs
import puppeteer from 'puppeteer-core';

import { requireBrowser } from './browsers.mjs';
import { BASE, startServer } from './serve.mjs';

const failures = [];
let checks = 0;
function check(condition, message) {
  checks += 1;
  if (!condition) failures.push(message);
}

const VIEWPORTS = [1440, 1200, 961, 960, 900, 820, 480, 360];

// Same rules the page uses, minus the illustrative phone (an image of an app, so
// WCAG's incidental-text exception applies) and gradient text clipping.
const CONTRAST_SCRIPT = `(() => {
  const lum = (c) => { const f = c.map(v => { v /= 255; return v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4; }); return 0.2126 * f[0] + 0.7152 * f[1] + 0.0722 * f[2]; };
  const parse = (s) => { const m = String(s).match(/rgba?\\(([^)]+)\\)/); if (!m) return null; const p = m[1].split(/[,\\s/]+/).filter(Boolean).map(Number); return { rgb: p.slice(0, 3), a: p.length > 3 ? p[3] : 1 }; };
  const flat = (bg, fg) => bg.map((c, i) => c * fg.a + fg.rgb[i] * (1 - fg.a));
  const bgOf = (el) => {
    let node = el, acc = null;
    while (node) {
      const cs = getComputedStyle(node);
      const c = parse(cs.backgroundColor);
      if (c && c.a > 0) { acc = acc ? flat(acc, c) : c.rgb; if (c.a > 0.98) return acc; }
      if (cs.backgroundImage !== 'none' && /gradient/.test(cs.backgroundImage)) {
        const stops = [...cs.backgroundImage.matchAll(/rgba?\\(([^)]+)\\)/g)].map(m => parse('rgb(' + m[1] + ')').rgb);
        if (stops.length) return acc ? flat(acc, { rgb: stops[stops.length - 1], a: 1 }) : stops[stops.length - 1];
      }
      node = node.parentElement;
    }
    return acc || [0, 0, 0];
  };
  const out = [];
  for (const el of document.querySelectorAll('body *')) {
    if (el.closest('.hero-scene') || el.closest('[aria-hidden="true"]')) continue;
    const text = [...el.childNodes].filter(n => n.nodeType === 3 && n.textContent.trim()).map(n => n.textContent.trim()).join(' ');
    if (!text) continue;
    const cs = getComputedStyle(el);
    if (cs.display === 'none' || cs.visibility === 'hidden' || el.offsetParent === null) continue;
    if (cs.webkitBackgroundClip === 'text' || cs.backgroundClip === 'text') continue;
    const fg = parse(cs.color);
    if (!fg) continue;
    const bg = bgOf(el);
    const l1 = lum(fg.rgb), l2 = lum(bg);
    const ratio = (Math.max(l1, l2) + 0.05) / (Math.min(l1, l2) + 0.05);
    const size = parseFloat(cs.fontSize), weight = Number(cs.fontWeight) || 400;
    const need = (size >= 24 || (size >= 18.66 && weight >= 700)) ? 3 : 4.5;
    if (ratio < need) out.push(el.tagName + '.' + String(el.className).split(' ')[0] + ' ' + Math.round(ratio * 100) / 100 + '<' + need);
  }
  return out;
})()`;

const { server, origin } = await startServer();
const browser = await puppeteer.launch({
  executablePath: requireBrowser(),
  args: ['--no-sandbox', '--disable-dev-shm-usage', '--force-device-scale-factor=1'],
});

const externalFailures = [];
try {
  const page = await browser.newPage();
  const pageErrors = [];
  const failedRequests = [];
  page.on('pageerror', (error) => pageErrors.push(String(error)));
  page.on('requestfailed', (request) => {
    const url = request.url();
    if (/fonts\.(googleapis|gstatic)\.com/.test(url)) externalFailures.push(url);
    else failedRequests.push(`${url} (${request.failure()?.errorText})`);
  });

  await page.setViewport({ width: 1440, height: 900 });
  const response = await page.goto(`${origin}${BASE}/`, { waitUntil: 'networkidle2' });
  check(response.status() === 200, `home page responded ${response.status()}`);
  await page.evaluate(() => document.fonts.ready);
  check(pageErrors.length === 0, `console errors: ${pageErrors.join(' | ')}`);
  check(failedRequests.length === 0, `failed requests: ${failedRequests.join(' | ')}`);

  for (const width of VIEWPORTS) {
    await page.setViewport({ width, height: 900 });
    await page.evaluate(() => new Promise((r) => requestAnimationFrame(() => r())));
    const metrics = await page.evaluate(() => {
      const flow = document.querySelector('.architecture-flow');
      const labels = [...document.querySelectorAll('.arch-connection span')].map((el) => ({
        label: el.getBoundingClientRect().width,
        cell: el.closest('.arch-connection').getBoundingClientRect().width,
        gap: parseFloat(getComputedStyle(el.closest('.architecture-flow')).columnGap) || 0,
      }));
      // Any text box that cannot fit its own text is being clipped, not wrapped.
      const clipped = [];
      for (const el of document.querySelectorAll('p, li, dd, dt, h1, h2, h3, span, strong, a, figcaption, code')) {
        if (!el.textContent.trim() || el.closest('.hero-scene') || el.offsetParent === null) continue;
        if (getComputedStyle(el).overflowX !== 'visible') continue;
        const lost = el.scrollWidth - el.clientWidth;
        if (lost > 1 && el.clientWidth > 0) clipped.push(`${el.tagName}.${String(el.className).split(' ')[0]} loses ${lost}px`);
      }
      return {
        clipped,
        overflow: document.documentElement.scrollWidth - window.innerWidth,
        columns: getComputedStyle(flow).gridTemplateColumns.split(' ').length,
        labels,
        preSize: parseFloat(getComputedStyle(document.querySelector('#command-source')).fontSize),
        tabsSize: parseFloat(getComputedStyle(document.querySelector('.task-tab')).fontSize),
        navLinks: [...document.querySelectorAll('nav a')].filter((a) => a.offsetParent).length,
      };
    });
    check(metrics.overflow <= 0, `${width}px: page scrolls horizontally by ${metrics.overflow}px`);
    check(
      width >= 961 ? metrics.columns === 5 : metrics.columns === 1,
      `${width}px: architecture flow has ${metrics.columns} columns`,
    );
    for (const { label, cell, gap } of metrics.labels) {
      check(
        label <= cell + gap,
        `${width}px: architecture connector label (${Math.round(label)}px) overflows its ${Math.round(cell)}px column`,
      );
    }
    check(metrics.clipped.length === 0, `${width}px: clipped text: ${metrics.clipped.slice(0, 4).join(', ')}`);
    check(metrics.preSize >= 14, `${width}px: install command text is ${metrics.preSize}px`);
    check(metrics.tabsSize >= 11, `${width}px: demo tabs are ${metrics.tabsSize}px`);
    check(metrics.navLinks >= 2, `${width}px: only ${metrics.navLinks} navigation links are reachable`);
  }

  // Demo tabs: pointer and keyboard, including the ARIA tab pattern.
  await page.setViewport({ width: 1440, height: 900 });
  const tabBehaviour = await page.evaluate(async () => {
    const tabs = [...document.querySelectorAll('.task-tab')];
    const panel = document.querySelector('#demo-tasks');
    const titles = () => [...panel.querySelectorAll('h3')].map((h) => h.textContent);
    const out = { perTab: [] };
    for (const tab of tabs) {
      tab.click();
      await new Promise((r) => setTimeout(r, 30));
      out.perTab.push({
        selected: tabs.map((t) => t.getAttribute('aria-selected')),
        tabIndex: tabs.map((t) => t.tabIndex),
        labelledby: panel.getAttribute('aria-labelledby'),
        titles: titles(),
      });
    }
    tabs[0].focus();
    tabs[0].dispatchEvent(new KeyboardEvent('keydown', { key: 'End', bubbles: true }));
    await new Promise((r) => setTimeout(r, 30));
    out.afterEnd = { active: document.activeElement.id, selected: tabs.map((t) => t.getAttribute('aria-selected')) };
    tabs[2].dispatchEvent(new KeyboardEvent('keydown', { key: 'Home', bubbles: true }));
    await new Promise((r) => setTimeout(r, 30));
    out.afterHome = { active: document.activeElement.id, selected: tabs.map((t) => t.getAttribute('aria-selected')) };
    return out;
  });
  tabBehaviour.perTab.forEach((state, index) => {
    check(
      state.selected.filter((value) => value === 'true').length === 1 && state.selected[index] === 'true',
      `tab ${index}: aria-selected is ${JSON.stringify(state.selected)}`,
    );
    check(
      state.tabIndex.filter((value) => value === 0).length === 1 && state.tabIndex[index] === 0,
      `tab ${index}: roving tabindex is ${JSON.stringify(state.tabIndex)}`,
    );
    check(state.labelledby === `tab-${['attention', 'running', 'recent'][index]}`, `tab ${index}: panel labelledby is ${state.labelledby}`);
    check(state.titles.length > 0, `tab ${index}: panel rendered no tasks`);
  });
  check(new Set(tabBehaviour.perTab.map((state) => state.titles.join())).size === 3, 'tabs do not render distinct task lists');
  check(tabBehaviour.afterEnd.active === 'tab-recent', `End key focused "${tabBehaviour.afterEnd.active}"`);
  check(tabBehaviour.afterHome.active === 'tab-attention', `Home key focused "${tabBehaviour.afterHome.active}"`);

  // Copy button: the clipboard path, then the documented fallback.
  const copy = await page.evaluate(async () => {
    const status = document.querySelector('#copy-status');
    const button = document.querySelectorAll('.copy-button')[0];
    button.click();
    await new Promise((r) => setTimeout(r, 150));
    const withClipboard = status.textContent;
    const selected = String(window.getSelection());
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: undefined });
    document.querySelectorAll('.copy-button')[1].click();
    await new Promise((r) => setTimeout(r, 150));
    return { withClipboard, selected, withoutClipboard: status.textContent };
  });
  check(copy.withClipboard.includes('Build from source'), `copy reported "${copy.withClipboard}"`);
  check(copy.selected.includes('cargo build'), 'copy did not select the commands as a fallback');
  check(copy.withoutClipboard.length > 0, 'copy fallback said nothing');

  // Contrast, at desktop and phone widths.
  for (const width of [1440, 390]) {
    await page.setViewport({ width, height: 900 });
    const offenders = await page.evaluate(CONTRAST_SCRIPT);
    check(offenders.length === 0, `${width}px contrast failures: ${offenders.slice(0, 6).join(', ')}`);
  }

  // Skip link: reachable, and moved on screen once focused.
  const client = await page.target().createCDPSession();
  await client.send('Emulation.setFocusEmulationEnabled', { enabled: true });
  const skip = await page.evaluate(() => {
    const link = document.querySelector('.skip-link');
    link.focus();
    const style = getComputedStyle(link);
    const box = link.getBoundingClientRect();
    return { top: Math.round(box.top), height: Math.round(box.height), background: style.backgroundColor, color: style.color };
  });
  await client.send('Emulation.setFocusEmulationEnabled', { enabled: false });
  check(skip.top === 15, `focused skip link sits at ${skip.top}px`);
  check(skip.height >= 44, `skip link target is ${skip.height}px tall`);

  // Social card has to be servable at the URL the metadata advertises.
  const card = await page.evaluate(async (base) => {
    const image = new Image();
    const loaded = new Promise((done) => {
      image.onload = () => done(true);
      image.onerror = () => done(false);
    });
    image.src = `${base}/assets/social-card.png`;
    await loaded;
    return { ok: image.naturalWidth > 0, width: image.naturalWidth, height: image.naturalHeight };
  }, BASE);
  check(card.ok && card.width === 1200 && card.height === 630, `social card decoded as ${card.width}x${card.height}`);

  // Unknown paths must serve the styled 404 page with the base path intact.
  const missing = await page.goto(`${origin}${BASE}/docs/does-not-exist`, { waitUntil: 'load' });
  check(missing.status() === 404, `missing page responded ${missing.status()}`);
  const notFound = await page.evaluate(() => {
    const home = document.querySelector('.not-found .button-primary');
    const logo = document.querySelector('.brand img');
    return {
      title: document.title,
      home: home?.getAttribute('href'),
      logoLoaded: Boolean(logo?.complete && logo.naturalWidth > 0),
      styled: getComputedStyle(document.body).backgroundColor,
    };
  });
  check(notFound.title.includes('Page not found'), `404 title is "${notFound.title}"`);
  check(notFound.home?.startsWith(BASE), `404 home link is "${notFound.home}"`);
  check(notFound.logoLoaded, '404 page did not load its logo (base path broken)');

  await page.close();
} finally {
  await browser.close();
  server.close();
}

if (externalFailures.length) {
  console.warn(`warning: ${externalFailures.length} font requests failed (offline?)`);
}
if (failures.length) {
  console.error(`\nbehaviour check failed (${failures.length} of ${checks} checks):`);
  for (const message of failures) console.error(`  - ${message}`);
  process.exit(1);
}
console.log(`behaviour check passed (${checks} checks)`);
