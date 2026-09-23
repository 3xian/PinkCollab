// Renders website/assets/social-card.png (1200x630) for og:image / twitter:image.
//
// The card is built from the shipped stylesheet and the real phone mockup lifted
// out of website/index.html, so it can never drift from the brand. Run it with a
// Chromium/Chrome binary available (CHROME_PATH, a Playwright install, or PATH):
//
//   node .github/actions/check-website/build-social-card.mjs
import { spawnSync } from 'node:child_process';
import { existsSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { requireBrowser } from './browsers.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const SITE = resolve(HERE, '../../../website');
const OUT = join(SITE, 'assets', 'social-card.png');
const WIDTH = 1200;
const HEIGHT = 630;

// Balanced <div class="phone"> ... </div>, so the card never copies mockup markup.
function phoneMarkup(html) {
  const start = html.indexOf('<div class="phone">');
  if (start < 0) throw new Error('no <div class="phone"> mockup in website/index.html');
  const tag = /<\/?div\b[^>]*>/g;
  tag.lastIndex = start;
  let depth = 0;
  for (let match = tag.exec(html); match; match = tag.exec(html)) {
    depth += match[0][1] === '/' ? -1 : 1;
    if (depth === 0) return html.slice(start, tag.lastIndex);
  }
  throw new Error('unbalanced mockup markup in website/index.html');
}

function cardHtml(phone) {
  return `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<title>PinkCollab social card</title>
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=DM+Sans:wght@400..700&family=Manrope:wght@400..800&display=swap">
<link rel="stylesheet" href="styles.css">
<style>
html,body{width:${WIDTH}px;height:${HEIGHT}px;margin:0;overflow:hidden}
body{background:var(--dots),var(--page);color:var(--text-primary);font-family:var(--font)}
.card{position:relative;display:grid;grid-template-columns:1fr 470px;height:${HEIGHT}px;padding-left:76px}
.card-copy{align-self:end;padding-bottom:74px}
.card-brand{position:absolute;left:76px;top:52px;display:flex;align-items:center;gap:14px;font-family:var(--display);font-weight:800;font-size:31px;letter-spacing:-1.4px}
.card-brand img{width:52px;height:52px;border-radius:13px}
.card-eyebrow{font-size:13px;letter-spacing:2.4px;font-weight:700;color:#c2a7dc}
.card h1{font-family:var(--display);font-size:72px;line-height:1.04;letter-spacing:-3.4px;font-weight:800;margin:20px 0 0;max-width:600px}
.card h1 span{color:var(--purple);background:linear-gradient(105deg,#8360dd 8%,#c069c9 75%);background-clip:text;-webkit-background-clip:text;-webkit-text-fill-color:transparent}
.card-sub{font-size:21px;line-height:1.6;color:var(--text-secondary);margin:26px 0 0;max-width:520px}
.card-url{font-size:18px;color:#c6a9e0;margin-top:32px}
.card-scene{position:relative;height:${HEIGHT}px;overflow:hidden;background:radial-gradient(ellipse 780px 700px at 58% 42%,#7a45bb5c,transparent 70%),radial-gradient(ellipse 620px 560px at 30% 62%,#d55abf38,transparent 68%)}
.card-scene .phone{position:absolute;left:58px;top:56px;transform:rotate(-6deg) scale(1.35);transform-origin:top left;box-shadow:0 40px 90px -26px #000}
</style>
</head>
<body>
<div class="card">
  <div class="card-brand"><img src="assets/logo-96.png" width="52" height="52" alt="">PinkCollab<span class="brand-dot">.</span></div>
  <div class="card-copy">
    <p class="card-eyebrow">RUNS ON HOSTS YOU OWN · OPEN SOURCE</p>
    <h1>Start and steer <span>Oh My Pi</span> from your phone.</h1>
    <p class="card-sub">Spawn, prompt, steer, and stop OMP coding sessions over a gateway that only your phone can reach.</p>
    <p class="card-url">3xian.github.io/PinkCollab</p>
  </div>
  <div class="card-scene">${phone}</div>
</div>
</body>
</html>
`;
}

const browser = requireBrowser();

const html = readFileSync(join(SITE, 'index.html'), 'utf8');
// The temp page lives beside index.html so relative stylesheet and asset paths resolve.
const temp = join(SITE, '.social-card.tmp.html');
writeFileSync(temp, cardHtml(phoneMarkup(html)));

let status = 1;
try {
  const result = spawnSync(
    browser,
    [
      '--headless=new',
      '--disable-gpu',
      '--no-first-run',
      '--no-default-browser-check',
      '--hide-scrollbars',
      '--force-device-scale-factor=1',
      `--window-size=${WIDTH},${HEIGHT}`,
      '--virtual-time-budget=10000',
      `--screenshot=${OUT}`,
      `file:///${temp.replaceAll('\\', '/')}`,
    ],
    { stdio: 'inherit' },
  );
  status = result.status ?? 1;
} finally {
  rmSync(temp, { force: true });
}

if (status !== 0 || !existsSync(OUT)) {
  console.error('Social card rendering failed.');
  process.exit(1);
}
console.log(`Wrote ${OUT}`);
