import { expect, it, vi } from 'vitest';
import { credentials, dispatch, gatewayEnv } from './helpers';

it('handles allowed unauthenticated preflight without inference or admission', async () => {
  const { secret } = await credentials(); const env = gatewayEnv(secret);
  const forbidden = vi.fn(() => { throw Error('No inference/admission'); });
  env.AI = { run: forbidden } as unknown as Ai;
  env.FREE_GATE = { get: forbidden, idFromName: forbidden } as unknown as typeof env.FREE_GATE;
  for (const origin of ['https://molamaker.si', 'https://www.molamaker.si']) {
    const response = await dispatch(new Request('https://molamaker.si/v1/chat/completions', { method: 'OPTIONS', headers: { Origin: origin, 'Access-Control-Request-Method': 'POST', 'Access-Control-Request-Headers': 'authorization,content-type' } }), env);
    expect(response.status).toBe(204);
    expect(response.headers.get('Access-Control-Allow-Origin')).toBe(origin);
    expect(response.headers.get('Access-Control-Allow-Headers')).toBe('Authorization, Content-Type');
    expect(response.headers.get('Cache-Control')).toBe('no-store');
  }
  expect(forbidden).not.toHaveBeenCalled();
});

it('refuses foreign origins and invalid preflight before authentication', async () => {
  const { secret } = await credentials();
  for (const origin of ['https://molamaker.si.evil.example', 'http://molamaker.si', 'null']) {
    const response = await dispatch(new Request('https://molamaker.si/v1/models', { headers: { Origin: origin } }), gatewayEnv(secret));
    expect(response.status).toBe(403);
    expect(response.headers.has('Access-Control-Allow-Origin')).toBe(false);
    expect(response.headers.get('Cache-Control')).toBe('no-store');
  }
  const response = await dispatch(new Request('https://molamaker.si/v1/models', { method: 'OPTIONS', headers: { Origin: 'https://molamaker.si', 'Access-Control-Request-Method': 'DELETE' } }), gatewayEnv(secret));
  expect(response.status).toBe(403);
});

it('keeps authenticated discovery and JSON API errors no-store with exact CORS', async () => {
  const { keys, secret } = await credentials();
  for (const [path, key, status] of [['models', undefined, 401], ['missing', keys[0], 404], ['models', keys[0], 200]] as const) {
    const response = await dispatch(new Request(`https://molamaker.si/v1/${path}`, { headers: { Origin: 'https://molamaker.si', ...(key ? { Authorization: `Bearer ${key}` } : {}) } }), gatewayEnv(secret));
    expect(response.status).toBe(status); expect(response.headers.get('Cache-Control')).toBe('no-store');
    expect(response.headers.get('Content-Type')).toContain('application/json');
    expect(response.headers.get('Access-Control-Allow-Origin')).toBe('https://molamaker.si');
    expect(response.headers.get('Vary')).toContain('Origin');
  }
});

it('redirects www before any credential, asset or AI processing', async () => {
  const { keys, secret } = await credentials(); const env = gatewayEnv(secret);
  env.ASSETS = { fetch() { throw Error('Cannot serve www'); } } as unknown as Fetcher;
  const response = await dispatch(new Request('https://www.molamaker.si/v1/models?view=1', { headers: { Authorization: `Bearer ${keys[0]}` } }), env);
  expect(response.status).toBe(301); expect(response.headers.get('Location')).toBe('https://molamaker.si/v1/models?view=1');
  expect(response.headers.get('Cache-Control')).toBe('no-store');
  expect(await response.text()).not.toContain(keys[0]);
  const forbidden = await dispatch(new Request('https://www.molamaker.si/?token=private'), env);
  expect(forbidden.status).toBe(400); expect(forbidden.headers.has('Location')).toBe(false);
});

it('emergency inference switch keeps models available but stops admission', async () => {
  const { keys, secret } = await credentials(); const env = { ...gatewayEnv(secret), INFERENCE_ENABLED: 'false' };
  const forbidden = vi.fn(() => { throw Error('Inference disabled'); });
  env.AI = { run: forbidden } as unknown as Ai;
  env.FREE_GATE = { get: forbidden, idFromName: forbidden } as unknown as typeof env.FREE_GATE;
  const headers = { Authorization: `Bearer ${keys[0]}` };
  expect((await dispatch(new Request('https://molamaker.si/v1/models', { headers }), env)).status).toBe(200);
  const health = await dispatch(new Request('https://molamaker.si/healthz'), env);
  expect(await health.json()).toMatchObject({ inferenceEnabled: false });
  expect((await dispatch(new Request('https://molamaker.si/v1/chat/completions', { method: 'POST', headers }), env)).status).toBe(503);
  expect(forbidden).not.toHaveBeenCalled();
});

it('makes asset error responses no-store without altering the public success cache policy', async () => {
  const { secret } = await credentials(); const env = gatewayEnv(secret);
  env.ASSETS = { fetch: async () => new Response('404', { status: 404, headers: { 'Cache-Control': 'public, max-age=60' } }) } as unknown as Fetcher;
  const response = await dispatch(new Request('https://molamaker.si/missing'), env);
  expect(response.status).toBe(404); expect(response.headers.get('Cache-Control')).toBe('no-store');
});
it('missing or malformed inference opt-in fails closed before any model admission', async () => {
  const { keys, secret } = await credentials();
  for (const flag of [undefined, '', 'FALSE', 'yes']) {
    const env = { ...gatewayEnv(secret), INFERENCE_ENABLED: flag };
    const response = await dispatch(new Request('https://molamaker.si/v1/chat/completions', { method: 'POST', headers: { Authorization: `Bearer ${keys[0]}` } }), env);
    expect(response.status).toBe(503);
    expect(await response.json()).toMatchObject({ error: { code: 'inference_disabled' } });
  }
});
