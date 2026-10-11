// Runs the real Linux desktop shell with its staged harness in a task-owned profile.
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { mkdir, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
const require=createRequire(import.meta.url);
const {_electron}=require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const evidence=resolve('.superpowers/sdd/2026-10-10-cross-platform-ui/task-4-evidence/electron');
await mkdir(evidence,{recursive:true,mode:0o700});
process.env.ELECTRON_CACHE=evidence+"/electron-cache";
await mkdir(evidence+'/harness-profile/profiles/nanomuse',{recursive:true});
await mkdir(evidence+'/workspaces',{recursive:true});
await writeFile(evidence+'/harness-profile/profiles/nanomuse/cordis.patch.yml', `- id: workspace-controller\n  config:\n    documentsDirectory: ${JSON.stringify(evidence+'/workspaces')}\n`);
const env={...process.env,NANOMUSE_DESKTOP_HOME:evidence+'/harness-profile',XDG_CONFIG_HOME:evidence+'/config',XDG_CACHE_HOME:evidence+'/cache',XDG_DATA_HOME:evidence+'/data'};
delete env.ELECTRON_RUN_AS_NODE;
const app=await _electron.launch({executablePath:require('../../harness/desktop/node_modules/electron'),args:['--no-sandbox','--user-data-dir='+evidence+'/user-data',resolve('harness/desktop')],env,timeout:60000});
try {
 const page=await app.firstWindow();
 await page.getByRole('button',{name:'Tasks',exact:true}).waitFor({timeout:60000});
 await page.screenshot({path:evidence+'/start.png'});
 // The shipped onboarding gear opens Settings and completes this task profile's wizard.
 const gear=page.locator('.nm-fr-gear');
 if(await gear.isVisible()) { await gear.click(); await page.locator('.nm-settings .nm-close').click(); }
 await page.screenshot({path:evidence+'/after-onboarding.png'});
 await writeFile(evidence+'/buttons.json',JSON.stringify(await page.locator('button').evaluateAll(buttons=>buttons.map(b=>({label:b.getAttribute('aria-label'),text:b.textContent}))),null,2));
 await page.getByRole('button',{name:/^New chat$|^New side chat$|^New Session$/}).first().click();
 const editable=page.locator('textarea, [contenteditable="true"]').first();
 await editable.fill('Local desktop draft');
 await app.evaluate(({BrowserWindow})=>BrowserWindow.getAllWindows()[0].setSize(960,900));
 console.log('actual compact native size', await app.evaluate(({BrowserWindow})=>BrowserWindow.getAllWindows()[0].getSize()));
 await page.getByRole('button',{name:'Tasks',exact:true}).click();
 await page.getByRole('menuitem',{name:'Goals',exact:true}).click();
 await page.getByRole('button',{name:'Chats',exact:true}).click();
 assert.equal(await editable.evaluate(el => el instanceof HTMLTextAreaElement ? el.value : el.textContent),'Local desktop draft');
 await page.evaluate(()=>document.documentElement.style.fontSize='200%');
 const send=page.locator('[data-composer-card] button[class*="_primary"]');
 const bounds=await send.boundingBox();
 assert.ok(bounds.width>=44 && bounds.height>=44 && bounds.x+bounds.width<=960 && bounds.y+bounds.height<=900,'native enlarged-font send target stays accessible');
 await writeFile(evidence+'/layout-font200.json',JSON.stringify(await page.evaluate(()=>Array.from(document.querySelectorAll('[class*="_frame"],.nm-sidebar,.nm-rail')).map(el=>({className:el.className,width:el.getBoundingClientRect().width,columns:getComputedStyle(el).gridTemplateColumns,inline:el.getAttribute('style')}))),null,2));
 assert.ok(await editable.evaluate(el=>parseFloat(getComputedStyle(el).fontSize)>=28),'native draft text enlarges');
 assert.ok(await page.locator('.nm-rail').evaluate(el=>{const frame=el.closest('[class*="_frame"]');return getComputedStyle(frame).gridTemplateColumns.split(' ')[0].replace('px','')>=el.getBoundingClientRect().width;}),'native frame keeps enlarged rail labels visible');
 await page.screenshot({path:evidence+'/compact-font200.png'});
 await page.evaluate(()=>document.documentElement.style.fontSize='100%');
 await app.evaluate(({BrowserWindow})=>BrowserWindow.getAllWindows()[0].setSize(1440,900));
 console.log('actual wide native size', await app.evaluate(({BrowserWindow})=>BrowserWindow.getAllWindows()[0].getSize()));
 await page.screenshot({path:evidence+'/wide.png'});
 console.log('PASS actual Linux Electron shell: start, real plugin navigation, resize, input draft retained');
} finally {await app.close();}
