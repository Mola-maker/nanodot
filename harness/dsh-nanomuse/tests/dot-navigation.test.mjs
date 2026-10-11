import { createRequire } from 'node:module';
import { readFileSync } from 'node:fs';
import test, { after } from 'node:test';
import assert from 'node:assert/strict';
import { build } from 'esbuild';
import { mkdtemp, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
const dir = await mkdtemp(join(tmpdir(), 'nanodot-nav-'));
const entry = join(dir, 'entry.ts');
function navigationEntry(cwd) {
  return `import { createElement as h } from ${JSON.stringify(cwd + "/node_modules/react/index.js")};
import { renderToStaticMarkup } from ${JSON.stringify(cwd + "/node_modules/react-dom/server.node.js")};
import { MuseSidebar } from ${JSON.stringify(cwd + "/src/client/MuseSidebar.tsx")};
import { en } from ${JSON.stringify(cwd + "/src/client/locales.ts")};
export function render() { return renderToStaticMarkup(h(MuseSidebar, { collapsed: true, width: 300, t: (key) => en[key] || key, renderSlot: () => null, startSession: () => {}, toggleSidebar: () => {}, selectPanel: () => {}, openProfile: () => {}, issuesUrl: '', chatActions: {}, usePanels: f => f([]), usePanelInfo: f => f({activePanelId: null}), useShortcuts: f => f([]) })); }`;
}
await writeFile(entry, navigationEntry(process.cwd()));
await build({ entryPoints: [entry], outfile: join(dir, 'out.cjs'), bundle: true, platform: 'node', format: 'cjs', plugins: [{ name: 'host-primitives', setup(b) { if (process.env.NAV_BASELINE) b.onLoad({filter:/MuseSidebar\.tsx$/}, () => ({contents: readFileSync(process.env.NAV_BASELINE, 'utf8'), loader:'tsx', resolveDir: process.cwd() + '/src/client' })); b.onResolve({filter: /^@deepseek-ai\/dsh-client-ui-primitives$/}, () => ({path:'host', namespace:'fixture'})); b.onLoad({filter: /.*/, namespace:'fixture'}, () => ({ contents:'export const Toast = () => null; export const Modal = () => null; export const Button = () => null; export const Input = () => null;', loader:'js' })); } }] });
const { render } = createRequire(import.meta.url)(join(dir, 'out.cjs'));
test('desktop keeps four primary destinations accessible through the real sidebar', () => {
  const html = render();
  for (const label of ['Chats', 'Tasks', 'Devices', 'Settings']) assert.ok(html.includes(`aria-label="${label}"`), `Missing ${label}`);
});

for (const cwd of [
  String.raw`D:\a\nanodot\nanodot\harness\dsh-nanomuse`,
  String.raw`C:\Users\Dot's workspace\double "quoted"\harness\dsh-nanomuse`,
  `/tmp/Dot's workspace/double "quoted"/harness/dsh-nanomuse`,
]) {
  test(`generated navigation imports preserve the checkout path: ${cwd}`, async () => {
    // Parse the actual generated entry without resolving a foreign-platform filesystem.
    const result = await build({
      stdin: { contents: navigationEntry(cwd), loader: 'ts', sourcefile: 'entry.ts' },
      bundle: false,
      write: false,
      metafile: true,
    });
    const imports = Object.values(result.metafile.outputs)[0].imports.map(({ path }) => path);
    assert.deepEqual(imports, [
      `${cwd}/node_modules/react/index.js`,
      `${cwd}/node_modules/react-dom/server.node.js`,
      `${cwd}/src/client/MuseSidebar.tsx`,
      `${cwd}/src/client/locales.ts`,
    ]);
  });
}
after(() => rm(dir, { recursive: true, force: true }));
