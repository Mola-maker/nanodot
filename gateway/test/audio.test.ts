import { expect, it, vi } from 'vitest';
import { credentials, dispatch, gatewayEnv, wav } from './helpers';
const whisper = '@cf/openai/whisper-large-v3-turbo', melo = '@cf/myshell-ai/melotts';
async function setup(output: unknown = {text:'你好',transcription_info:{language:'zh',duration:1}}) {
  const {keys,secret} = await credentials(); const env = gatewayEnv(secret); const events: string[] = [];
  const run = vi.fn(async () => { events.push('ai'); return output; });
  const admit = vi.fn(async (_clientId:string, _now:number) => { events.push('admit'); return {allowed:true,retryAfterSeconds:0}; });
  env.AI = {run} as any; env.FREE_GATE = {idFromName:vi.fn((id:string)=>id),get:vi.fn(()=>({admit}))} as any;
  const headers = {Authorization:`Bearer ${keys[0]}`};
  const asr = (data = wav(), fields: Record<string,string> = {}) => {
    const f = new FormData(); f.set('file',new Blob([data.slice().buffer],{type:'audio/wav'}),'voice.wav');
    for (const [k,v] of Object.entries({model:whisper,language:'zh-CN',response_format:'json',...fields})) f.set(k,v);
    return dispatch(new Request('https://example.com/v1/audio/transcriptions',{method:'POST',headers,body:f}),env);
  };
  const speech = (fields: Record<string,unknown> = {}) => dispatch(new Request('https://example.com/v1/audio/speech',{method:'POST',headers,body:JSON.stringify({model:melo,input:'你好',voice:melo,response_format:'mp3',...fields})}),env);
  return {env,events,run,admit,asr,speech,headers};
}
it('bounds whole WAV, normalizes Chinese language and uses shared admission before transcribe', async () => {
  const s=await setup(); const r=await s.asr(); expect(r.status).toBe(200);
  expect(await r.json()).toEqual({text:'你好',language:'zh',duration:1}); expect(s.events).toEqual(['admit','ai']);
  const [model,input] = s.run.mock.calls[0] as unknown as [string,any];
  expect(model).toBe(whisper); expect(input).toEqual({audio:btoa(String.fromCharCode(...wav())),task:'transcribe',language:'zh'});
  expect(s.env.FREE_GATE.idFromName).toHaveBeenCalledWith('global-free-tier-v1'); expect(s.admit.mock.calls[0][0]).toBe('client-a');
});
it('rejects invalid ASR before admission and never passes optional unknown controls', async () => {
  const s=await setup();
  for (const [data,fields,status] of [[wav(0),{},400],[wav(31),{},400],[new Uint8Array(2097153),{},413],[wav(),{model:'whisper-1'},400],[wav(),{response_format:'text'},400],[wav(),{language:'../../zh'},400],[wav(),{prompt:'hint'},400]] as const) {
    expect((await s.asr(data,fields)).status).toBe(status);
  }
  expect(s.admit).not.toHaveBeenCalled(); expect(s.run).not.toHaveBeenCalled();
});
it('rejects error JSON or invalid text instead of inventing a transcription', async () => {
  for (const output of [{error:'unavailable',text:'fake'}, {success:false,text:'fake'}, {text:7}, {}, '<html>failure</html>']) {
    const s=await setup(output); const r=await s.asr(); expect(r.status).toBe(503); expect(await r.json()).toHaveProperty('error'); expect(s.admit).toHaveBeenCalledTimes(1);
  }
});
it('returns quota refusal and keeps cancelled or failed admitted calls counted', async () => {
  const s=await setup(); s.admit.mockResolvedValueOnce({allowed:false,retryAfterSeconds:60});
  const denied=await s.asr(); expect(denied.status).toBe(429); expect(denied.headers.get('Retry-After')).toBe('60'); expect(s.run).not.toHaveBeenCalled();
  s.run.mockRejectedValueOnce(new Error('secret upstream')); expect((await s.asr()).status).toBe(503); expect(s.admit).toHaveBeenCalledTimes(2);
});
it('hides and disables Chinese TTS absent verified language and gate', async () => {
  const s=await setup(); expect((await s.speech()).status).toBe(503); expect(s.admit).not.toHaveBeenCalled();
  for (const gate of [undefined,'false','true']) {
    (s.env as any).TTS_ZH_VERIFIED=gate;
    const r=await dispatch(new Request('https://example.com/v1/models',{headers:s.headers}),s.env);
    expect(JSON.stringify(await r.json())).not.toContain(melo);
  }
});
it('maps project default and model-id voice to verified Melo language and MP3 bytes', async () => {
  // Protocol-only synthetic MPEG header: this is not decoded or listened to audio.
  const bytes = new Uint8Array([255,251,144,0,1,2,3,4]); const s=await setup({audio:btoa(String.fromCharCode(...bytes))});
  Object.assign(s.env,{TTS_ZH_VERIFIED:'true',TTS_ZH_LANG:'zh-test-verified'});
  for (const voice of [melo,'default']) { const r=await s.speech({voice,speed:1}); expect(r.status).toBe(200); expect(r.headers.get('Content-Type')).toBe('audio/mpeg'); expect(new Uint8Array(await r.arrayBuffer())).toEqual(bytes); }
  expect(s.run.mock.calls[0]).toEqual([melo,{prompt:'你好',lang:'zh-test-verified'}]);
  const r=await dispatch(new Request('https://example.com/v1/models',{headers:s.headers}),s.env); expect(JSON.stringify(await r.json())).toContain(melo);
});
it('refuses unknown voice format speed, model, fields, empty or over-budget text', async () => {
  const s=await setup(); Object.assign(s.env,{TTS_ZH_VERIFIED:'true',TTS_ZH_LANG:'zh-test-verified'});
  for (const fields of [{voice:'alloy'},{response_format:'wav'},{speed:1.1},{speed:'1'},{model:'tts-1'},{input:''},{input:'a'.repeat(501)},{lang:'en'},{instructions:'override'}]) expect((await s.speech(fields)).status).toBe(400);
  expect(s.admit).not.toHaveBeenCalled();
});
it('rejects non-audio speech output', async () => {
  for (const output of [{error:'failure'},{audio:'e30='},new Uint8Array(),{audio:'not base64'}]) {
    const s=await setup(output); Object.assign(s.env,{TTS_ZH_VERIFIED:'true',TTS_ZH_LANG:'zh-test-verified'}); expect((await s.speech()).status).toBe(503);
  }
});
it('bounds streamed bodies without trusting Content-Length and rejects duplicate multipart fields', async () => {
  const s=await setup(); const huge=new Uint8Array(4*1024*1024+1);
  const r=await dispatch(new Request('https://example.com/v1/audio/transcriptions',{method:'POST',headers:{...s.headers,'Content-Type':'multipart/form-data; boundary=x','Content-Length':'1'},body:huge}),s.env);
  expect(r.status).toBe(413); expect(s.admit).not.toHaveBeenCalled();
  const form=new FormData(); form.set('file',new Blob([wav().slice().buffer]),'voice.wav');form.append('model',whisper);form.append('model',whisper);
  expect((await dispatch(new Request('https://example.com/v1/audio/transcriptions',{method:'POST',headers:s.headers,body:form}),s.env)).status).toBe(400);
});
it('drops late ASR and TTS after abort without refund or result', async () => {
  for(const speech of [false,true]) {
    const s=await setup();Object.assign(s.env,{TTS_ZH_VERIFIED:'true',TTS_ZH_LANG:'zh-test-verified'});
    const controller=new AbortController();s.run.mockImplementationOnce(async()=> {controller.abort();return speech?new Uint8Array([255,251,144,0]):{text:'late'};});
    const form=new FormData();form.set('file',new Blob([wav().slice().buffer]),'voice.wav');form.set('model',whisper);
    const r=await dispatch(new Request(`https://example.com/v1/audio/${speech?'speech':'transcriptions'}`,{method:'POST',headers:s.headers,signal:controller.signal,body:speech?JSON.stringify({model:melo,input:'你好'}):form}),s.env);
    expect(r.status).toBe(499);expect(s.admit).toHaveBeenCalledTimes(1);expect(s.run).toHaveBeenCalledTimes(1);
  }
});
