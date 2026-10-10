// The desktop's splash: the app's own logo (the N mark on its white tile, docs/brand.md) in a
// quiet ring, the wordmark, one status line — no picture of any character and no reading of
// the person's face; the error state keeps the layout.
// Read as text — the page is opened in no browser here.
import assert from 'node:assert/strict'
import { existsSync, readFileSync, readdirSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { test } from 'node:test'

const here = dirname(fileURLToPath(import.meta.url))
const resources = join(here, '..', '..', 'desktop', 'resources')
const html = readFileSync(join(resources, 'loading.html'), 'utf8')

test('the splash is the logo in a ring, the wordmark and a status line — no dragon, no face', () => {
  assert.match(html, /<h1>Nanodot<\/h1>/)
  assert.match(html, /class="mark"[^>]*><span class="ring"><\/span><img src="logo\.png"/)
  assert.match(html, /<p id="line" role="status">/)
  assert.doesNotMatch(html, /dragon/i)
  // the dragon's picture was `icon.png`; the splash must not reach for it (or any face) again
  assert.doesNotMatch(html, /\bicon\.png/)
  assert.doesNotMatch(html, /avatar|face\.png|\.webp/i)
  // the page shows its own logo only: no face parameter, no data URL from anywhere
  assert.doesNotMatch(html, /params\.get\("face"\)/)
  assert.doesNotMatch(html, /base64/)
  assert.match(html, /img-src 'self' file: data:/)
})

test('the logo is the brand tile: a 256 px PNG with transparent corners, and the dragon file is gone', () => {
  const logo = join(resources, 'logo.png')
  assert.ok(existsSync(logo))
  const png = readFileSync(logo)
  // PNG signature, then the IHDR chunk: width and height at bytes 16..24, colour type at 25
  assert.equal(png.subarray(0, 8).toString('hex'), '89504e470d0a1a0a')
  assert.equal(png.readUInt32BE(16), 256)
  assert.equal(png.readUInt32BE(20), 256)
  assert.equal(png[25], 6, 'RGBA — the corners outside the rounded tile are transparent')
  assert.ok(!existsSync(join(resources, 'icon.png')), 'resources/icon.png was the dragon; nothing may ship it')
})

test('the error state keeps the layout: the ring stops, the message under it', () => {
  assert.match(html, /window\.__failed = function/)
  assert.match(html, /\.failed \.ring \{ animation: none;/)
  assert.match(html, /\.failed \.error \{ display: block; \}/)
  assert.match(html, /没能启动/)
  assert.match(html, /Could not start/)
})

test('the dragon pictures are gone from the desktop resources', () => {
  assert.ok(existsSync(resources))
  assert.deepEqual(readdirSync(resources).filter((name) => /^dragon-/.test(name)), [])
})
