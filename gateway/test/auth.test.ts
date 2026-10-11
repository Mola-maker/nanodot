import { afterEach, describe, expect, it, vi } from 'vitest';
import type { GatewayEnv } from '../src/types';
import { authenticate } from '../src/auth';
import { credentials, dispatch, gatewayEnv, request } from './helpers';

afterEach(() => vi.restoreAllMocks());

describe('private authentication', () => {
  it('maps a Bearer digest to the configured client identity', async () => {
    const { keys, secret } = await credentials(['first', 'second']);
    expect(await authenticate(request(keys[1]), gatewayEnv(secret))).toEqual({ clientId: 'second' });
  });

  it('refuses missing, incorrect, and query-only keys without AI calls', async () => {
    const { keys, secret } = await credentials();
    const bindings = gatewayEnv(secret);
    const run = vi.fn(() => { throw new Error('AI must not run'); });
    bindings.AI = { run } as unknown as Ai;
    for (const req of [request(), request(crypto.randomUUID()), request(undefined, `https://example.com/v1/chat/completions?key=${keys[0]}`)]) {
      const result = await dispatch(req, bindings);
      expect(result.status).toBe(401);
      expect(result.headers.get('content-type')).toContain('application/json');
    }
    expect(run).not.toHaveBeenCalled();
  });

  it('refuses credentials in query parameters even with a valid Bearer header', async () => {
    const { keys, secret } = await credentials();
    expect(await authenticate(request(keys[0], `https://example.com/v1/chat/completions?api_key=${keys[0]}`), gatewayEnv(secret))).toBeNull();
  });

  it('rejects malformed Bearer headers', async () => {
    const { keys, secret } = await credentials();
    for (const value of [keys[0], `Basic ${keys[0]}`, `Bearer ${keys[0]} extra`, `Bearer ${keys[0]},other`]) {
      const req = request();
      req.headers.set('Authorization', value);
      expect(await authenticate(req, gatewayEnv(secret))).toBeNull();
    }
  });

  it('fails closed on missing, malformed, or ambiguous key configuration before authentication', async () => {
    const { secret } = await credentials();
    const digest = Object.values(JSON.parse(secret))[0];
    for (const config of ['', '{', '[]', '{}', '{"client":"plaintext"}', JSON.stringify({ one: digest, two: digest })]) {
      const response = await dispatch(request(), gatewayEnv(config));
      expect(response.status).toBe(503);
      expect(await response.json()).toMatchObject({ error: { code: 'gateway_unconfigured' } });
    }
  });

  it('fails closed if any required binding is unavailable', async () => {
    const { keys, secret } = await credentials();
    for (const missing of ['AI', 'ASSETS', 'FREE_GATE'] as const) {
      const bindings = gatewayEnv(secret);
      delete (bindings as unknown as Record<string, unknown>)[missing];
      expect((await dispatch(request(keys[0]), bindings)).status).toBe(503);
    }
  });

  it('returns unavailable for authenticated inference without spending quota', async () => {
    const { keys, secret } = await credentials();
    const bindings = gatewayEnv(secret);
    const idFromName = vi.fn(() => { throw new Error('Unavailable routes cannot spend quota'); });
    bindings.FREE_GATE = { idFromName, get: idFromName } as unknown as GatewayEnv['FREE_GATE'];
    const response = await dispatch(request(keys[0], 'https://example.com/v1/audio/speech'), bindings);
    expect(response.status).toBe(503);
    expect(await response.json()).toMatchObject({ error: { code: 'inference_unavailable' } });
    expect(idFromName).not.toHaveBeenCalled();
  });

  it('keeps API errors JSON for unknown routes and methods', async () => {
    const { keys, secret } = await credentials();
    expect((await dispatch(request(keys[0], 'https://example.com/v1/unknown'), gatewayEnv(secret))).status).toBe(404);
    const req = new Request('https://example.com/v1/chat/completions', { headers: { Authorization: `Bearer ${keys[0]}` } });
    expect((await dispatch(req, gatewayEnv(secret))).status).toBe(405);
  });

  it('health reports only configuration and deployment, never calls AI or quota', async () => {
    const { secret } = await credentials();
    const bindings = gatewayEnv(secret);
    bindings.AI = { run() { throw new Error('health cannot call AI'); } } as unknown as Ai;
    const admission = vi.fn(() => { throw new Error('Health cannot admit'); });
    bindings.FREE_GATE = { idFromName: admission, get: admission } as unknown as GatewayEnv['FREE_GATE'];
    const response = await dispatch(new Request('https://example.com/healthz'), bindings);
    expect(response.status).toBe(200);
    expect(await response.json()).toEqual({ deployed: true, configured: true, inferenceEnabled: true });
    expect(admission).not.toHaveBeenCalled();
    const unavailable = await dispatch(new Request('https://example.com/healthz'), gatewayEnv(''));
    expect(unavailable.status).toBe(503);
    expect(await unavailable.json()).toEqual({ deployed: true, configured: false, inferenceEnabled: false });
  });

  it('never logs secrets, raw request bodies, or query strings', async () => {
    const { keys, secret } = await credentials();
    const rawBody = crypto.randomUUID();
    const spies = ['log', 'info', 'warn', 'error', 'debug'].map(method => vi.spyOn(console, method as 'log').mockImplementation(() => {}));
    await dispatch(request(keys[0], 'https://example.com/v1/chat/completions', rawBody), gatewayEnv(secret));
    await dispatch(request(undefined, `https://example.com/v1/chat/completions?key=${keys[0]}`, rawBody), gatewayEnv(secret));
    const logged = JSON.stringify(spies.flatMap(spy => spy.mock.calls));
    for (const sensitive of [keys[0], rawBody, secret]) expect(logged).not.toContain(sensitive);
  });
});
