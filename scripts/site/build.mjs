import { cp, mkdir, readFile, rm, writeFile, access, realpath } from 'node:fs/promises';
import { resolve, relative, join, dirname, basename, isAbsolute, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('../../', import.meta.url));
const site = join(root, 'site');
const escape = value => String(value).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const inside = (parent, child) => {
  const path = relative(parent, child);
  return path === '' || (path !== '..' && !path.startsWith(`..${sep}`) && !isAbsolute(path));
};

async function canonicalPath(path) {
  try { return await realpath(path); }
  catch (error) {
    if (error.code !== 'ENOENT') throw error;
    return join(await canonicalPath(dirname(path)), basename(path));
  }
}

export async function buildSite({ outDir, docsDir }) {
  const out = resolve(outDir);
  const docs = resolve(docsDir);
  // Check actual filesystem ancestry, including existing parent aliases, before deletion.
  const [repository, output, documentation, ...sources] = await Promise.all(
    [root, out, docs, site, join(root, 'assets/brand'), join(root, 'scripts/site')].map(canonicalPath),
  );
  const managedOutput = join(repository, 'gateway/public');
  if (inside(output, repository)
      || (inside(repository, output) && output !== managedOutput)
      || inside(output, documentation) || inside(documentation, output)
      || sources.some(source => inside(output, source) || inside(source, output))) {
    throw new Error('Output must be separate from source and docs inputs; repository output is restricted to gateway/public.');
  }
  await access(join(docs, 'index.html'));
  const rows = JSON.parse(await readFile(join(site, 'downloads.json'), 'utf8'));
  for (const row of rows) {
    if (!/^[a-f0-9]{64}$/.test(row.sha256) || !/^https:\/\/github\.com\/Mola-maker\/nanodot\/actions\/runs\/\d+\/artifacts\/\d+$/.test(row.url) || !['available', 'unsigned', 'pending'].includes(row.status)) throw new Error('Invalid evidenced artifact row.');
  }
  const cards = rows.map(row => `<article class="download-card"><h3><span class="en">${escape(row.label)}</span><span class="zh">${escape(row.labelZh)}</span></h3><p><span class="en">${row.status === 'unsigned' ? 'Unsigned; cannot be installed directly. Requires your Apple signing and provisioning.' : 'Temporary P0 build artifact; GitHub sign-in required.'}</span><span class="zh">${row.status === 'unsigned' ? '未签名，不能直接安装。需要你自己的 Apple 签名与配置描述文件。' : '临时 P0 构建产物，需要登录 GitHub。'}</span></p><a class="artifact-link" href="${escape(row.url)}"><span class="en">Open Actions ZIP</span><span class="zh">打开 Actions ZIP</span></a><p class="meta"><span class="en">Expires (UTC)</span><span class="zh">到期（UTC）</span>: <time datetime="${escape(row.expiresAt)}">${escape(row.expiresAt)}</time><span class="expired" hidden><span class="en">Expired; see build docs.</span><span class="zh">已到期，请查看构建文档。</span></span></p><details><summary><span class="en">Artifact ZIP SHA-256</span><span class="zh">产物 ZIP 的 SHA-256</span></summary><code class="checksum">${escape(row.sha256)}</code><p><span class="en">Checksum of the outer GitHub ZIP, not the APK, EXE, DMG or app inside it.</span><span class="zh">这是 GitHub 外层 ZIP 的校验值，不是内部 APK、EXE、DMG 或 app 的校验值。</span></p></details></article>`).join('\n');
  await rm(out, { recursive: true, force: true });
  await mkdir(join(out, 'assets'), { recursive: true });
  await mkdir(join(out, 'connect'), { recursive: true });
  for (const file of ['index.html', 'style.css', 'site.js', 'privacy.html', 'connect/index.html', '404.html', 'downloads.json', 'dot-tokens.css']) {
    const content = await readFile(join(site, file), 'utf8');
    await writeFile(join(out, file), file === 'index.html' ? content.replace('<!-- DOWNLOADS -->', cards) : content);
  }
  await cp(join(root, 'assets/brand/nanodot-avatar/idle.svg'), join(out, 'assets/dot-idle.svg'));
  await cp(join(root, 'assets/brand/nanodot-icon.svg'), join(out, 'assets/nanodot-icon.svg'));
  await cp(docs, join(out, 'docs'), { recursive: true });
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  await buildSite({ outDir: process.argv[2] ?? join(root, 'gateway/public'), docsDir: process.argv[3] ?? join(root, 'website/.vitepress/dist') });
}
