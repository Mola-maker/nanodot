import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { createServer } from 'node:http';
import { readFile, mkdir } from 'node:fs/promises';
import { resolve } from 'node:path';
const require = createRequire(import.meta.url);
const { build } = require('../../harness/dsh-nanomuse/node_modules/esbuild');
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const out = resolve('.superpowers/sdd/2026-10-10-cross-platform-ui/task-4-evidence/desktop-browser');
await mkdir(out, {recursive:true});
await build({entryPoints:['scripts/ui/fixtures/desktop.tsx'],outfile:out+'/fixture.js',bundle:true,platform:'browser',format:'iife', plugins:[{name:'host-seats',setup(b){b.onResolve({filter:/^@deepseek-ai\/dsh-client-ui-primitives$/},()=>({path:'host',namespace:'fixture'}));b.onLoad({filter:/.*/,namespace:'fixture'},()=>({contents:'export const Toast = () => null; export const Modal = () => null; export const Button = () => null; export const Input = () => null;',loader:'js'}));}}]});
const server = createServer(async (req,res) => {
  if (req.url === '/fixture.js') {res.setHeader('content-type','text/javascript');res.end(await readFile(out+'/fixture.js'));}
  else if (req.url?.includes('/nanomuse/')) {res.writeHead(204);res.end();}
  else {res.setHeader('content-type','text/html');res.end('<!doctype html><html><body style="margin:0"><div id="root"></div><script src="/fixture.js"></script></body></html>');}
});
await new Promise(r=>server.listen(5175,'127.0.0.1',r));
const browser=await chromium.launch({executablePath:process.env.CHROMIUM_PATH,headless:true});
try {
 for (const width of [320,768,1440]) {
  const page=await browser.newPage({viewport:{width,height:900},reducedMotion:'reduce'});
  const errors=[];page.on('pageerror',e=>errors.push(e.message));
  await page.goto('http://127.0.0.1:5175');
  await page.getByRole('textbox',{name:'Host composer fixture'}).fill('Draft held by host');
  const tasks=page.getByRole('button',{name:'Tasks',exact:true});await tasks.focus();await page.keyboard.press('Enter');
  for (const [label,id] of [['Feed','nanomuse-feed'],['Ideas','nanomuse-ideas'],['Goals','nanomuse-goals'],['Library','nanomuse-library']]) {
    await page.getByRole('menuitem',{name:label,exact:true}).focus();await page.keyboard.press('Enter');
    assert.equal(await page.getByLabel('Active panel').textContent(),id);await tasks.click();
  }
  await page.keyboard.press('Escape');
  await page.getByRole('button',{name:'Devices',exact:true}).click();assert.equal(await page.getByLabel('Active panel').textContent(),'nanomuse-devices');
  await page.getByRole('button',{name:'Settings',exact:true}).click();assert.equal(await page.getByLabel('Active panel').textContent(),'settings');
  await page.getByRole('button',{name:'Chats',exact:true}).click();
  await page.getByRole('button',{name:'New chat',exact:true}).click();assert.equal(await page.getByLabel('Session count').textContent(),'1');
  await page.evaluate(()=>document.documentElement.style.fontSize='200%');await page.setViewportSize({width:width+20,height:700});
  assert.equal(await page.getByRole('textbox').inputValue(),'Draft held by host');
  assert.equal(await page.evaluate(()=>document.getAnimations().filter(a=>a.effect?.getComputedTiming().iterations===Infinity).length),0);
  await page.locator('body').evaluate(el=>el.setAttribute('data-ds-dark-theme',''));
  await tasks.click(); await page.keyboard.press('ArrowDown'); await page.keyboard.press('Escape'); assert.ok(await tasks.evaluate(el=>document.activeElement===el));
  assert.deepEqual(errors,[]);await page.screenshot({path:out+`/${width}.png`});await page.close();
  console.log(`PASS desktop host fixture ${width}px: native keyboard Tasks/rooms/Devices/Settings/Chats/new chat, retained host draft, resize/font200`);
 }
} finally {await browser.close();await new Promise(r=>server.close(r));}
