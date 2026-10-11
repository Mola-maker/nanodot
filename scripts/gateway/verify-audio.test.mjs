import {test} from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,writeFile,readFile,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {fileURLToPath} from 'node:url';
import {spawnSync} from 'node:child_process';
import {reserveAttempt} from './verify-audio.mjs';
test('attempt ledger refuses 16th combined call and records failed attempts once',async()=>{
 const dir=await mkdtemp(join(tmpdir(),'audio-ledger-')); const path=join(dir,'calls.json');
 try {
  await writeFile(path,JSON.stringify({totalAttempts:14,attempts:[]}));
  const id=await reserveAttempt(path,'candidate'); assert.ok(id);
  const first=JSON.parse(await readFile(path)); assert.equal(first.totalAttempts,15); assert.equal(first.attempts.length,1); assert.equal(first.attempts[0].stage,'candidate');
  await assert.rejects(reserveAttempt(path,'candidate'),/15/);
  assert.deepEqual(JSON.parse(await readFile(path)),first);
 }finally{await rm(dir,{recursive:true,force:true})}
});
test('default and live without Free/probe confirmation make no inference',()=>{
 const script=new URL('./verify-audio.mjs',import.meta.url);
 const offline=spawnSync(process.execPath,[fileURLToPath(script)],{encoding:'utf8',env:{PATH:process.env.PATH}});
 assert.equal(offline.status,0); assert.match(offline.stdout,/No live calls/);
 const denied=spawnSync(process.execPath,[fileURLToPath(script),'--live','--stage','candidate'],{encoding:'utf8',env:{PATH:process.env.PATH}});
 assert.notEqual(denied.status,0); assert.match(denied.stderr,/Free/);
});
test('network failure consumes one attempt without retry or refund',async()=>{
 const {runAttempt}=await import('./verify-audio.mjs'); const dir=await mkdtemp(join(tmpdir(),'audio-failure-')),path=join(dir,'calls.json');
 try {
  await writeFile(path,JSON.stringify({totalAttempts:0,attempts:[]}));let calls=0;
  await assert.rejects(runAttempt(path,'asr',()=>{calls++;throw Error('upstream-secret')}),/remains counted/);
  const ledger=JSON.parse(await readFile(path));assert.equal(calls,1);assert.equal(ledger.totalAttempts,1);assert.equal(ledger.attempts[0].status,'network_failure');assert.ok(!JSON.stringify(ledger).includes('upstream-secret'));
 }finally{await rm(dir,{recursive:true,force:true})}
});
