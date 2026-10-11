import { authenticate, readKeyHashes } from './auth';
import type { GatewayEnv } from './types';
import { handleChat } from './chat';
import { listModels } from './models';
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

export async function handle(request: Request, env: GatewayEnv, _ctx: ExecutionContext): Promise<Response> {
  const path = new URL(request.url).pathname;
  if (path === '/healthz') {
    if (request.method !== 'GET' && request.method !== 'HEAD') return error(405, 'method_not_allowed', 'Use GET or HEAD.', { Allow: 'GET, HEAD' });
    const ready = configured(env);
    return json({ deployed: true, configured: ready, inferenceEnabled: ready }, ready ? 200 : 503);
  }
  if (path === '/v1' || path.startsWith('/v1/')) {
    // Configuration failures take precedence; never substitute an anonymous path.
    if (!configured(env)) return error(503, 'gateway_unconfigured', 'Gateway configuration unavailable.');
    const identity = await authenticate(request, env);
    if (!identity) return error(401, 'unauthorized', 'A project credential is required.', { 'WWW-Authenticate': 'Bearer' });
    if (path === '/v1/models') {
      if (request.method !== 'GET') return error(405, 'method_not_allowed', 'Use GET.', { Allow: 'GET' });
      return listModels(false);
    }
    const pending = new Set(['/v1/chat/completions', '/v1/audio/transcriptions', '/v1/audio/speech']);
    if (!pending.has(path)) return error(404, 'not_found', 'API route not found.');
    if (request.method !== 'POST') return error(405, 'method_not_allowed', 'Use POST.', { Allow: 'POST' });
    if (path === '/v1/chat/completions') return handleChat(request, env, identity.clientId);
    // Disabled audio routes do not reserve quota or fabricate a reply.
    return error(503, 'inference_unavailable', 'Inference is not enabled.');
  }
  if (request.method !== 'GET' && request.method !== 'HEAD') return error(405, 'method_not_allowed', 'Use GET or HEAD.', { Allow: 'GET, HEAD' });
  if (!env.ASSETS) return error(503, 'assets_unavailable', 'Website assets unavailable.');
  try { return await env.ASSETS.fetch(request); } catch { return error(503, 'assets_unavailable', 'Website assets unavailable.'); }
}

export default { fetch: handle } satisfies ExportedHandler<GatewayEnv>;
