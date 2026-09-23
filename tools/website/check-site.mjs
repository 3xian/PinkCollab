// Static integrity checks for the GitHub Pages site: every link resolves, every
// asset exists, metadata agrees with the repository, and install and build
// text still matches the authoritative docs. No dependencies.
//
//   node tools/website/check-site.mjs
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, '../..');
const SITE = join(ROOT, 'website');
// GitHub Pages serves this project site under a path prefix.
const BASE = '/PinkCollab';
const ORIGIN = 'https://3xian.github.io';
const PAGES = ['index.html', '404.html'];
const INDEXABLE = ['index.html'];
const NAV_MAX_BYTES = 260_000;

const failures = [];
let checks = 0;

function fail(message) {
  failures.push(message);
}

function check(condition, message) {
  checks += 1;
  if (!condition) fail(message);
}

const siteFile = (path) => join(SITE, path);
const readSite = (path) => readFileSync(siteFile(path), 'utf8');
const read = (path) => readFileSync(join(ROOT, path), 'utf8');

function idsOf(html) {
  return new Set([...html.matchAll(/\sid="([^"]+)"/g)].map((m) => m[1]));
}

function pngSize(file) {
  const bytes = readFileSync(file);
  if (bytes.length < 24 || bytes.readUInt32BE(0) !== 0x89504e47) return null;
  return { width: bytes.readUInt32BE(16), height: bytes.readUInt32BE(20) };
}

// GitHub's heading slug rules, close enough for anchor checking.
function headingsOf(markdown) {
  return new Set(
    markdown
      .split('\n')
      .filter((line) => /^#{1,6}\s/.test(line))
      .map((line) =>
        line
          .replace(/^#{1,6}\s+/, '')
          .trim()
          .toLowerCase()
          .replace(/[^\p{L}\p{N}\s-]/gu, '')
          .replace(/\s+/g, '-'),
      ),
  );
}

function normalizeCommand(line) {
  return line.replace(/\\\s*$/, '').replace(/\s+/g, ' ').trim();
}

// --- Site structure -------------------------------------------------------

const html = Object.fromEntries(PAGES.map((page) => [page, readSite(page)]));
const ids = Object.fromEntries(PAGES.map((page) => [page, idsOf(html[page])]));
const referenced = new Set();
const loaded = new Set();
const inlineAnchors = [];

for (const page of PAGES) {
  const source = html[page];
  check(/<html[^>]+lang="[a-z-]+"/.test(source), `${page}: missing <html lang>`);
  check(/<meta charset="utf-8">/i.test(source), `${page}: missing charset`);
  check(/name="viewport"/.test(source), `${page}: missing viewport meta`);
  check(/<title>[^<]+<\/title>/.test(source), `${page}: missing title`);
  check(
    /name="description" content="[^"]{20,}"/.test(source),
    `${page}: missing description`,
  );
  for (const attribute of source.matchAll(/\bid="([^"]+)"/g)) {
    const marker = `id="${attribute[1]}"`.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    const duplicates = (source.match(new RegExp(`\\s${marker}`, 'g')) ?? []).length;
    check(duplicates === 1, `${page}: duplicate id "${attribute[1]}"`);
  }

  for (const match of source.matchAll(/\s(?:href|src)="([^"]+)"/g)) {
    const url = match[1];
    if (/^(https?:|mailto:|data:|tel:)/.test(url)) continue;
    if (url.startsWith('#')) {
      inlineAnchors.push([page, page, url.slice(1)]);
      continue;
    }
    let path = url.split('#')[0];
    const hash = url.includes('#') ? url.slice(url.indexOf('#') + 1) : '';
    if (path.startsWith('/')) {
      check(page === '404.html', `${page}: root-absolute path "${url}" breaks on a project page`);
      check(
        path === BASE || path.startsWith(`${BASE}/`),
        `${page}: "${url}" must keep the ${BASE}/ base path`,
      );
      path = path.slice(BASE.length + 1);
    }
    if (path.endsWith('/')) path += 'index.html';
    const target = path === '' ? 'index.html' : path;
    check(existsSync(siteFile(target)), `${page}: "${url}" does not resolve to a file`);
    if (target.startsWith('assets/')) {
      referenced.add(target);
      loaded.add(target);
    }
    if (hash && target.endsWith('.html') && ids[target]) {
      check(ids[target].has(hash), `${page}: "${url}" has no matching id in ${target}`);
    }
    if (target.endsWith('.html') && page === target) referencesSelfAnchors(source, hash, page);
  }

  for (const match of source.matchAll(/<img\b[^>]*>/g)) {
    check(/\balt="/.test(match[0]), `${page}: <img> without alt: ${match[0].slice(0, 60)}`);
    const src = /\bsrc="([^"]+)"/.exec(match[0]);
    if (src && src[1].startsWith('assets/')) {
      referenced.add(src[1]);
      loaded.add(src[1]);
    }
  }
}

function referencesSelfAnchors(source, hash, page) {
  if (hash) check(idsOf(source).has(hash), `${page}: "#${hash}" has no matching id`);
}

for (const [from, to, hash] of inlineAnchors) {
  check(ids[to].has(hash), `${from}: "#${hash}" has no matching id in ${to}`);
}

// --- Social card and icons ------------------------------------------------

const ogImage = /property="og:image" content="([^"]+)"/.exec(html['index.html']);
const twitterImage = /name="twitter:image" content="([^"]+)"/.exec(html['index.html']);
check(Boolean(ogImage), 'index.html: missing og:image');
check(Boolean(twitterImage), 'index.html: missing twitter:image');
if (ogImage && twitterImage) {
  check(ogImage[1] === twitterImage[1], 'index.html: twitter:image must match og:image');
  const url = new URL(ogImage[1]);
  check(url.origin === ORIGIN, `index.html: og:image must be served from ${ORIGIN}`);
  check(url.pathname.startsWith(`${BASE}/`), `index.html: og:image must keep the ${BASE}/ base path`);
  const file = siteFile(url.pathname.slice(BASE.length + 1));
  referenced.add(url.pathname.slice(BASE.length + 1));
  check(existsSync(file), `index.html: og:image file ${url.pathname} is missing`);
  if (existsSync(file)) {
    const size = pngSize(file);
    check(size?.width === 1200 && size?.height === 630, `og:image must be 1200x630, got ${JSON.stringify(size)}`);
  }
}

check(
  /name="twitter:card" content="summary_large_image"/.test(html['index.html']),
  'index.html: twitter:card should be summary_large_image',
);

// --- Assets --------------------------------------------------------------

const assets = readdirSync(join(SITE, 'assets'));
for (const asset of assets) {
  const bytes = statSync(join(SITE, 'assets', asset)).size;
  check(bytes <= NAV_MAX_BYTES, `assets/${asset} is ${bytes} bytes, over the ${NAV_MAX_BYTES} budget`);
}
for (const asset of assets) {
  check(referenced.has(`assets/${asset}`), `assets/${asset} is never referenced`);
}

const navWeight = ['index.html', 'styles.css', 'app.js']
  .map((file) => statSync(siteFile(file)).size)
  .reduce((total, size) => total + size, 0) +
  [...loaded]
    .filter((path) => existsSync(siteFile(path)))
    .map((path) => statSync(siteFile(path)).size)
    .reduce((total, size) => total + size, 0);
check(navWeight <= 400_000, `index page weight is ${navWeight} bytes`);
console.log(`index page weight: ${(navWeight / 1024).toFixed(1)} KB`);

// --- sitemap and robots --------------------------------------------------

const sitemap = readSite('sitemap.xml');
const locations = [...sitemap.matchAll(/<loc>([^<]+)<\/loc>/g)].map((m) => m[1]);
check(locations.length > 0, 'sitemap.xml: no <loc> entries');
for (const location of locations) {
  const url = new URL(location);
  check(url.origin === ORIGIN, `sitemap.xml: ${location} is not on ${ORIGIN}`);
  check(url.pathname.startsWith(`${BASE}/`), `sitemap.xml: ${location} must keep the ${BASE}/ base path`);
  const path = url.pathname.slice(BASE.length + 1) || 'index.html';
  check(existsSync(siteFile(path)), `sitemap.xml: ${location} does not exist`);
}
for (const page of INDEXABLE) {
  check(
    locations.some((location) => location.endsWith(page === 'index.html' ? `${BASE}/` : page)),
    `sitemap.xml: ${page} is missing`,
  );
}
check(!locations.some((location) => location.endsWith('404.html')), 'sitemap.xml: 404.html must not be listed');

const robots = readSite('robots.txt');
check(/^User-agent: \*$/m.test(robots), 'robots.txt: missing "User-agent: *"');
check(robots.includes(`${ORIGIN}${BASE}/sitemap.xml`), 'robots.txt: sitemap URL is missing or wrong');

// --- stylesheet and font loading ----------------------------------------

const css = readSite('styles.css');
check(!/@import/.test(css), 'styles.css: use a <link> in the document head instead of @import');
for (const page of PAGES.filter((page) => html[page].includes('styles.css'))) {
  check(
    /preconnect" href="https:\/\/fonts\.gstatic\.com"/.test(html[page]),
    `${page}: fonts should be preconnected`,
  );
  check(
    /fonts\.googleapis\.com\/css2\?family=/.test(html[page]),
    `${page}: loads styles.css without its font stylesheet`,
  );
}

// --- Docs and install consistency ----------------------------------------

function anchorsOf(markdown) {
  const anchors = headingsOf(markdown);
  for (const match of markdown.matchAll(/\sid="([^"]+)"/g)) anchors.add(match[1]);
  return anchors;
}

function markdownTarget(fromFile, target) {
  const [pathPart, hash] = target.split('#');
  if (!pathPart) return { file: fromFile, hash };
  return { file: resolve(ROOT, dirname(fromFile), pathPart), hash };
}

function checkMarkdownFile(file) {
  const text = read(file);
  const anchors = anchorsOf(text);
  for (const match of text.matchAll(/\]\(([^)\s]+)\)/g)) {
    const target = match[1];
    if (/^(?:https?:|mailto:)/.test(target)) continue;
    const { file: linked, hash } = markdownTarget(file, target);
    if (linked === file) {
      if (hash) check(anchors.has(hash), `${file}: "#${hash}" does not match any heading or id`);
      continue;
    }
    check(existsSync(linked), `${file}: missing link target ${target}`);
    if (hash && existsSync(linked) && linked.endsWith('.md')) {
      check(
        anchorsOf(readFileSync(linked, 'utf8')).has(hash),
        `${file}: ${target} has no matching heading or id`,
      );
    }
  }
}

const readme = read('README.md');
checkMarkdownFile('README.md');
for (const name of readdirSync(join(ROOT, 'docs'))) {
  if (name.endsWith('.md')) checkMarkdownFile(join('docs', name));
}

for (const page of PAGES) {
  for (const match of html[page].matchAll(/blob\/main\/(README\.md|docs\/[^"#]+)#([^"]+)"/g)) {
    const file = match[1];
    check(existsSync(join(ROOT, file)), `${page}: missing ${file}`);
    if (existsSync(join(ROOT, file))) {
      check(
        anchorsOf(read(file)).has(match[2]),
        `${page}: ${file}#${match[2]} does not match any heading or id`,
      );
    }
  }
}

const cliSource = read('gateway/src/main.rs');
const enumBody = cliSource.slice(cliSource.indexOf('enum Commands {'));
const subcommands = new Set(
  [...enumBody.matchAll(/^ {4}([A-Z][A-Za-z]*)\s*[{,]/gm)].map((m) =>
    m[1].replace(/([a-z0-9])([A-Z])/g, '$1-$2').toLowerCase(),
  ),
);
check(subcommands.size >= 6, `gateway/src/main.rs: parsed only ${subcommands.size} subcommands`);

function usedSubcommands(text, label) {
  for (const match of text.matchAll(
    /(?:^|\s)(?:pinkcollab|\.\/target\/release\/pinkcollab-gateway|pinkcollab-gateway)[ \t]+([a-z][a-z-]+)/g,
  )) {
    check(subcommands.has(match[1]), `${label}: unknown gateway subcommand "${match[1]}"`);
  }
}
usedSubcommands(html['index.html'], 'index.html');
usedSubcommands(readme, 'README.md');
for (const name of readdirSync(join(ROOT, 'docs'))) {
  if (name.endsWith('.md')) usedSubcommands(read(join('docs', name)), `docs/${name}`);
}

function commandLines(text) {
  return text
    .split('\n')
    .map(normalizeCommand)
    .filter((line) => /^(?:pinkcollab|npm|cargo|npx|\.\/target)/.test(line));
}

const installDoc = read('docs/getting-started.md');
const buildDoc = read('docs/development.md');
for (const block of html['index.html'].matchAll(/<code id="command-[^"]+">([\s\S]*?)<\/code>/g)) {
  const lines = block[1]
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&amp;/g, '&')
    .split('\n')
    .map(normalizeCommand)
    .filter((line) => /^(?:pinkcollab|npm|cargo|npx)/.test(line));
  for (const line of lines) {
    const authority = /^cargo\b/.test(line) ? buildDoc : installDoc;
    const label = /^cargo\b/.test(line) ? 'docs/development.md' : 'docs/getting-started.md';
    check(
      commandLines(authority).includes(line),
      `index.html command "${line}" is not documented in ${label}`,
    );
  }
}

const rustVersion = /rust-version\s*=\s*"([\d.]+)"/.exec(read('gateway/Cargo.toml'))?.[1];
check(Boolean(rustVersion), 'gateway/Cargo.toml: no rust-version to compare against');
if (rustVersion) {
  for (const [label, source, required] of [
    ['index.html', html['index.html'], true],
    ['docs/development.md', buildDoc, true],
    ['README.md', readme, false],
  ]) {
    const claimed = [...source.matchAll(/Rust (\d+\.\d+)\+/g)].map((m) => m[1]);
    if (required) check(claimed.length > 0, `${label}: does not state the required Rust version`);
    for (const version of claimed) {
      check(version === rustVersion, `${label}: claims Rust ${version}+ but Cargo.toml requires ${rustVersion}`);
    }
  }
  for (const name of readdirSync(join(ROOT, 'docs'))) {
    if (!name.endsWith('.md') || name === 'development.md') continue;
    const source = read(join('docs', name));
    for (const version of [...source.matchAll(/Rust (\d+\.\d+)\+/g)].map((m) => m[1])) {
      check(version === rustVersion, `docs/${name}: claims Rust ${version}+ but Cargo.toml requires ${rustVersion}`);
    }
  }
}

// --- Result --------------------------------------------------------------

if (failures.length) {
  console.error(`\nwebsite check failed (${failures.length} of ${checks} checks):`);
  for (const message of failures) console.error(`  - ${message}`);
  process.exit(1);
}
console.log(`website check passed (${checks} checks)`);
