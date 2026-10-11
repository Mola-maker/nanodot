import { test } from 'node:test';
import assert from 'node:assert/strict';
import { cp, mkdir, mkdtemp, readFile, rm, symlink, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const sourceRoot = fileURLToPath(new URL('../../', import.meta.url));

// The actual builder is relocated into a disposable repository-shaped sentinel tree.
// No rejection experiment uses the real checkout as an output or symlink target.
async function fixture() {
  const dir = await mkdtemp(join(tmpdir(), 'nanodot-build-safety-'));
  const repo = join(dir, 'repository');
  const docs = join(dir, 'compiled-docs');
  await mkdir(join(repo, 'scripts/site'), { recursive: true });
  await mkdir(join(repo, 'site'), { recursive: true });
  await mkdir(join(repo, 'assets/brand/nanodot-avatar'), { recursive: true });
  await mkdir(docs);
  await writeFile(join(docs, 'index.html'), '<h1>Compiled docs sentinel</h1>');
  await cp(join(sourceRoot, 'scripts/site/build.mjs'), join(repo, 'scripts/site/build.mjs'));
  for (const name of ['index.html', 'style.css', 'site.js', 'privacy.html', 'connect/index.html', '404.html', 'downloads.json', 'dot-tokens.css']) {
    await mkdir(join(repo, 'site', name, '..'), { recursive: true });
    await cp(join(sourceRoot, 'site', name), join(repo, 'site', name));
  }
  await cp(join(sourceRoot, 'assets/brand/nanodot-avatar/idle.svg'), join(repo, 'assets/brand/nanodot-avatar/idle.svg'));
  await cp(join(sourceRoot, 'assets/brand/nanodot-icon.svg'), join(repo, 'assets/brand/nanodot-icon.svg'));
  const { buildSite } = await import(pathToFileURL(join(repo, 'scripts/site/build.mjs')));
  return { dir, repo, docs, buildSite };
}

for (const target of ['assets/brand', 'assets', 'scripts/site', 'scripts', 'docs', 'site', 'gateway/public/nested']) {
  test(`rejects repository output ${target} before deleting a source sentinel`, async () => {
    const { dir, repo, docs, buildSite } = await fixture();
    try {
      const outDir = join(repo, target);
      await mkdir(outDir, { recursive: true });
      const sentinel = join(outDir, 'source-sentinel');
      await writeFile(sentinel, 'preserve original source');
      const rejection = await buildSite({ outDir, docsDir: docs }).then(() => null, error => error);
      assert.equal(await readFile(sentinel, 'utf8').catch(() => '<deleted>'), 'preserve original source');
      assert.match(rejection?.message ?? 'build succeeded', /Output must be separate from source and docs inputs/);
    } finally { await rm(dir, { recursive: true, force: true }); }
  });
}

test('rejects a sandbox output ancestor alias to consumed artwork before deletion', async () => {
  const { dir, repo, docs, buildSite } = await fixture();
  try {
    const sentinel = join(repo, 'assets/brand/source-sentinel');
    await writeFile(sentinel, 'preserve aliased source');
    const alias = join(dir, 'external-assets-alias');
    await symlink(join(repo, 'assets'), alias, 'dir');
    const rejection = await buildSite({ outDir: join(alias, 'brand'), docsDir: docs }).then(() => null, error => error);
    assert.equal(await readFile(sentinel, 'utf8').catch(() => '<deleted>'), 'preserve aliased source');
    assert.match(rejection?.message ?? 'build succeeded', /Output must be separate from source and docs inputs/);
  } finally { await rm(dir, { recursive: true, force: true }); }
});

for (const target of ['dedicated repository output', 'external temporary output']) {
  test(`builds legitimate ${target} while preserving docs inputs`, async () => {
    const { dir, repo, docs, buildSite } = await fixture();
    try {
      const outDir = target === 'dedicated repository output' ? join(repo, 'gateway/public') : join(dir, 'external-output');
      await buildSite({ outDir, docsDir: docs });
      assert.match(await readFile(join(outDir, 'index.html'), 'utf8'), /Open Actions ZIP/);
      assert.equal(await readFile(join(outDir, 'docs/index.html'), 'utf8'), '<h1>Compiled docs sentinel</h1>');
      assert.equal(await readFile(join(docs, 'index.html'), 'utf8'), '<h1>Compiled docs sentinel</h1>');
    } finally { await rm(dir, { recursive: true, force: true }); }
  });
}

test('rejects an external output containing its docs input without replacing either sentinel', async () => {
  const { dir, buildSite } = await fixture();
  try {
    const outDir = join(dir, 'external-parent');
    const docsDir = join(outDir, 'compiled-docs');
    await mkdir(docsDir, { recursive: true });
    await writeFile(join(docsDir, 'index.html'), '<h1>External docs sentinel</h1>');
    const sentinel = join(outDir, 'output-sentinel');
    await writeFile(sentinel, 'preserve output ancestor');
    await assert.rejects(buildSite({ outDir, docsDir }), /Output must be separate from source and docs inputs/);
    assert.equal(await readFile(sentinel, 'utf8'), 'preserve output ancestor');
    assert.equal(await readFile(join(docsDir, 'index.html'), 'utf8'), '<h1>External docs sentinel</h1>');
  } finally { await rm(dir, { recursive: true, force: true }); }
});
