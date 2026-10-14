import type { GatewayEnv } from './types';
import { FREE_GATE_NAME } from './quota';
import { GEMMA, QWEN } from './models';
import { error, record, RequestError } from './errors';
import { normalizeAiStream } from './sse';

export interface ChatRequest { model: string; messages: unknown[]; stream: boolean; max_completion_tokens?: number; max_tokens?: number; tools?: unknown[] }
const BODY_LIMIT = 4 * 1024 * 1024;
function invalid(message: string): never { throw new RequestError(400, 'invalid_request', message); }

async function readBody(request: Request): Promise<unknown> {
  if (!request.body) invalid('A JSON body is required.');
  const reader = request.body.getReader();
  const decoder = new TextDecoder('utf-8', { fatal: true });
  let text = '', size = 0;
  try {
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      size += value.byteLength;
      if (size > BODY_LIMIT) { await reader.cancel(); throw new RequestError(413, 'body_too_large', 'Body exceeds 4 MiB.'); }
      text += decoder.decode(value, { stream: true });
    }
    text += decoder.decode();
    return JSON.parse(text);
  } catch (cause) { if (cause instanceof RequestError) throw cause; invalid('Malformed JSON body.'); }
  finally { reader.releaseLock(); }
}

// Inspect image container dimensions locally. Never fetch a client URL.
function image(url: unknown): string {
  if (typeof url !== 'string') invalid('An image data URL is required.');
  const match = /^data:image\/(png|jpeg);base64,([A-Za-z0-9+/]+={0,2})$/.exec(url);
  if (!match || match[2].length % 4 !== 0) invalid('Only PNG/JPEG base64 data URLs are supported.');
  const bytes = Uint8Array.from(atob(match[2]), c => c.charCodeAt(0));
  if (bytes.length > 2 * 1024 * 1024) invalid('Image exceeds 2 MiB.');
  const view = new DataView(bytes.buffer);
  let width = 0, height = 0;
  if (match[1] === 'png') {
    if (bytes.length < 45 || [137,80,78,71,13,10,26,10].some((b,i) => bytes[i] !== b) || view.getUint32(8) !== 13 || String.fromCharCode(...bytes.slice(12,16)) !== 'IHDR' || String.fromCharCode(...bytes.slice(-8,-4)) !== 'IEND') invalid('Invalid PNG image.');
    width = view.getUint32(16); height = view.getUint32(20);
    let offset = 8, dataSeen = false, endSeen = false;
    while (offset + 12 <= bytes.length) {
      const length = view.getUint32(offset);
      if (offset + 12 + length > bytes.length) invalid('Invalid PNG chunk.');
      const type = String.fromCharCode(...bytes.slice(offset + 4, offset + 8));
      if (type === 'IDAT') dataSeen = true;
      if (type === 'IEND') {
        if (length !== 0 || offset + 12 !== bytes.length) invalid('Invalid PNG end.');
        endSeen = true;
      }
      offset += 12 + length;
    }
    if (!dataSeen || !endSeen || offset !== bytes.length) invalid('Incomplete PNG image.');
  } else {
    if (bytes.length < 4 || bytes[0] !== 255 || bytes[1] !== 216 || bytes.at(-2) !== 255 || bytes.at(-1) !== 217) invalid('Invalid JPEG image.');
    let offset = 2, scanSeen = false;
    while (offset + 4 <= bytes.length) {
      if (bytes[offset++] !== 255) invalid('Invalid JPEG marker.');
      while (bytes[offset] === 255) offset++;
      const marker = bytes[offset++];
      if (marker === 218) { scanSeen = true; break; }
      if (marker === 217) break;
      if (marker === 1 || (marker >= 208 && marker <= 215)) continue;
      if (offset + 2 > bytes.length) invalid('Invalid JPEG segment.');
      const length = view.getUint16(offset);
      if (length < 2 || offset + length > bytes.length) invalid('Invalid JPEG segment.');
      if ([192,193,194,195,197,198,199,201,202,203,205,206,207].includes(marker)) {
        if (length < 8) invalid('Invalid JPEG dimensions.');
        height = view.getUint16(offset + 3); width = view.getUint16(offset + 5);
        if (!width || !height || Math.max(width, height) > 1280) invalid('JPEG dimensions exceed budget.');
      }
      offset += length;
    }
    if (!scanSeen) invalid('Incomplete JPEG image.');
  }
  if (!width || !height || Math.max(width,height) > 1280) invalid('Image dimensions must be 1–1280 pixels.');
  return url;
}

function validate(value: unknown): { model: typeof QWEN | typeof GEMMA; input: Record<string, unknown>; stream: boolean } {
  if (!record(value)) invalid('Body must be an object.');
  if (value.model !== QWEN && value.model !== GEMMA) invalid('Model is not allowlisted for chat.');
  const model = value.model;
  if (value.stream !== undefined && typeof value.stream !== 'boolean') invalid('stream must be boolean.');
  if (!Array.isArray(value.messages) || value.messages.length === 0 || value.messages.length > 256) invalid('messages must contain 1–256 entries.');
  let characters = 0, images = 0;
  const text = (s: unknown): string => { if (typeof s !== 'string') invalid('Expected text.'); characters += s.length; return s; };
  const toolCalls = (calls: unknown) => {
    if (!Array.isArray(calls) || calls.length === 0 || calls.length > 128) invalid('Invalid tool calls.');
    return calls.map(c => {
      if (!record(c) || c.type !== 'function' || !record(c.function)) invalid('Only function tool calls are supported.');
      const id = text(c.id), name = text(c.function.name), args = text(c.function.arguments);
      if (!id || !name) invalid('Tool call id and name are required.');
      try { JSON.parse(args); } catch { invalid('Tool arguments must be JSON.'); }
      return { id, type: 'function', function: { name, arguments: args } };
    });
  };
  const messages = value.messages.map((m: unknown) => {
    if (!record(m) || !['user','assistant','system','tool'].includes(m.role)) invalid('Invalid message role.');
    if (model === QWEN && (m.role === 'tool' || m.tool_calls !== undefined || m.tool_call_id !== undefined)) invalid('Qwen tool history is not verified; use Gemma for tool round trips.');
    const result: Record<string, unknown> = { role: m.role };
    if (m.content === null && m.role === 'assistant' && m.tool_calls) result.content = null;
    else if (Array.isArray(m.content)) {
      if (!['user','assistant'].includes(m.role) || m.content.length === 0) invalid('Invalid content parts.');
      result.content = m.content.map((part: unknown) => {
        if (!record(part)) invalid('Invalid content part.');
        if (part.type === 'text') return { type: 'text', text: text(part.text) };
        if (part.type !== 'image_url' || m.role !== 'user' || model !== GEMMA || !record(part.image_url)) invalid('Images require Gemma user messages.');
        if (++images > 1) invalid('At most one image is allowed.');
        return { type: 'image_url', image_url: { url: image(part.image_url.url) } };
      });
    } else result.content = text(m.content);
    if (m.tool_calls !== undefined) {
      if (m.role !== 'assistant') invalid('Tool calls require an assistant message.');
      result.tool_calls = toolCalls(m.tool_calls);
    }
    if (m.role === 'tool') { result.tool_call_id = text(m.tool_call_id); if (!result.tool_call_id) invalid('Tool result requires tool_call_id.'); }
    if (m.reasoning_content !== undefined || m.reasoning !== undefined) invalid('Reasoning-history input is not verified for these models.');
    return result;
  });
  const max = value.max_completion_tokens ?? value.max_tokens ?? 2048;
  for (const key of ['max_completion_tokens','max_tokens']) if (value[key] !== undefined && (!Number.isInteger(value[key]) || value[key] < 1 || value[key] > 2048)) invalid('Output budget must be 1–2048 tokens.');
  if (value.max_tokens !== undefined && value.max_completion_tokens !== undefined && value.max_tokens !== value.max_completion_tokens) invalid('Conflicting output budgets.');
  const input: Record<string, unknown> = { messages, stream: value.stream ?? false, [model === QWEN ? 'max_tokens' : 'max_completion_tokens']: max };
  for (const [key, min, max] of [['temperature',0,model === QWEN ? 5 : 2], ['top_p',0.001,1]] as const) {
    if (value[key] !== undefined) { if (typeof value[key] !== 'number' || !Number.isFinite(value[key]) || value[key] < min || value[key] > max) invalid(`Invalid ${key}.`); input[key] = value[key]; }
  }
  if (value.response_format !== undefined) {
    if (!record(value.response_format) || value.response_format.type !== 'text') invalid('Only text response format is supported.');
  }
  if (value.tools !== undefined) {
    if (!Array.isArray(value.tools) || value.tools.length > 128) invalid('Invalid tools.');
    const tools = value.tools.map((t: unknown) => {
      if (!record(t) || t.type !== 'function' || !record(t.function) || !record(t.function.parameters) || t.function.parameters.type !== 'object' || !record(t.function.parameters.properties)) invalid('Only function tools with an object schema are supported.');
      const fn = t.function;
      const name = text(fn.name), description = text(fn.description ?? '');
      if (!/^[A-Za-z0-9_-]{1,64}$/.test(name)) invalid('Invalid function name.');
      const parameters = JSON.parse(JSON.stringify(fn.parameters));
      characters += JSON.stringify(parameters).length;
      if (model === QWEN) {
        if (Object.keys(parameters).some(k => !['type','properties','required'].includes(k))) invalid('Qwen schema keyword is not verified.');
        for (const property of Object.values(parameters.properties)) {
          if (!record(property) || typeof property.type !== 'string' || typeof property.description !== 'string' || Object.keys(property).some(k => !['type','description'].includes(k))) invalid('Qwen supports simple typed, described tool properties only.');
        }
      }
      if (parameters.required !== undefined && (!Array.isArray(parameters.required) || parameters.required.some((r: unknown) => typeof r !== 'string' || !Object.hasOwn(parameters.properties, r)))) invalid('Invalid required parameters.');
      return { type: 'function', function: { name, description, parameters } };
    });
    if (tools.length) input.tools = tools;
  }
  if (value.tool_choice !== undefined && value.tool_choice !== 'auto') invalid('Only automatic function choice is supported.');
  if (characters > 16000) invalid('Text context, including tools, exceeds 16,000 characters.');
  if (value.stream_options !== undefined && (!record(value.stream_options) || value.stream_options.include_usage !== true)) invalid('Invalid stream options.');
  if (model === GEMMA && value.stream) input.stream_options = { include_usage: true };
  return { model, input, stream: value.stream ?? false };
}

function completion(value: unknown, model: string): unknown {
  if (!record(value) || value.error) throw new Error('Invalid upstream output');
  let choices: unknown[];
  if (Array.isArray(value.choices) && value.choices.length) {
    choices = value.choices.map((c: unknown) => {
      if (!record(c) || !record(c.message) || !['stop','length','tool_calls','content_filter'].includes(c.finish_reason)) throw new Error('Invalid choice');
      const m = c.message;
      if (typeof m.content !== 'string' && !(m.content === null && Array.isArray(m.tool_calls))) throw new Error('Invalid message');
      return { index: c.index ?? 0, message: { role: 'assistant', content: m.content, ...(m.reasoning_content !== undefined ? { reasoning_content: m.reasoning_content } : {}), ...(m.tool_calls ? { tool_calls: m.tool_calls } : {}) }, finish_reason: c.finish_reason };
    });
  } else if (typeof value.response === 'string' && value.response.length) choices = [{ index: 0, message: { role: 'assistant', content: value.response }, finish_reason: 'stop' }];
  else throw new Error('Empty upstream output');
  return { id: typeof value.id === 'string' ? value.id : `chatcmpl-${crypto.randomUUID()}`, object: 'chat.completion', created: typeof value.created === 'number' ? value.created : Math.floor(Date.now()/1000), model, choices, ...(value.usage ? { usage: value.usage } : {}) };
}

export async function handleChat(request: Request, env: GatewayEnv, clientId: string): Promise<Response> {
  try {
    const { model, input, stream } = validate(await readBody(request));
    if (request.signal.aborted) return error(499, 'request_cancelled', 'Request cancelled.');
    const gate = env.FREE_GATE.get(env.FREE_GATE.idFromName(FREE_GATE_NAME));
    const admitted = await gate.admit(clientId, Date.now());
    if (!admitted.allowed) return error(429, 'quota_exceeded', 'Shared project request limit reached.', { 'Retry-After': String(admitted.retryAfterSeconds) });
    // Once admitted, attempts remain counted even if the client cancels or AI fails.
    const output = await env.AI.run(model, input as unknown as AiModels[typeof model]['inputs']);
    if (stream) {
      if (!(output instanceof ReadableStream)) throw new Error('Expected upstream stream');
      return new Response(normalizeAiStream(output, model, request.signal), { headers: { 'Content-Type': 'text/event-stream; charset=utf-8', 'Cache-Control': 'no-store' } });
    }
    return Response.json(completion(output, model), { headers: { 'Cache-Control': 'no-store' } });
  } catch (cause) {
    if (cause instanceof RequestError) return error(cause.status, cause.code, cause.message);
    return error(503, 'upstream_unavailable', 'Model service unavailable.');
  }
}
