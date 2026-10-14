import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
const read = name => readFile(new URL(`../../${name}`, import.meta.url), 'utf8');
test('production assets always run through Worker and public routes are exact', async () => {
  const config = JSON.parse(await read('gateway/wrangler.jsonc'));
  assert.deepEqual(config.routes, [{ pattern: 'molamaker.si', custom_domain: true }, { pattern: 'www.molamaker.si', custom_domain: true }]);
  assert.equal(config.assets.run_worker_first, true);
  assert.equal(config.vars.TTS_ZH_VERIFIED, 'false'); assert.equal(config.vars.INFERENCE_ENABLED, 'false');
  assert.equal(config.workers_dev, false); assert.equal(config.preview_urls, false);
});
test('private operator has exact external production binding without own migrations', async () => {
  const config = JSON.parse(await read('gateway/wrangler.operator.jsonc'));
  assert.equal(config.workers_dev, false); assert.equal(config.preview_urls, false); assert.deepEqual(config.routes, []);
  assert.deepEqual(config.durable_objects.bindings, [{ name: 'FREE_GATE', class_name: 'FreeGate', script_name: 'nanodot-gateway' }]);
  assert.equal(config.migrations, undefined);
});
test('CI only publishes from a protected explicit manual job; legacy raw Pages publication is closed', async () => {
  const ci = await read('.github/workflows/gateway-site.yml');
  assert.match(ci, /github\.event_name == 'workflow_dispatch' && inputs\.deploy/);
  assert.match(ci, /environment:\s*name: gateway-production/);
  assert.match(ci, /NANODOT_DOCS_BASE: \/docs\//);
  assert.match(ci, /node scripts\/site\/build\.mjs/);
  assert.match(ci, /versions upload/); assert.doesNotMatch(ci, /secrets\.GATEWAY_KEY_HASHES/);
  const pages = await read('.github/workflows/pages.yml');
  assert.doesNotMatch(pages, /deploy-pages|pages: write|id-token: write|upload-pages-artifact/);
});
