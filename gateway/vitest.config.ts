import { createHash, randomUUID } from 'node:crypto';
import { cloudflareTest } from '@cloudflare/vitest-plugin';
import { defineConfig } from 'vitest/config';

export default defineConfig({
  plugins: [cloudflareTest({
    remoteBindings: false,
    wrangler: { configPath: './wrangler.jsonc' },
    miniflare: { bindings: { GATEWAY_KEY_HASHES: JSON.stringify(Object.fromEntries(['a', 'b', 'c', 'd'].map(id => [id, createHash('sha256').update(randomUUID()).digest('hex')]))) } },
  })],
});
