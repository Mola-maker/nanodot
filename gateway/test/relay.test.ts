import { expect, it, vi } from 'vitest';
import relay from '../src/operator-relay';
const bearer = `Bearer ${crypto.randomUUID()}`;
it('relay blocks public hosts, browser origins, missing credentials and unknown paths before its service binding', async () => {
  const fetch = vi.fn(async () => new Response('private result'));
  for (const [url, method, headers] of [
    ['https://public.example/v1/audio/speech', 'POST', { Authorization: bearer }],
    ['http://127.0.0.1:8788/v1/audio/speech', 'POST', { Origin: 'https://molamaker.si', Authorization: bearer }],
    ['http://127.0.0.1:8788/v1/audio/speech', 'POST', {}],
    ['http://127.0.0.1:8788/unknown', 'POST', { Authorization: bearer }],
  ] as const) {
    const response = await relay.fetch(new Request(url, { method, headers }), { OPERATOR: { fetch } as unknown as Fetcher });
    expect(response.status).toBeGreaterThanOrEqual(400);
  }
  expect(fetch).not.toHaveBeenCalled();
});
it('relay forwards only the dedicated Bearer through the private service binding', async () => {
  const fetch = vi.fn(async (request: Request) => {
    expect(request.url).toBe('https://private-operator.invalid/v1/audio/speech');
    expect(request.headers.get('Authorization')).toBe(bearer);
    return new Response('private result', { headers: { 'Cache-Control': 'no-store' } });
  });
  const response = await relay.fetch(new Request('http://127.0.0.1:8788/v1/audio/speech', { method: 'POST', headers: { Authorization: bearer }, body: 'test' }), { OPERATOR: { fetch } as unknown as Fetcher });
  expect(response.status).toBe(200); expect(fetch).toHaveBeenCalledTimes(1);
});
