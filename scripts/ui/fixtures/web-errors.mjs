import assert from 'node:assert/strict';
import { response } from './web-runtime.mjs';
export async function checkErrors(browser, url) {
  const page=await browser.newPage({reducedMotion:'reduce'});
  let stateStatus=401;
  let fileStatus=429;
  await page.route('**/api/**',async route=>{
    const path=new URL(route.request().url()).pathname;
    const status=path==='/api/state'?stateStatus:path==='/api/files'?fileStatus:200;
    await route.fulfill({status,contentType:'application/json',body:JSON.stringify(status===429?{detail:'429: Too many requests. Try again later.'}:response(path))});
  });
  await page.goto(url);
  await page.getByRole('alert').filter({hasText:'401'}).waitFor();
  assert.ok(await page.getByRole('textbox',{name:'Access token'}).isVisible());
  stateStatus=200;await page.reload();
  await page.getByRole('navigation',{name:'Main navigation'}).getByRole('button',{name:'Tasks',exact:true}).filter({visible:true}).click();
  await page.getByRole('navigation',{name:'Tasks',exact:true}).getByRole('button',{name:'Library',exact:true}).click();
  const error=page.getByRole('alert').filter({hasText:'429'});await error.waitFor();
  fileStatus=200;await error.getByRole('button',{name:'Try again'}).click();
  await page.getByRole('status').filter({hasText:'Nothing created yet'}).waitFor();
  await page.getByRole('alert').filter({hasText:'Dot is offline'}).waitFor();
  await page.close();console.log('PASS controlled 401 gate, 429 text, owned query retry/empty and offline Dot announcement');
}
