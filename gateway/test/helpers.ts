import { env } from 'cloudflare:workers';
import { createExecutionContext } from 'cloudflare:test';
import type { GatewayEnv } from '../src/types';
import { handle } from '../src';

export async function credentials(clientIds = ['client-a']) {
  const keys = clientIds.map(() => crypto.randomUUID());
  const hashes: Record<string, string> = {};
  for (const [index, clientId] of clientIds.entries()) {
    const bytes = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(keys[index]));
    hashes[clientId] = Array.from(new Uint8Array(bytes), byte => byte.toString(16).padStart(2, '0')).join('');
  }
  return { keys, secret: JSON.stringify(hashes) };
}

export function gatewayEnv(secret: string): GatewayEnv {
  return { ...env, GATEWAY_KEY_HASHES: secret } as unknown as GatewayEnv;
}

export function request(key?: string, url = 'https://example.com/v1/chat/completions', body?: string) {
  return new Request(url, {
    method: 'POST',
    headers: key ? { Authorization: `Bearer ${key}` } : {},
    body,
  });
}

export function dispatch(req: Request, bindings: GatewayEnv) {
  return handle(req, bindings, createExecutionContext());
}
