import test, { after } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, writeFileSync, mkdirSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { spawnSync } from 'node:child_process';
import { buildSync, transformSync } from 'esbuild';
import { runInNewContext } from 'node:vm';

const root = resolve(process.cwd(), '../..');
const temporary = mkdtempSync(join(tmpdir(), 'nanodot-brand-'));
const stylePath = 'harness/dsh-nanomuse/src/client/styles.ts';
const tokensPath = 'harness/dsh-nanomuse/src/client/dot-tokens.ts';
const originalStyle = readFileSync(join(root, stylePath), 'utf8');
let sequence = 0;

function fixture() {
  const dir = join(temporary, String(sequence++));
  mkdirSync(join(dir, 'config'), { recursive: true });
  mkdirSync(join(dir, 'harness/dsh-nanomuse/src/client'), { recursive: true });
  mkdirSync(join(dir, 'web/src'), { recursive: true });
  writeFileSync(join(dir, stylePath), originalStyle);
  writeFileSync(join(dir, tokensPath), readFileSync(join(root, tokensPath)));
  writeFileSync(join(dir, 'web/src/index.css'), readFileSync(join(root, 'web/src/index.css')));
  writeFileSync(join(dir, 'web/src/dot-tokens.css'), readFileSync(join(root, 'web/src/dot-tokens.css')));
  writeFileSync(join(dir, 'config/ui.nanodot.json'), readFileSync(join(root, 'config/ui.nanodot.json')));
  const profile = JSON.parse(readFileSync(join(root, 'config/brand.nanodot.json'), 'utf8'));
  profile.themeColors.light.primary = '#325D88';
  profile.themeColors.dark.primary = '#BAD6F3';
  writeFileSync(join(dir, 'config/brand.nanodot.json'), JSON.stringify(profile));
  const preferences = '{"color":"#A0467B","avatar":"my-custom-avatar","theme":"dark"}';
  writeFileSync(join(dir, 'profile.json'), preferences);
  return { dir, preferences };
}

function rebrand(dir) {
  const result = spawnSync(process.env.PYTHON || (process.platform === 'win32' ? 'python' : 'python3'), [
    '-c',
    'import sys; from pathlib import Path; from scripts.rebrand import apply_brand; from scripts.brand_profile import load_brand; root=Path(sys.argv[1]); print(len(apply_brand(root, load_brand(root / "config/brand.nanodot.json"))))',
    dir,
  ], { cwd: root, encoding: 'utf8' });
  assert.equal(result.status, 0, result.stderr || result.error?.message);
  return Number(result.stdout.trim());
}

function emittedStyles(dir) {
  const result = buildSync({ entryPoints: [join(dir, stylePath)], bundle: true, platform: 'node', format: 'cjs', write: false, logLevel: 'silent' });
  const properties = new Map();
  let style;
  const module = { exports: {} };
  const document = {
    documentElement: { setAttribute() {}, removeAttribute() {}, style: { setProperty: (key, value) => properties.set(key, value), removeProperty: key => properties.delete(key) } },
    getElementById: () => style,
    createElement: () => ({ textContent: '', remove() {} }),
    head: { appendChild(value) { style = value; } },
  };
  runInNewContext(result.outputFiles[0].text, { module, exports: module.exports, document });
  module.exports.ensureStyles();
  module.exports.setAccent('#A0467B');
  transformSync(style.textContent, { loader: 'css', logLevel: 'silent' });
  return { css: style.textContent, properties };
}

function declarations(css, selector) {
  const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const blocks = [...css.matchAll(new RegExp(escaped + ' \\{([^}]+)\\}', 'g'))];
  assert.ok(blocks.length, `Missing emitted selector ${selector}`);
  const output = new Map();
  for (const block of blocks) for (const declaration of block[1].matchAll(/(--[\w-]+)\s*:\s*([^;]+);/g)) output.set(declaration[1], declaration[2].trim());
  return output;
}

function contrast(first, second) {
  const luminance = color => {
    const values = [1, 3, 5].map(index => parseInt(color.slice(index, index + 2), 16) / 255);
    const linear = values.map(value => value <= 0.04045 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4);
    return linear[0] * 0.2126 + linear[1] * 0.7152 + linear[2] * 0.0722;
  };
  const values = [luminance(first), luminance(second)].sort((a, b) => a - b);
  return (values[1] + 0.05) / (values[0] + 0.05);
}

test('rebrand preserves the executable Desktop template and user preferences', () => {
  const { dir, preferences } = fixture();
  assert.ok(rebrand(dir) > 0);
  const updated = readFileSync(join(dir, stylePath), 'utf8');
  transformSync(updated, { loader: 'ts', logLevel: 'silent' });
  assert.equal(updated, originalStyle);
  assert.equal(readFileSync(join(dir, 'profile.json'), 'utf8'), preferences);
  assert.equal(rebrand(dir), 0);
});

test('rebrand refreshes token defaults for both modes without changing a saved accent', () => {
  const { dir } = fixture();
  rebrand(dir);
  const source = readFileSync(join(dir, tokensPath), 'utf8');
  const tokens = JSON.parse(source.match(/export const dotTokens = ([\s\S]+) as const;/)[1]);
  assert.equal(tokens.colors.light.primary, '#325D88');
  assert.equal(tokens.colors.dark.primary, '#BAD6F3');
  const { properties } = emittedStyles(dir);
  assert.equal(properties.get('--nm-profile-accent'), '#A0467B');
});

test('the real Desktop emits themed text and button roles with profile fallback', () => {
  const { dir } = fixture();
  rebrand(dir);
  const { css } = emittedStyles(dir);
  const light = declarations(css, 'html[data-nanomuse]');
  const surface = declarations(css, 'html[data-nanomuse] body');
  const dark = declarations(css, 'html[data-nanomuse] body[data-ds-dark-theme]');
  assert.equal(light.get('--nm-accent'), 'var(--nm-profile-accent, #325D88)');
  assert.equal(dark.get('--nm-accent'), 'var(--nm-profile-accent, #BAD6F3)');
  assert.equal(light.get('--nm-on-primary'), '#FFFFFF');
  assert.equal(dark.get('--nm-on-primary'), '#101D30');
  assert.equal(surface.get('--dsw-alias-label-secondary'), '#1C3552');
  assert.equal(dark.get('--dsw-alias-label-secondary'), '#E8F0FD');
  assert.equal(light.get('--nm-muted'), '#1C3552');
  assert.equal(dark.get('--nm-muted'), '#E8F0FD');
  for (const mode of [surface, dark]) {
    assert.ok(contrast(mode.get('--dsw-alias-label-secondary'), mode.get('--nm-base')) >= 4.5);
    assert.ok(contrast(mode.get('--dsw-alias-label-tertiary'), mode.get('--nm-field')) >= 4.5);
  }
  for (const mode of [light, dark]) {
    const fallback = mode.get('--nm-accent').match(/#[A-Fa-f0-9]{6}/)[0];
    assert.ok(contrast(mode.get('--nm-on-primary'), fallback) >= 4.5);
  }
});

test('rebrand retains Web token consumers and refreshes both saved-theme defaults', () => {
  const { dir } = fixture();
  const before = readFileSync(join(dir, 'web/src/index.css'), 'utf8');
  rebrand(dir);
  const after = readFileSync(join(dir, 'web/src/index.css'), 'utf8');
  const sourceTokens = readFileSync(join(dir, 'web/src/dot-tokens.css'), 'utf8');
  const light = declarations(sourceTokens, ':root, [data-dot-theme="light"]');
  const dark = declarations(sourceTokens, '[data-dot-theme="dark"]');
  assert.equal(light.get('--dot-primary'), '#325D88');
  assert.equal(dark.get('--dot-primary'), '#BAD6F3');
  for (const [selector, palette] of [[':root', light], ['html[data-theme="dark"]', dark]]) {
    const consumer = declarations(after, selector);
    const mutedRole = consumer.get('--om-muted').match(/var\((--dot-[\w-]+)\)/)[1];
    assert.ok(contrast(palette.get(mutedRole), palette.get('--dot-background')) >= 4.5);
  }
  // The legacy literal blocks also change with the brand; the token consumer stays intact.
  const tokenBlocks = source => [...source.matchAll(/(?:^:root|^html\[data-theme="dark"\]) \{[^}]*var\(--dot-[^}]+\}/gm)].map(match => match[0]);
  assert.deepEqual(tokenBlocks(after), tokenBlocks(before));
  assert.equal(rebrand(dir), 0);
});

after(() => rmSync(temporary, { recursive: true, force: true }));
