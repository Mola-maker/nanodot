import type { GatewayEnv } from './types';
import { error, record, RequestError } from './errors';
import { FREE_GATE_NAME } from './quota';
import { MELO, WHISPER } from './models';
import { parseWav } from './wav';
const BODY_LIMIT = 4 * 1024 * 1024;
function invalid(message: string): never { throw new RequestError(400, 'invalid_request', message); }
export function ttsEnabled(env: GatewayEnv): boolean {
  // The exact Chinese language value must come from recorded live verification.
  return env.TTS_ZH_VERIFIED === 'true' && typeof env.TTS_ZH_LANG === 'string'
    && /^[A-Za-z][A-Za-z0-9_-]{0,31}$/.test(env.TTS_ZH_LANG);
}
async function boundedBody(request: Request): Promise<Uint8Array> {
  if (!request.body) invalid('A request body is required.');
  const reader = request.body.getReader(); const chunks: Uint8Array[] = []; let size = 0;
  try {
    while (true) {
      const {done,value} = await reader.read(); if (done) break;
      size += value.byteLength;
      if (size > BODY_LIMIT) { await reader.cancel(); throw new RequestError(413,'body_too_large','Body exceeds 4 MiB.'); }
      chunks.push(value);
    }
  } finally { reader.releaseLock(); }
  const bytes = new Uint8Array(size); let offset = 0;
  for (const chunk of chunks) { bytes.set(chunk,offset); offset += chunk.byteLength; }
  return bytes;
}
function base64(bytes: Uint8Array): string {
  let binary = '';
  for (let offset = 0; offset < bytes.length; offset += 8192) binary += String.fromCharCode(...bytes.subarray(offset,offset+8192));
  return btoa(binary);
}
async function admit(request: Request, env: GatewayEnv, clientId: string): Promise<Response | undefined> {
  if (request.signal.aborted) return error(499,'request_cancelled','Request cancelled.');
  const result = await env.FREE_GATE.get(env.FREE_GATE.idFromName(FREE_GATE_NAME)).admit(clientId,Date.now());
  if (!result.allowed) return error(429,'quota_exceeded','Shared project request limit reached.',{'Retry-After':String(result.retryAfterSeconds)});
  // Admission is never refunded; an aborted admitted request cannot reach AI.
  if (request.signal.aborted) return error(499,'request_cancelled','Request cancelled.');
}
function failed(cause: unknown): Response {
  if (cause instanceof RequestError) return error(cause.status,cause.code,cause.message);
  return error(503,'upstream_unavailable','Voice model service unavailable.');
}
export async function handleTranscription(request: Request, env: GatewayEnv, clientId: string): Promise<Response> {
  try {
    if (!/^multipart\/form-data\s*;/i.test(request.headers.get('Content-Type') ?? '')) invalid('Use multipart/form-data.');
    const bytes = await boundedBody(request);
    let form: FormData;
    try { form = await new Request(request.url,{method:'POST',headers:{'Content-Type':request.headers.get('Content-Type')!},body:bytes.slice().buffer}).formData(); }
    catch { invalid('Malformed multipart body.'); }
    const seen = new Set<string>();
    for (const [key] of form) {
      if (!['file','model','language','response_format'].includes(key) || seen.has(key)) invalid('Unknown or duplicate audio field.');
      seen.add(key);
    }
    if (form.get('model') !== WHISPER) invalid('Model is not allowlisted for transcription.');
    if (form.get('response_format') !== null && form.get('response_format') !== 'json') invalid('Only json transcription is supported.');
    const file = form.get('file'); if (!(file instanceof File)) invalid('A WAV file is required.');
    const audio = new Uint8Array(await file.arrayBuffer()); parseWav(audio);
    const languageTag = form.get('language'); let language: string | undefined;
    if (languageTag !== null) {
      if (typeof languageTag !== 'string' || !/^[a-zA-Z]{2}(?:[-_][a-zA-Z0-9]{2,8})*$/.test(languageTag)) invalid('Use an ISO-639-1 language tag.');
      language = languageTag.split(/[-_]/)[0].toLowerCase();
    }
    const refusal = await admit(request,env,clientId); if (refusal) return refusal;
    const output: unknown = await env.AI.run(WHISPER,{audio:base64(audio),task:'transcribe',...(language ? {language} : {})});
    if (request.signal.aborted) return error(499,'request_cancelled','Request cancelled.');
    if (!record(output) || output.error !== undefined || output.success === false || typeof output.text !== 'string') throw new Error('Invalid transcription');
    const info = output.transcription_info;
    if (info !== undefined && (!record(info) || (info.language !== undefined && typeof info.language !== 'string')
      || (info.duration !== undefined && (typeof info.duration !== 'number' || !Number.isFinite(info.duration) || info.duration < 0 || info.duration > 30)))) throw new Error('Invalid transcription info');
    return Response.json({text:output.text,...(info?.language ? {language:info.language} : {}),...(info?.duration !== undefined ? {duration:info.duration} : {})},{headers:{'Cache-Control':'no-store'}});
  } catch (cause) { return failed(cause); }
}
function mp3(output: unknown): Uint8Array {
  let bytes: Uint8Array;
  if (output instanceof Uint8Array) bytes = output;
  else {
    if (!record(output) || output.error !== undefined || output.success === false || typeof output.audio !== 'string'
      || !/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(output.audio)
      || output.audio.length > Math.ceil(BODY_LIMIT/3)*4) throw new Error('Invalid audio');
    bytes = Uint8Array.from(atob(output.audio),c => c.charCodeAt(0));
  }
  // Framing rejects JSON/HTML masquerading as audio; live decoding/listening is a separate gate.
  if (bytes.length < 4 || bytes.length > BODY_LIMIT || !(String.fromCharCode(...bytes.subarray(0,3)) === 'ID3'
    || (bytes[0] === 255 && (bytes[1] & 0xe0) === 0xe0 && (bytes[1] & 6) !== 0 && (bytes[2] & 0xf0) !== 0xf0))) throw new Error('Invalid MP3 framing');
  return bytes;
}
export async function handleSpeech(request: Request, env: GatewayEnv, clientId: string): Promise<Response> {
  if (!ttsEnabled(env)) return error(503,'tts_unverified','Chinese cloud speech has not passed live verification; select a verified system Chinese voice.');
  try {
    let body: unknown;
    try { body = JSON.parse(new TextDecoder('utf-8',{fatal:true}).decode(await boundedBody(request))); }
    catch (cause) { if (cause instanceof RequestError) throw cause; invalid('Malformed JSON body.'); }
    if (!record(body) || Object.keys(body).some(k => !['model','input','voice','response_format','speed'].includes(k))) invalid('Unknown speech field.');
    if (body.model !== MELO) invalid('Model is not allowlisted for speech.');
    if (typeof body.input !== 'string' || !body.input.trim() || body.input.length > 500) invalid('Speech input must contain 1–500 characters.');
    if (body.voice !== undefined && body.voice !== MELO && body.voice !== 'default') invalid('Use the model ID or project default voice.');
    if (body.response_format !== undefined && body.response_format !== 'mp3') invalid('Only mp3 speech is supported.');
    if (body.speed !== undefined && body.speed !== 1) invalid('Only speed 1 is supported.');
    const refusal = await admit(request,env,clientId); if (refusal) return refusal;
    const output: unknown = await env.AI.run(MELO,{prompt:body.input,lang:env.TTS_ZH_LANG});
    if (request.signal.aborted) return error(499,'request_cancelled','Request cancelled.');
    return new Response(mp3(output).slice().buffer,{headers:{'Content-Type':'audio/mpeg','Cache-Control':'no-store'}});
  } catch(cause) { return failed(cause); }
}
