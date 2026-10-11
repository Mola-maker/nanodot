import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { checkErrors } from './fixtures/web-errors.mjs';
import { response } from './fixtures/web-runtime.mjs';
const require = createRequire(import.meta.url);
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const browser = await chromium.launch({ executablePath: process.env.CHROMIUM_PATH, headless: true });
try {
  for (const width of [320, 768, 1440]) {
    const page = await browser.newPage({ viewport: { width, height: 900 }, reducedMotion: 'reduce' });
    const errors = [];
    page.on("pageerror", e => { errors.push(e.message); console.error("PAGE ERROR", e.message); });
    let submissions = 0;
    let creates = 0;
    await page.route('**/api/**', async route => {
      const path = new URL(route.request().url()).pathname;
      if (path.includes('/send')) { submissions++; await new Promise(r=>setTimeout(r,100)); await route.fulfill({status:429,contentType:'application/json',body:JSON.stringify({detail:'429: Try again later'})}); return; }
      if (path === '/api/threads' && route.request().method() === 'POST') { creates++; await route.fulfill({contentType:'application/json',body:JSON.stringify({id:'fixture-side',title:'Fixture side chat',events:0,busy:false})}); return; }
      await route.fulfill({ contentType: 'application/json', body: JSON.stringify(response(path)) });
    });
    await page.goto(process.env.UI_BASE_URL || 'http://127.0.0.1:5173');
    const nav = page.getByRole('navigation', { name: 'Main navigation' });
    await nav.getByRole('button', { name: 'Tasks', exact: true }).waitFor();
    const composer = page.locator('textarea').first();
    await composer.fill('Keep my draft');
    await nav.getByRole('button', { name: 'Tasks', exact: true }).focus();
    await page.keyboard.press('Enter');
    const rooms = page.getByRole('navigation', { name: 'Tasks' });
    for (const label of ['Feed', 'Ideas', 'Goals', 'Library']) {
      await rooms.getByRole('button', { name: label, exact: true }).click();
    }
    await nav.getByRole('button', { name: 'Devices', exact: true }).click();
    await page.getByRole('button', { name: 'Back', exact: true }).click();
    assert.equal(await composer.inputValue(), 'Keep my draft');
    await nav.getByRole('button', { name: 'Settings', exact: true }).click();
    await page.getByRole('button', { name: 'Back', exact: true }).click();
    await page.keyboard.press('Control+k');
    assert.ok(await page.getByPlaceholder('Search chats').filter({visible:true}).isVisible());
    await page.keyboard.press('Escape');
    await page.keyboard.press('Meta+k');
    assert.ok(await page.getByPlaceholder('Search chats').filter({visible:true}).isVisible());
    if (width < 1024) await page.getByRole('button',{name:'New side chat',exact:true}).filter({visible:true}).click();
    else { await page.getByPlaceholder('New side chat',{exact:true}).fill('Fixture side chat'); await page.getByRole('button',{name:'New',exact:true}).filter({visible:true}).click(); }
    await page.waitForFunction(() => document.body.textContent.includes('Fixture side chat'));
    assert.equal(creates,1,'new chat creates once');
    await page.keyboard.press('Escape');
    for (const font of ['100%', '150%', '200%']) {
      await page.evaluate(font => document.documentElement.style.fontSize = font,font);
      await nav.getByRole('button',{name:'Tasks',exact:true}).focus();
      await page.keyboard.press('Enter');
      for (const room of ['Feed','Ideas','Goals','Library']) await page.getByRole('navigation',{name:'Tasks'}).getByRole('button',{name:room,exact:true}).click();
      await nav.getByRole('button',{name:'Devices',exact:true}).click();
      await page.getByRole('button',{name:'Back',exact:true}).click();
      await nav.getByRole('button',{name:'Settings',exact:true}).click();
      await page.getByRole('button',{name:'Back',exact:true}).click();
      assert.equal(await composer.inputValue(),'Keep my draft','draft survives enlarged-font routes');
      const send=page.getByRole('button',{name:'Send',exact:true});
      assert.ok(await send.isVisible());
      const bounds=await send.boundingBox(); assert.ok(bounds.y+bounds.height<=900);
    }
    await page.setViewportSize({ width: width + 20, height: 700 });
    assert.equal(submissions, 0, 'resize must not submit');
    const sendBounds=await page.getByRole('button',{name:'Send',exact:true}).boundingBox();
    assert.ok(sendBounds.x>=0 && sendBounds.x+sendBounds.width<=width+20 && sendBounds.y+sendBounds.height<=700, 'scaled composer send remains inside viewport after resize');
    assert.ok((await composer.boundingBox()).width>=100, 'scaled draft remains readable');
    assert.equal(await composer.inputValue(), 'Keep my draft');
    assert.equal(await page.evaluate(() => document.getAnimations().filter(a => a.effect?.getComputedTiming().iterations === Infinity).length), 0);
    await composer.focus(); await page.keyboard.press('Enter'); await page.keyboard.press('Enter');
    await page.waitForTimeout(150); assert.equal(submissions,1,'one in-flight submission despite repeated Enter');
    assert.equal(await composer.inputValue(),'Keep my draft','failed send restores draft');
    await page.waitForTimeout(3500);
    await page.evaluate(()=> { document.documentElement.dataset.theme='dark'; document.documentElement.dataset.dotTheme='dark'; });
    await page.screenshot({path:`.superpowers/sdd/2026-10-10-cross-platform-ui/task-4-evidence/web-${width}.png`});
    assert.deepEqual(errors, [], "UI runtime errors");
    await page.close();
    console.log(`PASS ${width}px: rooms, back, draft, keyboard, 200% font, resize, reduced motion`);
  }
  await checkErrors(browser,process.env.UI_BASE_URL || 'http://127.0.0.1:5173');
} finally { await browser.close(); }
