import assert from 'node:assert/strict'
import { test } from 'node:test'
import { build } from 'esbuild'
import { mkdtemp, writeFile, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { ProfileStore, DEFAULT_PROFILE } from '../lib/profile.js'

const client = await build({
  entryPoints: ['src/client/Avatar.tsx'], bundle: true, format: 'esm', platform: 'node', write: false,
  plugins: [{ name: 'live-stub', setup(builder) {
    builder.onResolve({ filter: /\/live\.ts$/ }, () => ({ path: 'live', namespace: 'stub' }))
    builder.onLoad({ filter: /.*/, namespace: 'stub' }, () => ({ contents: 'export function useLive() { return { motion: {} } }' }))
  } }],
})
const { stillUrl, clipUrl } = await import(`data:text/javascript;base64,${Buffer.from(client.outputFiles[0].text).toString('base64')}`)

test('Dot and legacy or unknown defaults use offline stills without obsolete clips', () => {
  for (const avatar of [undefined, 'dot-v1', 'dragon', 'future-avatar']) {
    const profile = avatar ? { avatar } : undefined
    assert.equal(stillUrl(profile, 'working'), 'nanomuse/assets/dragon-working.webp')
    assert.equal(clipUrl(profile, 'working'), null)
  }
  assert.equal(clipUrl({ avatar: 'emoji' }, 'happy'), null)
})

test('custom avatar stills and matching clips retain their existing paths', () => {
  const profile = { avatar: 'face', faceId: 'abc123' }
  assert.equal(stillUrl(profile, 'happy'), 'nanomuse/assets/face/abc123/happy.webp')
  assert.equal(clipUrl(profile, 'happy', { faceId: 'abc123', clips: { happy: { v: 42 } } }), 'nanomuse/avatar/motion/happy.mp4?v=42')
  assert.equal(clipUrl(profile, 'happy', { faceId: 'other', clips: { happy: { v: 42 } } }), null)
})

test('loading a legacy, unknown, custom or emoji profile preserves the chosen name and custom face', async () => {
  assert.equal(DEFAULT_PROFILE.name, 'Dot')
  assert.equal(DEFAULT_PROFILE.avatar, 'dot-v1')
  const dir = await mkdtemp(join(tmpdir(), 'nanodot-profile-'))
  try {
    for (const avatar of ['dragon', 'future-avatar', 'face', 'emoji']) {
      await writeFile(join(dir, 'profile.json'), JSON.stringify({ name: 'My companion', avatar, faceId: 'abcdef', color: '#ABCDEF' }))
      const store = new ProfileStore(dir, {})
      await store.load()
      assert.equal(store.current().name, 'My companion')
      assert.equal(store.current().avatar, avatar === 'face' || avatar === 'emoji' ? avatar : 'dot-v1')
      assert.equal(store.current().color, '#ABCDEF')
      if (avatar === 'face') assert.equal(store.current().faceId, 'abcdef')
    }
  } finally { await rm(dir, { recursive: true, force: true }) }
})
