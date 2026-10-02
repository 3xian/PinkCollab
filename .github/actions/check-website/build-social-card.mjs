// Rebuild the README, website hero, and 1200x630 social card from one approved
// source through the canonical branding generator. Requires Pillow and NumPy.
import { spawnSync } from 'node:child_process';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../../..');
const python = process.env.PYTHON || (process.platform === 'win32' ? 'python' : 'python3');
const result = spawnSync(python, [resolve(ROOT, 'scripts/update-logo.py'), '--promotional-only'], {
  stdio: 'inherit',
});
if (result.error) console.error(`Could not start the branding generator: ${result.error.message}`);
process.exit(result.status ?? 1);
