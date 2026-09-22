// Shared static server for the behaviour and Lighthouse checks: serves website/
// under the /PinkCollab/ base path and mirrors the GitHub Pages 404 behaviour.
import { createReadStream, existsSync, readFileSync, statSync } from 'node:fs';
import http from 'node:http';
import { dirname, extname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

export const BASE = '/PinkCollab';
export const SITE = resolve(dirname(fileURLToPath(import.meta.url)), '../../website');

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.png': 'image/png',
  '.xml': 'application/xml; charset=utf-8',
  '.txt': 'text/plain; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
};

export function startServer() {
  const server = http.createServer((request, response) => {
    let pathname = decodeURIComponent(new URL(request.url, 'http://localhost').pathname);
    if (pathname === BASE) {
      response.writeHead(301, { location: `${BASE}/` });
      return response.end();
    }
    pathname = pathname.startsWith(`${BASE}/`) ? pathname.slice(BASE.length + 1) : pathname.slice(1);
    let file = join(SITE, pathname);
    if (pathname === '' || pathname.endsWith('/')) file = join(file, 'index.html');
    if (!existsSync(file) || statSync(file).isDirectory()) {
      response.writeHead(404, { 'content-type': MIME['.html'] });
      return response.end(readFileSync(join(SITE, '404.html')));
    }
    response.writeHead(200, { 'content-type': MIME[extname(file)] ?? 'application/octet-stream' });
    createReadStream(file).pipe(response);
  });
  return new Promise((done) => {
    server.listen(0, '127.0.0.1', () => done({ server, origin: `http://127.0.0.1:${server.address().port}` }));
  });
}
