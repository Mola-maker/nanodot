import { test } from 'node:test';
import assert from 'node:assert/strict';
import { access, mkdtemp, mkdir, writeFile, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash, randomUUID } from 'node:crypto';
import { spawn } from 'node:child_process';
import { setTimeout as delay } from 'node:timers/promises';

const root = fileURLToPath(new URL('../../', import.meta.url));
test('actual local Worker assets serve missing pages as 404 and keep API errors no-store JSON', { timeout: 60000 }, async () => {
  const builder = join(root, 'scripts/site/build.mjs');
  assert.equal(await access(builder).then(() => true, () => false), true, 'site build interface is missing');
  const { buildSite } = await import(builder);
  const dir = await mkdtemp(join(tmpdir(), 'nanodot-routes-'));
  let worker;
  let output = '';
  try {
    const docsDir = join(dir, 'docs');
    const outDir = join(dir, 'public');
    await mkdir(docsDir);
    await writeFile(join(docsDir, 'index.html'), '<h1>Docs</h1>');
    await buildSite({ outDir, docsDir });
    const config = JSON.parse(await readFile(join(root, 'gateway/wrangler.jsonc'), 'utf8'));
    config.main = join(root, 'gateway/src/index.ts');
    config.assets.directory = outDir;
    delete config.ai; // Wrangler cannot emulate AI; absent binding fails closed without remote calls.
    await writeFile(join(dir, 'wrangler.json'), JSON.stringify(config));
    // Port 0 asks the OS for an isolated ephemeral listening socket.
    worker = spawn(process.execPath, [join(root, 'gateway/node_modules/wrangler/bin/wrangler.js'), 'dev', '--config', join(dir, 'wrangler.json'), '--local', '--port', '0'], { cwd: dir, env: { ...process.env, WRANGLER_SEND_METRICS: 'false' }, stdio: ['ignore', 'pipe', 'pipe'] });
    worker.stdout.on('data', b => { output += b; });
    worker.stderr.on('data', b => { output += b; });
    let origin;
    for (let i = 0; i < 200; i++) {
      origin = output.match(/Ready on (http:\/\/[^\s]+)/)?.[1];
      if (origin) break;
      if (worker.exitCode !== null) break;
      await delay(100);
    }
    assert.ok(origin, output);
    for (const path of ['/', '/connect/', '/privacy', '/docs/']) assert.equal((await fetch(`${origin}${path}`)).status, 200, path);
    const missing = await fetch(`${origin}/definitely-missing`);
    assert.equal(missing.status, 404);
    assert.match(await missing.text(), /404/);
    for (const path of ['/v1', '/v1/models', '/v1/missing', '/healthz']) {
      const response = await fetch(`${origin}${path}`, { headers: { Accept: 'text/html', 'Sec-Fetch-Mode': 'navigate' } });
      assert.equal(response.status, 503, path);
      assert.match(response.headers.get('content-type'), /application\/json/);
      assert.equal(response.headers.get('cache-control'), 'no-store');
      assert.doesNotMatch(await response.text(), /<!doctype|<html/i);
    }
    // Exercise the same production handler with a local AI shape that throws if called.
    // Real ASSETS and Durable Objects remain present; discovery/auth cannot infer.
    worker.kill('SIGTERM');
    await new Promise(resolve => worker.once('exit', resolve));
    output = '';
    const credential = randomUUID();
    const entry = join(dir, 'configured.ts');
    await writeFile(entry, `import { handle, FreeGate } from ${JSON.stringify(join(root, 'gateway/src/index.ts'))}; export { FreeGate }; export default { fetch(request, env, ctx) { return handle(request, { ...env, AI: { run() { throw new Error('Inference forbidden in site route test'); } } }, ctx); } };`);
    config.main = entry;
    config.vars.GATEWAY_KEY_HASHES = JSON.stringify({ 'site-test-client': createHash('sha256').update(credential).digest('hex') });
    await writeFile(join(dir, 'wrangler.json'), JSON.stringify(config));
    worker = spawn(process.execPath, [join(root, 'gateway/node_modules/wrangler/bin/wrangler.js'), 'dev', '--config', join(dir, 'wrangler.json'), '--local', '--port', '0'], { cwd: dir, env: { ...process.env, WRANGLER_SEND_METRICS: 'false' }, stdio: ['ignore', 'pipe', 'pipe'] });
    worker.stdout.on('data', b => { output += b; });
    worker.stderr.on('data', b => { output += b; });
    origin = undefined;
    for (let i = 0; i < 200; i++) {
      origin = output.match(/Ready on (http:\/\/[^\s]+)/)?.[1];
      if (origin || worker.exitCode !== null) break;
      await delay(100);
    }
    // Do not expose temporary credentials in a failure log.
    assert.ok(origin, 'Configured local Worker did not start.');
    for (const headers of [{}, { Authorization: `Bearer ${randomUUID()}` }]) {
      const response = await fetch(`${origin}/v1/models`, { headers: { ...headers, Accept: 'text/html', 'Sec-Fetch-Mode': 'navigate' } });
      assert.equal(response.status, 401);
      assert.equal(response.headers.get('cache-control'), 'no-store');
      assert.match(response.headers.get('content-type'), /application\/json/);
    }
    const models = await fetch(`${origin}/v1/models`, { headers: { Authorization: `Bearer ${credential}` } });
    assert.equal(models.status, 200);
    assert.equal(models.headers.get('cache-control'), 'no-store');
    const catalogue = await models.json();
    assert.equal(catalogue.object, 'list');
    assert.equal(catalogue.data.length, 3);
    assert.equal(catalogue.data.some(m => m.id.includes('melotts')), false);
    for (const path of ['/v1', '/v1/missing']) {
      const response = await fetch(`${origin}${path}`, { headers: { Authorization: `Bearer ${credential}`, Accept: 'text/html' } });
      assert.equal(response.status, 404);
      assert.equal(response.headers.get('cache-control'), 'no-store');
      assert.match(response.headers.get('content-type'), /application\/json/);
    }
  } finally {
    if (worker && worker.exitCode === null) { worker.kill('SIGTERM'); await new Promise(resolve => worker.once('exit', resolve)); }
    await rm(dir, { recursive: true, force: true });
  }
});
