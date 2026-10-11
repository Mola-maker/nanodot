// Controlled protocol regressions for review I1–I3; no relay, credentials or model calls.
import assert from 'node:assert/strict';
import { mkdir } from 'node:fs/promises';
import { response, snapshot, hub } from './web-runtime.mjs';
const evidence='.superpowers/sdd/2026-10-10-cross-platform-ui/task-4-evidence/fix-round-1';
const device={id:'fixture-computer',name:'Fixture workstation',kind:'computer',online:true,actions:[]};
const skill={name:'fixture',description:'Controlled skill suggestion description',source:'yours',enabled:true,path:'/fixture/skill',files:[],allowed_tools:[],metadata:{},updated_at:null};
const activity=[{id:'fixture-activity',ts:new Date().toISOString(),kind:'background',title:'Retained successful activity',text:'Controlled successful request data',thread:'main',thread_title:'Main chat'}];
const posts={instructions:'',daily:false,time:'08:00',generated_at:null,posts:[{id:'fixture-post',ts:new Date().toISOString(),title:'Recovered post',body:'Controlled retry result',area:'other',prompt:''}]};
const upcoming={proactive:false,proactivity:'off',interval_minutes:60,effective_interval_minutes:60,quiet_hours:'',quiet_until:null,next_pass_at:null,queue:[],check_ins:[],reminders:[],triggers:{items:[]},busy:false};
const calendar={configured:false,today:new Date().toISOString().slice(0,10),events:[],feeds:[]};
export async function checkReview(browser,url,issue) {
 await mkdir(evidence,{recursive:true});
 const page=await browser.newPage({viewport:{width:320,height:900},reducedMotion:'reduce'});
 const counts={};const sendTexts=[];const failures=new Set(['/api/feed/posts','/api/upcoming','/api/calendar']);const errors=[];page.on('pageerror',e=>errors.push(e.message));
 await page.route('**/api/**',async route=>{
  const path=new URL(route.request().url()).pathname;counts[path]=(counts[path]||0)+1;
  if(path.endsWith('/send'))sendTexts.push(route.request().postDataJSON()?.text);
  let body=response(path),status=200;
  if(issue==='I1') {
   if(path==='/api/state')body={...snapshot,hub:{...hub,state:'connected',connected:true,account:{signed_in:true},devices:[device]}};
   if(path==='/api/hub')body={...hub,state:'connected',connected:true,account:{signed_in:true},devices:[device]};
   if(path==='/api/skills')body={skills:[skill]};
  }
  if(issue==='I2' && path.endsWith('/send') && counts[path]===1){status=429;body={detail:'429: Controlled send refused. Try again.'};}
  if(issue==='I3') {
   if(path==='/api/feed')body=activity;
   if(path==='/api/feed/posts')body=posts;
   if(path==='/api/upcoming')body=upcoming;
   if(path==='/api/calendar')body=calendar;
   if(failures.has(path)) {
    status=path==='/api/feed/posts'?429:503;body={detail:`${status}: Controlled ${path} failure`};
   }
  }
  await route.fulfill({status,contentType:'application/json',body:JSON.stringify(body)});
 });
 try {
  await page.goto(url);await page.getByRole('navigation',{name:'Main navigation'}).waitFor();
  await page.evaluate(()=>document.documentElement.style.fontSize='200%');
  const composer=page.locator('textarea').first();
  if(issue==='I1') {
   for(const [input,label,expected] of [['/fi','Skills','/fixture '],['@Fixture','Devices','@Fixture workstation ']]) {
    await composer.fill(input);const list=page.getByRole('listbox',{name:label});await list.waitFor();
    const row=list.getByRole('button').first();const rowBox=await row.boundingBox(),listBox=await list.boundingBox();
    assert.ok(rowBox.width>=listBox.width-4,`${label} suggestion retains full list width`);
    assert.ok(rowBox.height>44,`${label} suggestion grows with its text`);
    await row.focus();await page.keyboard.press('Enter');assert.equal(await composer.inputValue(),expected);
   }
   assert.equal(Object.entries(counts).filter(([path])=>path.endsWith('/send')).length,0);
  }
  if(issue==='I2') {
   await composer.fill('Retain and retry this draft');await page.getByRole('button',{name:'Send',exact:true}).click();
   const failure=page.getByText('429: Controlled send refused. Try again.',{exact:true});await failure.waitFor();
   assert.ok(await failure.evaluate(el=>!!el.closest('[role="alert"],[role="status"][aria-live]')),'failed REST send announces its owning error');
   assert.equal(await composer.inputValue(),'Retain and retry this draft');
   await page.getByRole('button',{name:'Send',exact:true}).click();
   await page.waitForFunction(()=>document.querySelector('textarea').value==='');
   assert.equal(Object.entries(counts).filter(([path])=>path.endsWith('/send'))[0][1],2,'explicit retry sends the retained draft once');
   assert.deepEqual(sendTexts,['Retain and retry this draft','Retain and retry this draft'],'retry keeps the owning send payload');
  }
  if(issue==='I3') {
   await page.getByRole('navigation',{name:'Main navigation'}).getByRole('button',{name:'Tasks',exact:true}).click();
   await page.getByText('Retained successful activity',{exact:true}).waitFor();
   for(const path of ['/api/feed/posts','/api/upcoming','/api/calendar']) {
    const error=page.getByRole('alert').filter({hasText:`Controlled ${path} failure`});await error.waitFor({timeout:3000});
   }
   const before={...counts};
   const error=page.getByRole('alert').filter({hasText:'Controlled /api/feed/posts failure'});
   failures.delete('/api/feed/posts');
   await error.getByRole('button',{name:'Try again'}).click();await page.getByText('Recovered post',{exact:true}).waitFor();
   assert.ok(await page.getByText('Retained successful activity',{exact:true}).isVisible());
   assert.equal(counts['/api/feed'],before['/api/feed']);assert.equal(counts['/api/upcoming'],before['/api/upcoming']);assert.equal(counts['/api/calendar'],before['/api/calendar']);assert.equal(counts['/api/feed/posts'],before['/api/feed/posts']+1);
   for(const path of ['/api/upcoming','/api/calendar']) {failures.delete(path);await page.getByRole('alert').filter({hasText:`Controlled ${path} failure`}).getByRole('button',{name:'Try again'}).click();}
   await page.waitForFunction(()=>!Array.from(document.querySelectorAll('[role="alert"]')).some(el=>el.textContent.includes('Controlled /api/')));
   assert.equal(counts['/api/feed'],before['/api/feed']);assert.equal(counts['/api/upcoming'],before['/api/upcoming']+1);assert.equal(counts['/api/calendar'],before['/api/calendar']+1);
  }
  assert.deepEqual(errors,[],'controlled real UI runtime errors');
  await page.screenshot({path:`${evidence}/${issue}-${process.env.UI_PHASE||'check'}.png`});
  console.log(`PASS controlled ${issue}: real DOM at 320px/font200; no model calls`);
 } catch(error) {console.error('Controlled request counts:',JSON.stringify(counts));await page.screenshot({path:`${evidence}/${issue}-${process.env.UI_PHASE||'check'}-failure.png`});throw error;}
 finally {await page.close();}
}
