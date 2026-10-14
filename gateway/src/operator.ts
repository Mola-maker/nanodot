// Private deployed operator service: no routes/workers.dev/preview URLs.
import { authenticate } from './auth';
import { handleSpeech } from './audio';
import type { GatewayEnv } from './types';
export interface OperatorEnv extends GatewayEnv { OPERATOR_KEY_HASHES: string; AUDIO_PROBE_LANG?: string }
export async function operator(request: Request, env: OperatorEnv): Promise<Response> {
  const reply = (body: unknown, status: number) => Response.json(body, { status, headers: { 'Cache-Control': 'no-store' } });
  const identity = await authenticate(request, { ...env, GATEWAY_KEY_HASHES: env.OPERATOR_KEY_HASHES });
  if (!identity) return reply({ error: { code: 'unauthorized' } }, 401);
  if (request.method !== 'POST' || request.headers.has('Origin')) return reply({ error: { code: 'method_not_allowed' } }, 405);
  const path = new URL(request.url).pathname;
  if (path === '/v1/audio/speech') {
    // Only this private script sets candidate state. Production remains unchanged.
    return handleSpeech(request, { ...env, TTS_ZH_VERIFIED: 'true', TTS_ZH_LANG: env.AUDIO_PROBE_LANG }, identity.clientId);
  }
  if (path === '/quota-proof') {
    // Real production namespace/class, but NEVER the production global object.
    const gate = env.FREE_GATE.get(env.FREE_GATE.idFromName('task6-isolated-quota-v1'));
    const outcomes = [];
    const now = Date.now();
    for (let index = 0; index < 13; index++) outcomes.push(await gate.admit(identity.clientId, now));
    const admitted = outcomes.filter(result => result.allowed).length;
    const denied = outcomes.length - admitted;
    return reply({ scope: 'isolated-object-not-production-http429', objectName: 'task6-isolated-quota-v1', admitted, denied, retryAfterSeconds: outcomes.at(-1)?.retryAfterSeconds }, denied ? 429 : 500);
  }
  return reply({ error: { code: 'not_found' } }, 404);
}
export default { fetch: operator };
