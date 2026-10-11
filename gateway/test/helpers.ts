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
  return { ...env, INFERENCE_ENABLED: 'true', GATEWAY_KEY_HASHES: secret } as unknown as GatewayEnv;
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

export function wav(seconds = 1, sampleRate = 16000): Uint8Array {
  const data = new Uint8Array(44 + seconds * sampleRate * 2), v = new DataView(data.buffer);
  for (const [offset, value] of [[0,'RIFF'],[8,'WAVE'],[12,'fmt '],[36,'data']] as const) data.set(new TextEncoder().encode(value),offset);
  v.setUint32(4,data.length-8,true); v.setUint32(16,16,true); v.setUint16(20,1,true); v.setUint16(22,1,true);
  v.setUint32(24,sampleRate,true); v.setUint32(28,sampleRate*2,true); v.setUint16(32,2,true); v.setUint16(34,16,true); v.setUint32(40,data.length-44,true);
  return data;
}
