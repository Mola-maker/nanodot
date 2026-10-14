import { expect, it, vi } from 'vitest';
import { credentials, gatewayEnv } from './helpers';

it('private operator has no production bypass and shares the exact global object for candidate speech', async () => {
  const { operator } = await import('../src/operator');
  const { keys, secret } = await credentials(['operator-task6']);
  const env = { ...gatewayEnv(secret), OPERATOR_KEY_HASHES: secret, AUDIO_PROBE_LANG: 'ZH' };
  const admit = vi.fn(async () => ({ allowed: true, retryAfterSeconds: 0 }));
  const name = vi.fn(() => ({})); env.FREE_GATE = { idFromName: name, get: () => ({ admit }) } as unknown as typeof env.FREE_GATE;
  const run = vi.fn(async () => ({ audio: 'SUQzAA==' })); env.AI = { run } as unknown as Ai;
  const request = (key?: string) => new Request('https://operator.invalid/v1/audio/speech', { method: 'POST', headers: { 'Content-Type': 'application/json', ...(key ? { Authorization: `Bearer ${key}` } : {}) }, body: JSON.stringify({ model: '@cf/myshell-ai/melotts', input: '你好' }) });
  expect((await operator(request(), env)).status).toBe(401); expect(run).not.toHaveBeenCalled();
  expect((await operator(request(keys[0]), env)).status).toBe(200);
  expect(name).toHaveBeenCalledWith('global-free-tier-v1');
  expect(admit).toHaveBeenCalledWith('operator-task6', expect.any(Number));
  expect(run).toHaveBeenCalledWith('@cf/myshell-ai/melotts', { prompt: '你好', lang: 'ZH' });
  expect(env.TTS_ZH_VERIFIED).not.toBe('true');
});

it('isolated quota proof never accesses the production bucket or AI', async () => {
  const { operator } = await import('../src/operator'); const { keys, secret } = await credentials(['operator-task6']);
  const env = { ...gatewayEnv(secret), OPERATOR_KEY_HASHES: secret };
  const name = vi.fn(() => ({})); let count = 0;
  env.FREE_GATE = { idFromName: name, get: () => ({ admit: async () => ({ allowed: ++count <= 12, retryAfterSeconds: count > 12 ? 60 : 0 }) }) } as unknown as typeof env.FREE_GATE;
  const run = vi.fn(); env.AI = { run } as unknown as Ai;
  const response = await operator(new Request('https://operator.invalid/quota-proof', { method: 'POST', headers: { Authorization: `Bearer ${keys[0]}` } }), env);
  expect(response.status).toBe(429); expect(count).toBe(13); expect(run).not.toHaveBeenCalled();
  expect(name).toHaveBeenCalledWith('task6-isolated-quota-v1'); expect(name).not.toHaveBeenCalledWith('global-free-tier-v1');
  expect(await response.json()).toMatchObject({ scope: 'isolated-object-not-production-http429', admitted: 12, denied: 1 });
});
