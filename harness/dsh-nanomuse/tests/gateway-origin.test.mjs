import { test } from 'node:test'
import assert from 'node:assert/strict'
import { gatewayBase, isFresh } from '../lib/gateway-origin.js'
import { listModelRows, loadCatalogue, modelsOf, ownProviderRow } from '../lib/providers.js'
import { readFile } from 'node:fs/promises'

// Real gateway wire fixture through the same HTTP parser and harness model adapter.
test('actual catalogue parser and adapter normalize all bases and use only wire modalities', async () => {
  const entry = (await loadCatalogue()).find(p => p.id === 'nanodot-workers')
  const body = await readFile(new URL('../../../gateway/test/fixtures/models.json', import.meta.url), 'utf8')
  const paths = []
  for (const form of ['', '/', '/v1', '/v1/']) {
    const baseURL = gatewayBase(`https://molamaker.si${form}`)
    const rows = await listModelRows('openai', baseURL, crypto.randomUUID(), async url => {
      paths.push(new URL(url).pathname)
      return new Response(body)
    })
    const models = modelsOf(entry, rows)
    const row = ownProviderRow({ provider: entry.id, label: entry.name, protocol: entry.protocol, baseURL, keyRef: 'fixture_ref', capabilities: entry.capabilities, models, at: 0 })
    assert.deepEqual(row.models.map(m => [m.id, m.input]), [
      ['@cf/google/gemma-4-26b-a4b-it', ['text', 'image']],
      ['@cf/qwen/qwen3-30b-a3b-fp8', ['text']],
    ])
    assert.equal(models.find(m => m.id.includes('whisper')).kind, 'voice')
    assert.equal(models.some(m => m.outputModalities.includes('audio')), false)
    assert.equal(row.baseURL, 'https://molamaker.si/v1')
  }
  assert.deepEqual(paths, ['/v1/models', '/v1/models', '/v1/models', '/v1/models'])
})

test('configuration readiness and account presence precede freshness; origin edits refuse the project key', () => {
  for (const loaded of [false, true]) for (const account of [false, true]) for (const provider of [false, true]) {
    assert.equal(isFresh(loaded, account, provider), loaded && !account && !provider)
  }
  for (const url of ['http://molamaker.si/v1', 'https://molamaker.si:444/v1', 'https://molamaker.si.evil.invalid/v1', 'https://user@molamaker.si/v1', 'https://molamaker.si/v2', 'https://molamaker.si/v1?key=x']) {
    assert.throws(() => gatewayBase(url))
  }
})
