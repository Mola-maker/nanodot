import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
test('relay launch refuses absent review and absent existing subdomain without starting Wrangler', async () => {
  const { startRelay } = await import('./start-operator-relay.mjs'); let starts = 0;
  const env = { CLOUDFLARE_API_TOKEN: randomUUID(), CLOUDFLARE_ACCOUNT_ID: randomUUID().replaceAll('-', '') };
  const spawnProcess = () => { starts++; };
  await assert.rejects(() => startRelay({ env, spawnProcess }), /reviewed session controls/);
  await assert.rejects(() => startRelay({ env, freePlanConfirmed: true, sessionControlsReviewed: true, spawnProcess, fetcher: async (_url, init) => {
    assert.equal(init.method, 'GET'); return Response.json({ success: false }, { status: 404 });
  } }), /existing workers.dev subdomain/);
  assert.equal(starts, 0);
});
test('approved launcher binds only loopback and never passes control credentials through argv', async () => {
  const { startRelay } = await import('./start-operator-relay.mjs'); const token = randomUUID(); let starts = 0;
  await startRelay({ env: { CLOUDFLARE_API_TOKEN: token, CLOUDFLARE_ACCOUNT_ID: randomUUID().replaceAll('-', '') }, freePlanConfirmed: true, sessionControlsReviewed: true,
    fetcher: async (_url, init) => { assert.equal(init.redirect, 'error'); return Response.json({ success: true, result: { subdomain: 'fixture' } }); },
    spawnProcess: (_binary, args, options) => { starts++; assert.equal(args.includes(token), false); assert.ok(args.includes('127.0.0.1')); assert.equal(args.includes('deploy'), false); assert.equal(options.env.WRANGLER_LOG, 'warn'); return {}; },
  });
  assert.equal(starts, 1);
});
