#!/usr/bin/env node
// Default acceptance is no-AI. Each explicit inference reserves the shared <=15 ledger.
import { readFile, writeFile, mkdir, readdir } from 'node:fs/promises';
import { dirname, resolve, join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { createHash } from 'node:crypto';
import { runAttempt } from './verify-audio.mjs';
const BASE = 'https://molamaker.si';
const TEXT = '@cf/qwen/qwen3-30b-a3b-fp8', VISION = '@cf/google/gemma-4-26b-a4b-it';
const hash = value => createHash('sha256').update(value).digest('hex');
class VerificationError extends Error {}
function check(value, message) { if (!value) throw new VerificationError(message); }
export async function scanSite(directory, secrets = []) {
  const files = [];
  async function walk(path, prefix = '') {
    for (const entry of (await readdir(path, { withFileTypes: true })).sort((a, b) => a.name.localeCompare(b.name))) {
      const name = `${prefix}${entry.name}`;
      check(!entry.isSymbolicLink(), 'Static output contained a symlink.');
      check(!/^(\.env(?:\..*)?|\.dev\.vars(?:\..*)?|.*\.(?:pem|key|keystore|jks))$/i.test(entry.name), 'Private file in static output.');
      if (entry.isDirectory()) await walk(join(path, entry.name), `${name}/`);
      else {
        const bytes = await readFile(join(path, entry.name));
        check(!secrets.filter(Boolean).some(secret => bytes.includes(Buffer.from(secret))), 'Static output contained credential bytes.');
        check(!/-----BEGIN ([A-Z ]*)PRIVATE KEY-----[\r\n ]+[A-Za-z0-9+/=\r\n ]{64,}-----END \1PRIVATE KEY-----/.test(bytes.toString('utf8')), 'Private key in static output.');
        files.push({ name, sha256: hash(bytes) });
      }
    }
  }
  await walk(resolve(directory));
  check(files.length > 0, 'Static output was empty.');
  return { status: 'PASS', stage: 'static-no-ai', files: files.length, manifestSha256: hash(JSON.stringify(files)), manifest: files };
}
export async function safeRequest(url, init = {}, fetcher = fetch) {
  const response = await fetcher(url, { ...init, redirect: 'manual', signal: init.signal ?? AbortSignal.timeout(30000) });
  check(response.status < 300 || response.status >= 400, 'Redirect refused; credential never forwarded.');
  return response;
}
export async function verifyLive({ base, key, stage = 'no-ai', freePlanConfirmed = false, liveAI = false, ledger, image, imageSha256, fetcher = fetch }) {
  check(base === BASE, 'Use the exact production origin https://molamaker.si.');
  check(typeof key === 'string' && /^[A-Za-z0-9._~+/-]+=*$/.test(key), 'Local project credential is required.');
  check(['no-ai', 'text', 'vision', 'care', 'cancel'].includes(stage), 'Unknown verification stage.');
  if (stage !== 'no-ai') check(liveAI && freePlanConfirmed && ledger, 'AI stages require explicit live AI, verified Free plan and shared ledger.');
  const report = { base, stage, startedAt: new Date().toISOString(), aiAttempts: 0, checks: [], pending: ['Android overlay/native secure storage', 'desktop/Web/iOS actual client', 'Chinese ASR', 'conditional TTS decode and hash-bound human listening', 'actual production HTTP429', 'provider daily Neuron exhaustion'] };
  try {
  async function request(path, init = {}, authorized = false) {
    return safeRequest(`${base}${path}`, { ...init, headers: { ...init.headers, ...(authorized ? { Authorization: `Bearer ${key}` } : {}) } }, fetcher);
  }
  async function record(name, response, status, { json = false, privateResponse = false } = {}) {
    const edgeId = response.headers.get('X-Request-ID') ?? response.headers.get('CF-Ray');
    report.lastResponse = { httpStatus: response.status, ...(edgeId && /^[A-Za-z0-9-]{1,100}$/.test(edgeId) ? { requestId: edgeId } : {}) };
    check(response.status === status, `${name}: unexpected HTTP status ${response.status}.`);
    if (privateResponse) check(response.headers.get('Cache-Control') === 'no-store', `${name}: private/error response was cacheable.`);
    if (json) check(response.headers.get('Content-Type')?.includes('application/json'), `${name}: expected JSON.`);
    const bytes = new Uint8Array(await response.arrayBuffer());
    check(!new TextDecoder().decode(bytes).includes(key), `${name}: response contained a credential.`);
    const id = response.headers.get('X-Request-ID') ?? response.headers.get('CF-Ray');
    report.checks.push({ name, status: 'PASS', httpStatus: response.status, responseSha256: hash(bytes), ...(id && /^[A-Za-z0-9-]{1,100}$/.test(id) ? { requestId: id } : {}) });
    return bytes;
  }
  if (stage === 'no-ai') {
    const health = JSON.parse(new TextDecoder().decode(await record('health', await request('/healthz'), 200, { json: true, privateResponse: true })));
    check(health.configured === true && health.deployed === true, 'Gateway is not configured.');
    report.inferenceEnabled = health.inferenceEnabled;
    await record('unauthorized models', await request('/v1/models'), 401, { json: true, privateResponse: true });
    const models = JSON.parse(new TextDecoder().decode(await record('authorized models', await request('/v1/models', {}, true), 200, { json: true, privateResponse: true })));
    check(models.object === 'list' && Array.isArray(models.data), 'Model catalogue invalid.');
    check([TEXT, VISION, '@cf/openai/whisper-large-v3-turbo'].every(id => models.data.some(m => m.id === id)), 'Model catalogue incomplete.');
    check(models.data.every(m => [TEXT, VISION, '@cf/openai/whisper-large-v3-turbo', '@cf/myshell-ai/melotts'].includes(m.id)), 'Model catalogue outside allowlist.');
    report.ttsAdvertised = models.data.some(m => m.id === '@cf/myshell-ai/melotts');
    for (const path of ['/v1', '/v1/task6-missing']) await record(path, await request(path, {}, true), 404, { json: true, privateResponse: true });
    for (const origin of [BASE, 'https://www.molamaker.si']) {
      const response = await request('/v1/chat/completions', { method: 'OPTIONS', headers: { Origin: origin, 'Access-Control-Request-Method': 'POST', 'Access-Control-Request-Headers': 'authorization,content-type' } });
      check(response.headers.get('Access-Control-Allow-Origin') === origin, 'Exact CORS origin missing.');
      await record(`preflight ${origin}`, response, 204, { privateResponse: true });
    }
    const denied = await request('/v1/models', { headers: { Origin: 'https://forbidden.example' } });
    check(!denied.headers.has('Access-Control-Allow-Origin'), 'Foreign origin was allowed.');
    await record('foreign origin', denied, 403, { json: true, privateResponse: true });
    for (const path of ['/', '/connect/', '/privacy', '/docs/', '/site.js', '/style.css']) await record(`website ${path}`, await request(path), 200);
    await record('unknown webpage', await request('/task6-missing-page'), 404);
    const redirect = await fetcher('https://www.molamaker.si/connect/', { redirect: 'manual', signal: AbortSignal.timeout(30000) });
    check(redirect.status === 301 && redirect.headers.get('Location') === `${base}/connect/`, 'www canonical redirect missing.');
    report.checks.push({ name: 'www redirect without Bearer', status: 'PASS', httpStatus: 301 });
    report.aiAttempts = 0;
    return report;
  }
  const body = { model: TEXT, stream: true, max_tokens: 128, messages: [{ role: 'user', content: '请只用中文回答：你好，中文连接测试。' }] };
  if (stage === 'care') body.messages[0].content = '请用中文给出一条简短关怀建议。只建议，不执行任何工具或设备操作。';
  if (stage === 'vision') {
    check(image && /^[a-f0-9]{64}$/.test(imageSha256 ?? ''), 'Vision needs a local PNG fixture and expected SHA256.');
    const bytes = await readFile(image);
    check(hash(bytes) === imageSha256 && bytes.length <= 2097152 && bytes.subarray(0, 8).equals(Buffer.from([137,80,78,71,13,10,26,10])), 'Vision fixture hash/type/bounds invalid.');
    check(bytes.readUInt32BE(16) <= 1280 && bytes.readUInt32BE(20) <= 1280, 'Vision fixture dimensions exceed 1280.');
    body.model = VISION;
    body.messages[0].content = [{ type: 'text', text: '请用中文说出图中标记文字。' }, { type: 'image_url', image_url: { url: `data:image/png;base64,${bytes.toString('base64')}` } }];
    report.fixtureSha256 = imageSha256;
  }
  const controller = new AbortController(); const timer = setTimeout(() => controller.abort(), 120000);
  try {
    const response = await runAttempt(resolve(ledger), `verify-live:${stage}`, () => { report.aiAttempts = 1; return request('/v1/chat/completions', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body), signal: controller.signal }, true); });
    const edgeId = response.headers.get('X-Request-ID') ?? response.headers.get('CF-Ray');
    report.lastResponse = { httpStatus: response.status, ...(edgeId && /^[A-Za-z0-9-]{1,100}$/.test(edgeId) ? { requestId: edgeId } : {}) };
    check(response.ok && response.headers.get('Content-Type')?.includes('text/event-stream') && response.headers.get('Cache-Control') === 'no-store', `AI stream failed HTTP${response.status}.`);
    const reader = response.body.getReader(); const decoder = new TextDecoder(); let transcript = '';
    try {
      while (true) {
        const { value, done } = await reader.read(); if (done) break;
        transcript += decoder.decode(value, { stream: true });
        check(transcript.length <= 2 * 1024 * 1024, 'SSE output exceeded verifier bound.');
        if (stage === 'cancel') { controller.abort(); await reader.cancel().catch(() => {}); break; }
      }
    } finally { reader.releaseLock(); }
    if (stage !== 'cancel') {
      check(transcript.includes('data: [DONE]'), 'SSE missing terminal DONE.');
      let content = '';
      for (const line of transcript.split(/\r?\n/)) if (line.startsWith('data: ') && line !== 'data: [DONE]') {
        const frame = JSON.parse(line.slice(6)); check(!frame.error, 'SSE reported an error.');
        for (const choice of frame.choices ?? []) { check(!choice.delta?.tool_calls, 'No-tools acceptance emitted tools.'); content += choice.delta?.content ?? ''; }
      }
      check(/[\u3400-\u9fff]/.test(content), 'SSE returned no Chinese content.');
    }
    check(!transcript.includes(key), 'Stream contained credential.');
    const requestId = response.headers.get('X-Request-ID') ?? response.headers.get('CF-Ray');
    report.checks.push({ ...(requestId && /^[A-Za-z0-9-]{1,100}$/.test(requestId) ? { requestId } : {}), name: stage, status: 'PASS', responseSha256: hash(transcript), note: stage === 'cancel' ? 'Reader/transport aborted locally; upstream completion and native UI remain unverified.' : 'Protocol and Chinese content passed; semantic fixture/proposal review remains pending.' });
    return report;
  } finally { clearTimeout(timer); }
  } catch (cause) {
    report.status = 'FAIL';
    report.failure = cause instanceof VerificationError ? cause.message : 'Request, ledger or response parsing failed; no automatic retry.';
    throw Object.assign(new VerificationError(report.failure), { report });
  }
}
export async function main(argv = process.argv.slice(2)) {
  const allowed = new Set(['scan-site', 'base', 'report', 'stage', 'ledger', 'image', 'image-sha256', 'live-ai', 'free-plan-confirmed']);
  const args = new Map();
  for (let i = 0; i < argv.length; i++) {
    const name = argv[i].slice(2); check(argv[i].startsWith('--') && allowed.has(name) && !args.has(name), 'Unknown or duplicate option.');
    args.set(name, ['live-ai', 'free-plan-confirmed'].includes(name) ? true : argv[++i]);
  }
  check(typeof args.get('report') === 'string', 'Supply --base https://molamaker.si --report local-path.');
  let report;
  try {
    if (args.has('scan-site')) {
      check(args.size === 2, 'Static scan accepts only --scan-site and --report.');
      report = await scanSite(args.get('scan-site'), [process.env.NANODOT_TEST_GATEWAY_KEY, process.env.AUDIO_PROBE_CREDENTIAL, process.env.CLOUDFLARE_API_TOKEN]);
    } else report = await verifyLive({ base: args.get('base'), key: process.env.NANODOT_TEST_GATEWAY_KEY, stage: args.get('stage'), liveAI: args.has('live-ai'), freePlanConfirmed: args.has('free-plan-confirmed'), ledger: args.get('ledger'), image: args.get('image'), imageSha256: args.get('image-sha256') }); }
  catch (cause) { report = cause?.report ?? { status: 'FAIL', stage: args.get('stage') ?? 'no-ai', note: cause instanceof VerificationError ? cause.message : 'Verification failed; no automatic retry.' }; process.exitCode = 1; }
  await mkdir(dirname(resolve(args.get('report'))), { recursive: true });
  await writeFile(args.get('report'), JSON.stringify(report, null, 2) + '\n', { mode: 0o600 });
  console.log(`Gateway ${report.status === 'FAIL' ? 'FAIL' : 'checks saved'}; no credentials logged.`);
}
if (process.argv[1] && pathToFileURL(resolve(process.argv[1])).href === import.meta.url) main().catch(() => { console.error('Invalid verifier configuration.'); process.exitCode = 1; });
