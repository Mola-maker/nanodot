import { DurableObject } from 'cloudflare:workers';
import { readKeyHashes } from './auth';
import type { GatewayEnv } from './types';

/** Task 2 must use this one name for every model/route/client. Never shard it. */
export const FREE_GATE_NAME = 'global-free-tier-v1';
const DAY_MS = 86_400_000;
const WINDOW_MS = 60_000;

type Daily = { day: number; count: number; last_ms: number };

export class FreeGate extends DurableObject<GatewayEnv> {
  constructor(ctx: DurableObjectState, env: GatewayEnv) {
    super(ctx, env);
    ctx.storage.sql.exec(`
      CREATE TABLE IF NOT EXISTS daily (
        singleton INTEGER PRIMARY KEY CHECK (singleton = 1),
        day INTEGER NOT NULL, count INTEGER NOT NULL, last_ms INTEGER NOT NULL
      );
      CREATE TABLE IF NOT EXISTS admissions (client_id TEXT NOT NULL, at_ms INTEGER NOT NULL);
      CREATE INDEX IF NOT EXISTS admissions_by_client_time ON admissions(client_id, at_ms);
    `);
  }

  /** Internal binding RPC only: caller passes Worker Date.now(), never request time. */
  async admit(clientId: string, nowMs: number): Promise<{ allowed: boolean; retryAfterSeconds: number }> {
    if (!readKeyHashes(this.env.GATEWAY_KEY_HASHES).has(clientId)) throw new Error('Unauthorized client');
    if (!Number.isSafeInteger(nowMs) || nowMs < 0 || nowMs > 8_640_000_000_000_000) throw new Error('Invalid server time');
    return this.ctx.storage.transactionSync(() => {
      const sql = this.ctx.storage.sql;
      const previous = sql.exec<Daily>('SELECT day, count, last_ms FROM daily WHERE singleton = 1').toArray()[0];
      // Clock rollback cannot revive old quota or expire a window early.
      const now = Math.max(nowMs, previous?.last_ms ?? nowMs);
      const day = Math.floor(now / DAY_MS);
      const count = previous?.day === day ? previous.count : 0;
      sql.exec('DELETE FROM admissions WHERE at_ms <= ?', now - WINDOW_MS);
      sql.exec('INSERT OR REPLACE INTO daily(singleton, day, count, last_ms) VALUES (1, ?, ?, ?)', day, count, now);
      const minute = sql.exec<{ count: number; earliest: number | null }>(
        'SELECT COUNT(*) AS count, MIN(at_ms) AS earliest FROM admissions WHERE client_id = ?', clientId,
      ).one();
      let retryMs = count >= 40 ? (day + 1) * DAY_MS - now : 0;
      if (minute.count >= 12) retryMs = Math.max(retryMs, minute.earliest! + WINDOW_MS - now);
      if (retryMs > 0) return { allowed: false, retryAfterSeconds: Math.max(1, Math.ceil(retryMs / 1000)) };
      sql.exec('UPDATE daily SET count = count + 1 WHERE singleton = 1');
      sql.exec('INSERT INTO admissions(client_id, at_ms) VALUES (?, ?)', clientId, now);
      // No refund API: cancellations and upstream errors stay counted.
      return { allowed: true, retryAfterSeconds: 0 };
    });
  }
}
