import { test } from 'node:test';
import assert from 'node:assert/strict';
import { access, readFile, mkdtemp, mkdir, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const root = new URL('../../', import.meta.url);
test('builds self-contained bilingual Nanodot pages and copies docs without legacy demos', async () => {
  const builder = new URL('scripts/site/build.mjs', root);
  assert.equal(await access(builder).then(() => true, () => false), true, 'site build interface is missing');
  const { buildSite } = await import(builder);
  const dir = await mkdtemp(join(tmpdir(), 'nanodot-site-'));
  try {
    const docsDir = join(dir, 'docs');
    const outDir = join(dir, 'public');
    await mkdir(docsDir);
    await writeFile(join(docsDir, 'index.html'), '<h1>Built docs fixture</h1>');
    await buildSite({ outDir, docsDir });
    assert.match(await readFile(join(outDir, 'docs/index.html'), 'utf8'), /Built docs fixture/);
    for (const file of ['index.html', 'connect/index.html', 'privacy.html', '404.html']) {
      const html = await readFile(join(outDir, file), 'utf8');
      assert.match(html, /Mola-maker\/nanodot/);
      for (const landmark of ['header', 'nav', 'main', 'footer']) assert.match(html, new RegExp(`<${landmark}\\b`));
      assert.match(html, /class="en"/);
      assert.match(html, /class="zh"/);
      assert.match(html, /id="theme"/);
      assert.doesNotMatch(html, /<iframe|demo\.nanomuse|testflight\.apple|fonts\.googleapis/i);
    }
    const home = await readFile(join(outDir, 'index.html'), 'utf8');
    assert.match(home, /assets\/dot-idle\.svg/);
    await access(join(outDir, 'assets/dot-idle.svg'));
    assert.match(home, /Preview|预览/);
    assert.match(home, /cannot be installed directly/);
    assert.match(home, /P0/);
    assert.equal(await access(join(outDir, 'legacy.html')).then(() => true, () => false), false);
    const connect = await readFile(join(outDir, 'connect/index.html'), 'utf8');
    assert.doesNotMatch(connect, /<input|<textarea|<form/);
    assert.match(connect, /https:\/\/molamaker\.si\/v1/);
  } finally { await rm(dir, { recursive: true, force: true }); }
});

test('download URLs and SHA-256 refer to the same evidenced P0 artifact ZIP', async () => {
  const file = new URL('site/downloads.json', root);
  assert.equal(await access(file).then(() => true, () => false), true, 'evidenced download manifest is missing');
  const rows = JSON.parse(await readFile(file, 'utf8'));
  const dossier = JSON.parse(await readFile(new URL('site/tests/fixtures/p0-artifacts.json', root), 'utf8'));
  assert.equal(rows[0].platform, 'android-arm64');
  assert.equal(rows.length, 6);
  for (const row of rows) {
    assert.match(row.sha256, /^[a-f0-9]{64}$/);
    assert.ok(['available', 'unsigned', 'pending'].includes(row.status));
    const artifact = dossier.artifacts.find(a => row.url.endsWith(`/artifacts/${a.id}`));
    assert.ok(artifact, `missing P0 artifact evidence: ${row.platform}`);
    assert.equal(row.url, `https://github.com/Mola-maker/nanodot/actions/runs/${artifact.workflow_run.id}/artifacts/${artifact.id}`);
    assert.equal(row.sha256, artifact.digest.replace('sha256:', ''));
    assert.equal(row.expiresAt, artifact.expires_at);
    assert.equal(row.checksumScope, 'GitHub artifact ZIP');
  }
  assert.equal(rows.find(row => row.platform === 'ios-arm64').status, 'unsigned');
});
