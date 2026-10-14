import { authenticate, readKeyHashes } from './auth';
import type { GatewayEnv } from './types';
import { handleChat } from './chat';
import { listModels } from './models';
import { handleTranscription, handleSpeech, ttsEnabled } from './audio';
export { FreeGate } from './quota';

function configured(env: GatewayEnv): boolean {
  try {
    readKeyHashes(env.GATEWAY_KEY_HASHES);
    return typeof env.AI?.run === 'function' && typeof env.ASSETS?.fetch === 'function'
      && typeof env.FREE_GATE?.idFromName === 'function' && typeof env.FREE_GATE?.get === 'function';
  } catch { return false; }
}

function json(body: unknown, status: number, extraHeaders?: HeadersInit): Response {
  return Response.json(body, { status, headers: { 'Cache-Control': 'no-store', ...extraHeaders } });
}

function error(status: number, code: string, message: string, headers?: HeadersInit): Response {
  return json({ error: { code, message } }, status, headers);
}

async function route(request: Request, env: GatewayEnv, _ctx: ExecutionContext): Promise<Response> {
  const path = new URL(request.url).pathname;
  if (path === '/healthz') {
    if (request.method !== 'GET' && request.method !== 'HEAD') return error(405, 'method_not_allowed', 'Use GET or HEAD.', { Allow: 'GET, HEAD' });
    const ready = configured(env);
    return json({ deployed: true, configured: ready, inferenceEnabled: ready && env.INFERENCE_ENABLED === 'true' }, ready ? 200 : 503);
  }
  if (path === '/v1' || path.startsWith('/v1/')) {
    // Configuration failures take precedence; never substitute an anonymous path.
    if (!configured(env)) return error(503, 'gateway_unconfigured', 'Gateway configuration unavailable.');
    const identity = await authenticate(request, env);
    if (!identity) return error(401, 'unauthorized', 'A project credential is required.', { 'WWW-Authenticate': 'Bearer' });
    if (path === '/v1/models') {
      if (request.method !== 'GET') return error(405, 'method_not_allowed', 'Use GET.', { Allow: 'GET' });
      return listModels(ttsEnabled(env));
    }
    const pending = new Set(['/v1/chat/completions', '/v1/audio/transcriptions', '/v1/audio/speech']);
    if (!pending.has(path)) return error(404, 'not_found', 'API route not found.');
    if (request.method !== 'POST') return error(405, 'method_not_allowed', 'Use POST.', { Allow: 'POST' });
    if (env.INFERENCE_ENABLED !== 'true') return error(503, 'inference_disabled', 'Inference is temporarily disabled.');
    if (path === '/v1/chat/completions') return handleChat(request, env, identity.clientId);
    if (path === '/v1/audio/transcriptions') return handleTranscription(request, env, identity.clientId);
    return handleSpeech(request, env, identity.clientId);
  }
  if (request.method !== 'GET' && request.method !== 'HEAD') return error(405, 'method_not_allowed', 'Use GET or HEAD.', { Allow: 'GET, HEAD' });
  if (!env.ASSETS) return error(503, 'assets_unavailable', 'Website assets unavailable.');
  try { return await env.ASSETS.fetch(request); } catch { return error(503, 'assets_unavailable', 'Website assets unavailable.'); }
}

const ORIGINS = new Set(['https://molamaker.si', 'https://www.molamaker.si']);
export async function handle(request: Request, env: GatewayEnv, ctx: ExecutionContext): Promise<Response> {
  const url = new URL(request.url);
  // Canonicalize before authentication; clients must refuse authenticated redirects.
  if (url.hostname === 'www.molamaker.si') {
    if ([...url.searchParams.keys()].some(key => /^(key|api_key|token|access_token|authorization)$/i.test(key))) return error(400, 'invalid_request', 'Credentials cannot appear in URLs.');
    url.hostname = 'molamaker.si'; url.protocol = 'https:'; url.port = '';
    return new Response(null, { status: 301, headers: { Location: url.href, 'Cache-Control': 'no-store' } });
  }
  const api = url.pathname === '/v1' || url.pathname.startsWith('/v1/') || url.pathname === '/healthz';
  const origin = request.headers.get('Origin');
  if (api && origin && !ORIGINS.has(origin)) return error(403, 'origin_forbidden', 'Origin is not allowed.');
  let response: Response;
  if (api && request.method === 'OPTIONS') {
    const method = request.headers.get('Access-Control-Request-Method');
    const headers = (request.headers.get('Access-Control-Request-Headers') ?? '').split(',').map(h => h.trim().toLowerCase()).filter(Boolean);
    if (!origin || !method || !['GET', 'HEAD', 'POST'].includes(method) || headers.some(h => !['authorization', 'content-type'].includes(h))) return error(403, 'preflight_forbidden', 'Preflight is not allowed.');
    response = new Response(null, { status: 204, headers: { 'Cache-Control': 'no-store', 'Access-Control-Allow-Methods': 'GET, HEAD, POST', 'Access-Control-Allow-Headers': 'Authorization, Content-Type' } });
  } else response = await route(request, env, ctx);
  if (response.status >= 400 || (api && origin)) {
    const headers = new Headers(response.headers);
    if (response.status >= 400) headers.set('Cache-Control', 'no-store');
    if (api && origin) {
      headers.set('Access-Control-Allow-Origin', origin); headers.append('Vary', 'Origin');
      headers.set('Access-Control-Expose-Headers', 'Retry-After, X-Request-ID');
    }
    response = new Response(response.body, { status: response.status, statusText: response.statusText, headers });
  }
  return response;
}
export default { fetch: handle } satisfies ExportedHandler<GatewayEnv>;
