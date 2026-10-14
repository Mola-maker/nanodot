import { env } from 'cloudflare:workers';
import { evictDurableObject, runInDurableObject } from 'cloudflare:test';
import { describe, expect, it } from 'vitest';
import type { GatewayEnv } from '../src/types';

const START = Date.UTC(2026, 9, 11, 12);
async function gate() {
  const namespace = (env as unknown as GatewayEnv).FREE_GATE;
  return namespace.get(namespace.idFromName(crypto.randomUUID()));
}

describe('atomic free-tier quota', () => {
  it('admits exactly 40 concurrent requests globally across authorized clients', async () => {
    const clients = ['a', 'b', 'c', 'd'];
    const object = await gate();
    const results = await Promise.all(Array.from({ length: 41 }, (_, index) => object.admit(clients[index % 4], START)));
    expect(results.filter(result => result.allowed)).toHaveLength(40);
    expect(results.filter(result => !result.allowed)).toEqual([{ allowed: false, retryAfterSeconds: 43200 }]);
  });

  it('denies the thirteenth admission and expires a rolling window exactly at 60 seconds', async () => {
    const object = await gate();
    for (let index = 0; index < 12; index++) expect((await object.admit('a', START + index * 1000)).allowed).toBe(true);
    expect(await object.admit('a', START + 59000)).toEqual({ allowed: false, retryAfterSeconds: 1 });
    expect(await object.admit('a', START + 60000)).toEqual({ allowed: true, retryAfterSeconds: 0 });
    expect(await object.admit('a', START + 60001)).toEqual({ allowed: false, retryAfterSeconds: 1 });
  });

  it('resets the global count at UTC midnight without resetting a client rolling window', async () => {
    const object = await gate();
    const midnight = Date.UTC(2026, 9, 12);
    for (let index = 0; index < 40; index++) await object.admit(['a', 'b', 'c', 'd'][index % 4], midnight - 1000);
    expect((await object.admit('b', midnight - 1)).allowed).toBe(false);
    expect((await object.admit('a', midnight)).allowed).toBe(true);
    expect((await object.admit('a', midnight + 1)).allowed).toBe(true);
    expect(await object.admit('a', midnight + 2)).toEqual({ allowed: false, retryAfterSeconds: 59 });
  });

  it('keeps admissions counted after downstream failures and client cancellations', async () => {
    const object = await gate();
    for (let index = 0; index < 12; index++) {
      expect((await object.admit('a', START)).allowed).toBe(true);
      try { throw new Error('downstream failed or was cancelled'); } catch { /* admission stays spent */ }
    }
    expect(await object.admit('a', START)).toEqual({ allowed: false, retryAfterSeconds: 60 });
  });

  it('rejects unknown clients and invalid time before storing counters', async () => {
    const object = await gate();
    const rejected = await runInDurableObject(object, async instance => {
      const results: boolean[] = [];
      for (const [client, time] of [['unknown', START], ['a', NaN], ['a', Infinity], ['a', -1], ['a', 1.5]] as const) {
        try { await instance.admit(client, time); results.push(false); } catch { results.push(true); }
      }
      return results;
    });
    expect(rejected).toEqual([true, true, true, true, true]);
    for (let index = 0; index < 12; index++) expect((await object.admit('a', START)).allowed).toBe(true);
  });

  it('persists daily counters in SQLite and cleans expired minute entries', async () => {
    const object = await gate();
    await object.admit('a', START);
    await object.admit('a', START + 60000);
    await runInDurableObject(object, async (_instance, state) => {
      expect(state.storage.sql.exec<{ count: number }>('SELECT count FROM daily').one().count).toBe(2);
      expect(state.storage.sql.exec<{ count: number }>('SELECT COUNT(*) AS count FROM admissions').one().count).toBe(1);
    });
  });

  it('preserves limits after object eviction', async () => {
    const object = await gate();
    for (let index = 0; index < 12; index++) await object.admit('a', START);
    await evictDurableObject(object);
    expect(await object.admit('a', START)).toEqual({ allowed: false, retryAfterSeconds: 60 });
  });

  it('does not reopen a previous day when the server clock moves backwards', async () => {
    const object = await gate();
    for (let index = 0; index < 40; index++) await object.admit(['a', 'b', 'c', 'd'][index % 4], START);
    expect((await object.admit('a', START - 86400000)).allowed).toBe(false);
  });
});
