import type { GatewayEnv } from './types';

/** Only SHA-256 digests and stable client identities belong in this secret. */
export function readKeyHashes(secret: string): Map<string, string> {
  const invalid = () => new Error('Gateway configuration unavailable');
  if (!secret) throw invalid();
  let parsed: unknown;
  try { parsed = JSON.parse(secret); } catch { throw invalid(); }
  if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) throw invalid();
  const entries = Object.entries(parsed);
  if (entries.length === 0) throw invalid();
  const hashes = new Map<string, string>();
  const seen = new Set<string>();
  for (const [clientId, digest] of entries) {
    if (!clientId.trim() || typeof digest !== 'string' || !/^[a-fA-F0-9]{64}$/.test(digest)) throw invalid();
    const normalized = digest.toLowerCase();
    if (seen.has(normalized)) throw invalid();
    hashes.set(clientId, normalized);
    seen.add(normalized);
  }
  return hashes;
}

export async function authenticate(request: Request, env: GatewayEnv): Promise<{ clientId: string } | null> {
  let hashes: Map<string, string>;
  try { hashes = readKeyHashes(env.GATEWAY_KEY_HASHES); } catch { return null; }
  const forbidden = new Set(['key', 'api_key', 'token', 'access_token', 'authorization']);
  for (const name of new URL(request.url).searchParams.keys()) {
    if (forbidden.has(name.toLowerCase())) return null;
  }
  const match = /^Bearer ([A-Za-z0-9._~+/-]+=*)$/i.exec(request.headers.get('Authorization') ?? '');
  if (!match) return null;
  const bytes = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(match[1]));
  const digest = Array.from(new Uint8Array(bytes), byte => byte.toString(16).padStart(2, '0')).join('');
  let identity: string | null = null;
  // Scan every fixed-size digest without early exit on a matching prefix/client.
  for (const [clientId, expected] of hashes) {
    let difference = 0;
    for (let index = 0; index < 64; index++) difference |= digest.charCodeAt(index) ^ expected.charCodeAt(index);
    if (difference === 0) identity = clientId;
  }
  return identity === null ? null : { clientId: identity };
}
