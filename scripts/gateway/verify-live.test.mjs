import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';

// Import must never dispatch network calls; verifier credentials are ephemeral.
test('safe request refuses every redirect without forwarding Bearer', async () => {
  const { safeRequest } = await import('./verify-live.mjs');
  const key = randomUUID(); let calls = 0;
  await assert.rejects(() => safeRequest('https://molamaker.si/v1/models', { headers: { Authorization: `Bearer ${key}` } }, async (_url, init) => {
    calls++; assert.equal(init.redirect, 'manual');
    return new Response(null, { status: 301, headers: { Location: 'https://foreign.example/' } });
  }), /Redirect refused/);
  assert.equal(calls, 1);
});

test('live stages cannot dispatch without Free confirmation and a shared ledger', async () => {
  const { verifyLive } = await import('./verify-live.mjs');
  let calls = 0;
  await assert.rejects(() => verifyLive({ base: 'https://molamaker.si', key: randomUUID(), stage: 'text', fetcher: async () => { calls++; } }), /Free plan and shared ledger/);
  assert.equal(calls, 0);
});

test('only the exact production HTTPS origin accepts the project key', async () => {
  const { verifyLive } = await import('./verify-live.mjs');
  for (const base of ['https://foreign.example', 'http://molamaker.si', 'https://molamaker.si/path', 'https://molamaker.si?token=abc']) {
    await assert.rejects(() => verifyLive({ base, key: randomUUID() }), /exact production origin/);
  }
});

function noAiFetcher(requests = []) {
  return async (url, init) => {
    requests.push([url, init]); const path = new URL(url).pathname;
    const origin = init.headers?.Origin;
    const headers = { 'Cache-Control': 'no-store' };
    if (url.startsWith('https://www.')) return new Response(null, { status: 301, headers: { Location: 'https://molamaker.si/connect/' } });
    if (origin === 'https://forbidden.example') return Response.json({ error: {} }, { status: 403, headers });
    if (init.method === 'OPTIONS') return new Response(null, { status: 204, headers: { ...headers, 'Access-Control-Allow-Origin': origin, 'Access-Control-Allow-Methods': 'GET, POST, OPTIONS', 'Access-Control-Allow-Headers': 'Authorization, Content-Type' } });
    if (path === '/healthz') return Response.json({ deployed: true, configured: true, inferenceEnabled: false }, { headers });
    if (path === '/v1/models') return init.headers?.Authorization ? Response.json({ object: 'list', data: ['@cf/qwen/qwen3-30b-a3b-fp8', '@cf/google/gemma-4-26b-a4b-it', '@cf/openai/whisper-large-v3-turbo'].map(id => ({ id })) }, { headers }) : Response.json({ error: {} }, { status: 401, headers });
    if (path.startsWith('/v1')) return Response.json({ error: {} }, { status: 404, headers });
    return new Response('public', { status: path === '/task6-missing-page' ? 404 : 200, headers });
  };
}

test('no-AI acceptance exercises discovery/auth/assets/preflight without POST inference', async () => {
  const { verifyLive } = await import('./verify-live.mjs'); const key = randomUUID(); const requests = [];
  const fetcher = noAiFetcher(requests);
  const report = await verifyLive({ base: 'https://molamaker.si', key, fetcher });
  assert.equal(report.aiAttempts, 0); assert.equal(report.ttsAdvertised, false);
  assert.equal(requests.some(([,init]) => init.method === 'POST'), false);
  assert.equal(JSON.stringify(report).includes(key), false);
  assert.equal(requests.at(-1)[1].headers, undefined);
});

test('explicit Chinese SSE stage counts one attempt and rejects protocol errors without retry', async () => {
  const { verifyLive } = await import('./verify-live.mjs');
  const { mkdtemp, writeFile, readFile, rm } = await import('node:fs/promises');
  const { tmpdir } = await import('node:os'); const { join } = await import('node:path');
  const directory = await mkdtemp(join(tmpdir(), 'gateway-verify-')); const ledger = join(directory, 'attempts.json');
  try {
    await writeFile(ledger, JSON.stringify({ totalAttempts: 0, attempts: [] })); let calls = 0;
    const report = await verifyLive({ base: 'https://molamaker.si', key: randomUUID(), stage: 'text', freePlanConfirmed: true, liveAI: true, ledger, fetcher: async () => {
      calls++; return new Response('data: {"choices":[{"delta":{"content":"你好"}}]}\n\ndata: [DONE]\n\n', { headers: { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-store' } });
    } });
    assert.equal(calls, 1); assert.equal(report.aiAttempts, 1); assert.equal(JSON.parse(await readFile(ledger)).totalAttempts, 1);
    await assert.rejects(() => verifyLive({ base: 'https://molamaker.si', key: randomUUID(), stage: 'care', freePlanConfirmed: true, liveAI: true, ledger, fetcher: async () => {
      calls++; return new Response('data: {"error":{"code":"quota_exceeded"}}\n\ndata: [DONE]\n\n', { headers: { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-store' } });
    } }), /SSE reported an error/);
    assert.equal(calls, 2); assert.equal(JSON.parse(await readFile(ledger)).totalAttempts, 2);
  } finally { await rm(directory, { recursive: true, force: true }); }
});

test('built-site inspection rejects secret files and actual credential bytes', async () => {
  const { scanSite } = await import('./verify-live.mjs');
  const { mkdtemp, writeFile, rm } = await import('node:fs/promises');
  const { tmpdir } = await import('node:os'); const { join } = await import('node:path');
  const directory = await mkdtemp(join(tmpdir(), 'gateway-site-scan-')); const key = randomUUID();
  try {
    await writeFile(join(directory, 'index.html'), `<script>window.key="${key}"</script>`);
    await assert.rejects(() => scanSite(directory, [key]), /credential bytes/);
    await writeFile(join(directory, 'index.html'), '<h1>Public</h1>');
    await writeFile(join(directory, '.dev.vars'), 'private');
    await assert.rejects(() => scanSite(directory), /Private file/);
    await rm(join(directory, '.dev.vars'));
    assert.equal((await scanSite(directory)).files, 1);
  } finally { await rm(directory, { recursive: true, force: true }); }
});

test('static scanner permits documentation naming a PEM header but rejects a key block', async () => {
  const { scanSite } = await import('./verify-live.mjs');
  const { mkdtemp, writeFile, rm } = await import('node:fs/promises');
  const { tmpdir } = await import('node:os'); const { join } = await import('node:path');
  const directory = await mkdtemp(join(tmpdir(), 'gateway-pem-doc-'));
  try {
    await writeFile(join(directory, 'ios.html'), 'The file starts with -----BEGIN PRIVATE KEY----- to the end.');
    assert.equal((await scanSite(directory)).status, 'PASS');
    await writeFile(join(directory, 'index.html'), `-----BEGIN PRIVATE KEY-----\n${'A'.repeat(80)}\n-----END PRIVATE KEY-----`);
    await assert.rejects(() => scanSite(directory), /Private key/);
  } finally { await rm(directory, { recursive: true, force: true }); }
});

for (const header of ['Access-Control-Allow-Methods', 'Access-Control-Allow-Headers']) {
  test(`no-AI acceptance rejects absent or incomplete ${header}`, async () => {
    const { verifyLive } = await import('./verify-live.mjs');
    for (const value of [null, header.endsWith('Methods') ? 'GET, OPTIONS' : 'Content-Type']) {
      const fixture = noAiFetcher();
      await assert.rejects(() => verifyLive({ base: 'https://molamaker.si', key: randomUUID(), fetcher: async (url, init) => {
        const response = await fixture(url, init);
        if (init.method === 'OPTIONS') {
          if (value === null) response.headers.delete(header); else response.headers.set(header, value);
        }
        return response;
      } }), /Required CORS/);
    }
  });
}

test('no-AI acceptance rejects cacheable unknown webpages', async () => {
  const { verifyLive } = await import('./verify-live.mjs'); const fixture = noAiFetcher();
  await assert.rejects(() => verifyLive({ base: 'https://molamaker.si', key: randomUUID(), fetcher: async (url, init) => {
    const response = await fixture(url, init);
    if (new URL(url).pathname === '/task6-missing-page') response.headers.set('Cache-Control', 'public, max-age=60');
    return response;
  } }), /unknown webpage: private\/error response was cacheable/);
});

async function cancelFixture(fetcher, assertion) {
  const { verifyLive } = await import('./verify-live.mjs');
  const { mkdtemp, writeFile, readFile, rm } = await import('node:fs/promises');
  const { tmpdir } = await import('node:os'); const { join } = await import('node:path');
  const directory = await mkdtemp(join(tmpdir(), 'gateway-cancel-')); const ledger = join(directory, 'attempts.json');
  try {
    await writeFile(ledger, JSON.stringify({ totalAttempts: 0, attempts: [] })); let calls = 0;
    const invoke = () => verifyLive({ base: 'https://molamaker.si', key: randomUUID(), stage: 'cancel', freePlanConfirmed: true, liveAI: true, ledger, fetcher: async (...args) => { calls++; return fetcher(...args); } });
    await assertion(invoke);
    assert.equal(calls, 1); assert.equal(JSON.parse(await readFile(ledger)).totalAttempts, 1);
  } finally { await rm(directory, { recursive: true, force: true }); }
}
const streamHeaders = { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-store' };
for (const content of ['', 'data: [DONE]\n\n']) {
  test(`cancel rejects ${content ? 'naturally ended' : 'empty closed'} SSE without an active read`, async () => {
    await cancelFixture(async () => new Response(content, { headers: streamHeaders }), async invoke => {
      await assert.rejects(invoke, error => {
        assert.equal(error.report.status, 'FAIL');
        assert.equal(error.report.checks.some(check => check.name === 'cancel' && check.status === 'PASS'), false);
        assert.match(error.message, /ended before active-reader cancellation/); return true;
      });
    });
  });
}

test('cancel aborts an active pending read and invokes stream cancellation exactly once', async () => {
  let aborted = false, cancelled = 0;
  await cancelFixture(async (_url, init) => {
    init.signal.addEventListener('abort', () => { aborted = true; }, { once: true });
    const stream = new ReadableStream({
      start(controller) { controller.enqueue(new TextEncoder().encode('data: {"choices":[{"delta":{"content":"你好"}}]}\n\n')); },
      cancel() { cancelled++; }
    });
    return new Response(stream, { headers: streamHeaders });
  }, async invoke => {
    const report = await invoke();
    assert.equal(aborted, true); assert.equal(cancelled, 1);
    assert.deepEqual(report.checks[0].cancellation, { pendingRead: true, abortSignal: true, readerResult: 'closed' });
    assert.match(report.checks[0].note, /upstream.*unverified/);
  });
});
