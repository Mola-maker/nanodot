#!/usr/bin/env node
// No automatic retries or production capability changes. Live execution belongs to Task6.
import {readFile,writeFile,mkdir,rm,rename} from 'node:fs/promises';
import {resolve,join} from 'node:path';
import {pathToFileURL} from 'node:url';
import {randomUUID,createHash} from 'node:crypto';
import {spawnSync} from 'node:child_process';
const WHISPER='@cf/openai/whisper-large-v3-turbo', MELO='@cf/myshell-ai/melotts';
const PRODUCTION='https://molamaker.si/v1';
const digest=bytes=>createHash('sha256').update(bytes).digest('hex');
async function lockedLedger(path, mutate) {
  const lock=`${path}.lock`; await mkdir(lock);
  try {
    const value=JSON.parse(await readFile(path,'utf8'));
    if(!Number.isInteger(value.totalAttempts) || value.totalAttempts<0 || value.totalAttempts>15 || !Array.isArray(value.attempts)) throw Error('Use the shared Task6 ledger with totalAttempts and attempts.');
    const result=mutate(value); const temp=`${path}.${randomUUID()}.tmp`;
    await writeFile(temp,JSON.stringify(value,null,2)+'\n',{mode:0o600}); await rename(temp,path); return result;
  }finally{await rm(lock,{recursive:true,force:true})}
}
export async function reserveAttempt(path,stage) {
  return lockedLedger(path,ledger=>{
    if(ledger.totalAttempts>=15) throw Error('Combined live budget of 15 attempts exhausted.');
    const id=randomUUID(); ledger.totalAttempts++;
    ledger.attempts.push({id,stage,startedAt:new Date().toISOString(),status:'reserved'}); return id;
  });
}
export async function runAttempt(path,stage,operation) {
  // Reserve once before dispatch; network failure/abort/refusal does not refund.
  const id=await reserveAttempt(path,stage);
  try {
    const response=await operation();
    await lockedLedger(path,ledger=>{Object.assign(ledger.attempts.find(a=>a.id===id),{status:response.ok?'http_success':'http_failure',httpStatus:response.status})});
    return response;
  }catch{
    await lockedLedger(path,ledger=>{Object.assign(ledger.attempts.find(a=>a.id===id),{status:'network_failure'})});
    throw Error('Voice request failed; attempt remains counted.');
  }
}
function tool(name,args) {
  const result=spawnSync(process.env[name.toUpperCase()] || name,args,{encoding:'utf8'});
  if(result.status!==0) throw Error(`${name} failed; no audio acceptance recorded.`);
  return result.stdout;
}
function decoded(path) {
  // Header sniffing and successful HTTP are insufficient: require full decode.
  const info=JSON.parse(tool('ffprobe',['-v','error','-show_entries','format=duration:stream=codec_name,sample_rate,channels','-of','json',path]));
  const duration=Number(info.format?.duration);
  if(!Number.isFinite(duration) || duration<=0 || duration>120 || !info.streams?.some(s=>s.codec_name==='mp3')) throw Error('Invalid MP3 duration/codec.');
  tool('ffmpeg',['-v','error','-xerror','-i',path,'-f','null','-']);
  return duration;
}
const usage=`No live calls are made by default. Live ASR, decoded Chinese audio and manual listening remain unrun.\n
Task6 only (same total<=15 ledger used by all live checks):
  --live --free-plan-confirmed --stage asr --wav PATH --provenance PATH --nonprivate-confirmed --ledger PATH --out DIR
  --live --free-plan-confirmed --stage candidate --candidate-lang VALUE --probe-base HTTPS_URL --probe-private-confirmed --probe-shared-gate-confirmed [--probe-local-relay-confirmed] --ledger PATH --out DIR
  --stage listen --out DIR --listened-chinese-and-mixed
  --live --free-plan-confirmed --stage production --ledger PATH --out DIR
Project credential comes from NANODOT_TEST_GATEWAY_KEY (legacy GATEWAY_PROJECT_CREDENTIAL accepted); candidate probe uses AUDIO_PROBE_CREDENTIAL.
The temporary private operator probe must share production FreeGate namespace/script_name/global-free-tier-v1. Production remains TTS_ZH_VERIFIED=false while probing.
Candidate success requires MP3 decode/duration AND manual listening; this script never enables production TTS or deploys a probe. No credentials are logged.\n`;
export async function main(argv=process.argv.slice(2)) {
  const args=new Map();
  for(let i=0;i<argv.length;i++) {if(!argv[i].startsWith('--')) throw Error('Use named options.'); const key=argv[i].slice(2); args.set(key,argv[i+1]&&!argv[i+1].startsWith('--')?argv[++i]:true)}
  if(!args.size || args.has('help')) {console.log(usage);return}
  const stage=args.get('stage'), out=args.get('out');
  if(stage==='listen') {
    if(!out || !args.has('listened-chinese-and-mixed')) throw Error('Manual listening must explicitly confirm both Chinese and mixed audio.');
    const path=join(out,'audio-evidence.json'), evidence=JSON.parse(await readFile(path,'utf8'));
    if(!['candidate','production'].includes(evidence.stage) || evidence.clips?.length!==2 || evidence.clips.some(c=>!c.decoded || !(c.durationSeconds>0))) throw Error('Decode both actual MP3 files before listening.');
    for(const clip of evidence.clips) if(digest(await readFile(clip.path))!==clip.sha256) throw Error('Audio changed since decode.');
    evidence.manualListening={chineseAndMixed:true,confirmedAt:new Date().toISOString()};
    evidence.acceptance='Decoded/listened evidence ready for operator review; production flag is unchanged.';
    await writeFile(path,JSON.stringify(evidence,null,2)+'\n'); console.log(evidence.acceptance);return;
  }
  if(!args.has('live') || !args.has('free-plan-confirmed')) throw Error('Live requests require verified Workers Free plan and --live.');
  if(!['asr','candidate','production'].includes(stage) || !out || !args.get('ledger')) throw Error('Supply stage, shared ledger and output directory.');
  // Verify required decoder availability before any billable candidate/production call.
  if(stage!=='asr') {tool('ffprobe',['-version']);tool('ffmpeg',['-version'])}
  let base=PRODUCTION, credential=process.env.NANODOT_TEST_GATEWAY_KEY || process.env.GATEWAY_PROJECT_CREDENTIAL;
  if(stage==='candidate') {
    if(!args.has('probe-private-confirmed') || !args.has('probe-shared-gate-confirmed') || typeof args.get('candidate-lang')!=='string') throw Error('Candidate probe needs private operator credential, exact candidate lang and shared admission confirmation.');
    const url=new URL(args.get('probe-base'));
    const localRelay=args.has('probe-local-relay-confirmed') && url.protocol==='http:' && ['127.0.0.1','localhost','[::1]'].includes(url.hostname);
    if((url.protocol!=='https:' && !localRelay) || url.username || url.password || url.search || url.hash || url.origin===new URL(PRODUCTION).origin) throw Error('Use a distinct private HTTPS operator probe.');
    base=url.href.replace(/\/$/,'');credential=process.env.AUDIO_PROBE_CREDENTIAL;
  }
  if(!credential) throw Error('Required project/probe credential is missing.');
  const ledger=resolve(args.get('ledger')); await mkdir(out,{recursive:true});
  const attempt=(path,body,headers={})=>runAttempt(ledger,stage,()=>fetch(`${base}${path}`,{method:'POST',headers:{Authorization:`Bearer ${credential}`,...headers},body,signal:AbortSignal.timeout(120000),redirect:'error'}));
  if(stage==='asr') {
    if(!args.has('nonprivate-confirmed') || !args.get('wav') || !args.get('provenance')) throw Error('Use an explicitly nonprivate local WAV with provenance.');
    const bytes=await readFile(args.get('wav')), provenance=JSON.parse(await readFile(args.get('provenance'),'utf8'));
    if(bytes.length>2097152 || digest(bytes)!==provenance.sha256 || provenance.sampleRate!==16000 || provenance.channels!==1 || provenance.sampleWidthBytes!==2 || !(provenance.durationSeconds>0 && provenance.durationSeconds<=30)) throw Error('WAV provenance/bounds do not match.');
    const body=new FormData();body.set('file',new Blob([bytes],{type:'audio/wav'}),'nonprivate.wav');body.set('model',WHISPER);body.set('language','zh');body.set('response_format','json');
    const response=await attempt('/audio/transcriptions',body); if(!response.ok) throw Error(`ASR failed HTTP${response.status}; counted once.`);
    const value=await response.json();if(value.error || typeof value.text!=='string' || !value.text.trim()) throw Error('ASR returned no valid transcript.');
    await writeFile(join(out,'asr-evidence.json'),JSON.stringify({stage,fixtureSha256:provenance.sha256,source:provenance.source,transcript:value.text,language:value.language,duration:value.duration,expectedText:provenance.text,intelligibilityReview:'pending human review'},null,2)+'\n');
    console.log('Actual ASR response saved; intelligibility review remains pending.');return;
  }
  const clips=[];
  for(const [name,input] of [['chinese','你好，这是中文语音测试。'],['mixed','你好，Nanodot。请打开 search 搜索框。']]) {
    const response=await attempt('/audio/speech',JSON.stringify({model:MELO,input,voice:'default',response_format:'mp3',speed:1}),{'Content-Type':'application/json'});
    if(!response.ok || !response.headers.get('Content-Type')?.startsWith('audio/mpeg')) throw Error(`Speech unavailable/invalid HTTP${response.status}; counted once.`);
    const bytes=new Uint8Array(await response.arrayBuffer());if(!bytes.length || bytes.length>4194304) throw Error('Speech bytes exceed bounds.');
    const path=resolve(out,`${stage}-${name}.mp3`);await writeFile(path,bytes);const durationSeconds=decoded(path);
    clips.push({name,path,sha256:digest(bytes),decoded:true,durationSeconds});
  }
  await writeFile(join(out,'audio-evidence.json'),JSON.stringify({stage,candidateLang:stage==='candidate'?args.get('candidate-lang'):undefined,clips,manualListening:null,acceptance:'Pending manual Chinese and mixed listening; no production capability change.'},null,2)+'\n');
  console.log(`${stage} decode/duration saved; manual listening remains pending. Production flag unchanged.`);
}
if(process.argv[1] && import.meta.url===pathToFileURL(resolve(process.argv[1])).href) main().catch(()=>{
  // Exceptions from a transport/parser can include credentials or provider content.
  console.error('Audio verification failed. Check Free confirmation, explicit stage/probe gates, shared ledger, local provenance and decoder prerequisites. No acceptance was granted.');process.exitCode=1;
});
