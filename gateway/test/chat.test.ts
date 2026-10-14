import history from './fixtures/history.json';
import { runInDurableObject } from 'cloudflare:test';
import { expect, it, vi } from 'vitest';
import { credentials, dispatch, gatewayEnv, request } from './helpers';
import type { GatewayEnv } from '../src/types';
const qwen = '@cf/qwen/qwen3-30b-a3b-fp8';
const gemma = '@cf/google/gemma-4-26b-a4b-it';
const body = (extra = {}) => ({ model: qwen, messages: [{ role: 'user', content: '你好' }], stream: false, ...extra });
async function setup() {
 const { keys, secret } = await credentials(['a']); const env = gatewayEnv(secret); const calls: unknown[][] = [];
 await runInDurableObject(env.FREE_GATE.get(env.FREE_GATE.idFromName('global-free-tier-v1')), async (_instance, state) => { state.storage.sql.exec('DELETE FROM daily'); state.storage.sql.exec('DELETE FROM admissions'); });
 env.AI = { async run(...args: unknown[]) { calls.push(args); return { choices: [{ message: { role: 'assistant', content: '你好' }, finish_reason: 'stop' }], usage: { prompt_tokens: 2, completion_tokens: 2, total_tokens: 4 } }; } } as unknown as Ai;
 return { env, calls, post: (value: unknown) => dispatch(request(keys[0], undefined, JSON.stringify(value)), env) };
}
it('maps Qwen tokens and passes only allowlisted parameters', async () => {
 const { calls, post } = await setup(); const response = await post(body({ max_completion_tokens: 64, tools: [], secret: 'never-forward' })); expect(response.status).toBe(200);
 expect(calls[0]).toEqual([qwen, { messages: [{ role: 'user', content: '你好' }], stream: false, max_tokens: 64 }]);
 expect(await response.json()).toMatchObject({ object: 'chat.completion', model: qwen, choices: [{ message: { role: 'assistant', content: '你好' }, finish_reason: 'stop' }], usage: { total_tokens: 4 } });
});
it('validates model, formats and budgets before admission', async () => {
 const { env, post, calls } = await setup(); const admission = vi.fn(() => { throw new Error('No admission'); }); env.FREE_GATE = { idFromName: admission, get: admission } as unknown as GatewayEnv['FREE_GATE'];
 const invalid = [body({ model: 'arbitrary' }), body({ model: '@cf/openai/whisper-large-v3-turbo' }), body({ messages: [] }), body({ stream: 'true' }), body({ max_tokens: 2049 }), body({ max_tokens: 0 }), body({ max_tokens: 10, max_completion_tokens: 11 }), body({ response_format: { type: 'json_schema' } }), body({ messages: [{ role: 'user', content: 'x'.repeat(16001) }] }), body({ messages: [{ role: 'user', content: [{ type: 'image_url', image_url: { url: 'https://example.com/a.png' } }] }] }), body({ tools: [{ type: 'web_search' }] }), body({ messages: [{role: 'tool', content: 'result', tool_call_id: 'call_1'}] })];
 for (const value of invalid) expect((await post(value)).status).toBe(400);
 expect((await post(body({ messages: [{role: 'user', content: 'x'.repeat(4*1024*1024)}] }))).status).toBe(413); expect(admission).not.toHaveBeenCalled(); expect(calls).toHaveLength(0);
});
it('admits once globally with server time immediately before AI', async () => {
 const { env, post } = await setup(); const order: string[] = []; const now = Date.now();
 env.FREE_GATE = { idFromName(name: string) { expect(name).toBe('global-free-tier-v1'); return name; }, get() { return { async admit(id: string, time: number) { expect(id).toBe('a'); expect(time).toBeGreaterThanOrEqual(now); order.push('admit'); return { allowed: true, retryAfterSeconds: 0 }; } }; } } as unknown as GatewayEnv['FREE_GATE'];
 env.AI = { async run() { order.push('AI'); throw new Error('private upstream details'); } } as unknown as Ai;
 const response = await post(body()); expect(response.status).toBe(503); expect(order).toEqual(['admit','AI']); expect(await response.text()).not.toContain('private');
});
it('returns 429 Retry-After with no AI after durable quota exhaustion', async () => {
 const { post, calls } = await setup(); for (let i=0; i<12; i++) expect((await post(body())).status).toBe(200);
 const denied = await post(body({ model: gemma })); expect(denied.status).toBe(429); expect(Number(denied.headers.get('Retry-After'))).toBeGreaterThan(0); expect(calls).toHaveLength(12);
});
it('encodes both function definitions and verified Gemma tool history', async () => {
 const { post, calls } = await setup(); const tools = [{ type: 'function', function: { name: 'lookup', description: 'Lookup', parameters: { type: 'object', properties: { value: { type: 'string', description: 'Value' } } } } }];
 expect((await post(body({ tools }))).status).toBe(200); expect((calls[0][1] as Record<string,unknown>).tools).toEqual(tools);
 const messages = [{ role: 'assistant', content: null, tool_calls: [{ id: 'call_1', type: 'function', function: { name: 'lookup', arguments: '{"value":"x"}' } }] }, { role: 'tool', tool_call_id: 'call_1', content: 'result' }];
 expect((await post(body({ model: gemma, tools, messages }))).status).toBe(200); expect((calls[1][1] as Record<string,unknown>).messages).toEqual(messages);
});
it('accepts one locally validated Gemma data URL and rejects image budgets before admission', async () => {
 const { post, calls } = await setup();
 const png = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jhKQAAAAASUVORK5CYII=';
 const parts = (url: string) => [{ type: 'text', text: 'Describe' }, { type: 'image_url', image_url: { url } }];
 const response = await post(body({ model: gemma, messages: [{ role: 'user', content: parts(png) }] }));
 expect(response.status).toBe(200); expect((calls[0][1] as Record<string,unknown>).messages).toEqual([{ role: 'user', content: parts(png) }]);
 const oversized = new Uint8Array(2 * 1024 * 1024 + 1); const big = 'data:image/png;base64,' + btoa(Array.from(oversized, c => String.fromCharCode(c)).join(''));
 const header = Uint8Array.from(atob(png.split(',')[1]), c => c.charCodeAt(0)); new DataView(header.buffer).setUint32(16, 1281);
 const wide = 'data:image/png;base64,' + btoa(String.fromCharCode(...header));
 for (const value of [body({ messages: [{ role: 'user', content: parts(png) }] }), body({ model: gemma, messages: [{ role: 'user', content: parts(wide) }] }), body({ model: gemma, messages: [{ role: 'user', content: parts(big) }] }), body({ model: gemma, messages: [{ role: 'user', content: [...parts(png), ...parts(png)] }] }), body({ model: gemma, messages: [{ role: 'user', content: parts('data:image/png;base64,AAAA') }] })]) expect((await post(value)).status).toBe(400);
 expect(calls).toHaveLength(1);
});
it('chat returns normalized SSE and failures remain counted with no refund', async () => {
 const { env, post, calls } = await setup();
 let attempts = 0;
 env.AI = { async run() { attempts++; if (attempts < 12) throw new Error('Failure'); return new ReadableStream({ start(c) { c.enqueue(new TextEncoder().encode('data: {"response":"你好"}\n\ndata: [DONE]\n\n')); c.close(); } }); } } as unknown as Ai;
 for (let i = 0; i < 11; i++) expect((await post(body())).status).toBe(503);
 const streamed = await post(body({ stream: true })); expect(streamed.headers.get('content-type')).toContain('text/event-stream');
 await streamed.body!.getReader().cancel();
 expect((await post(body({ model: gemma }))).status).toBe(429); expect(attempts).toBe(12); expect(calls).toHaveLength(0);
});
it('rejects malformed upstream completions instead of inventing a finished message', async () => {
 const { env, post } = await setup();
 for (const output of [{}, { choices: [] }, { choices: [{message: {role: 'assistant', content: 'partial'}}] }, {error: {message: 'private'} }]) {
   env.AI = {async run() {return output;} } as unknown as Ai;
   expect((await post(body())).status).toBe(503);
 }
});

it('rejects corrupt PNG chunk lengths before spending quota', async () => {
 const { post, calls } = await setup();
 const bytes = Uint8Array.from(atob('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jhKQAAAAASUVORK5CYII='), c => c.charCodeAt(0));
 new DataView(bytes.buffer).setUint32(33, 0x7fffffff);
 const url = 'data:image/png;base64,' + btoa(String.fromCharCode(...bytes));
 expect((await post(body({model: gemma, messages: [{role: 'user', content: [{type: 'image_url', image_url: {url}}]}]}))).status).toBe(400);
 expect(calls).toHaveLength(0);
});
it('refuses undocumented reasoning-history encoding before AI', async () => {
 const { post, calls } = await setup();
 for (const model of [qwen, gemma]) expect((await post(body({model, messages: [{role: 'assistant', content: 'answer', reasoning_content: 'opaque'}]}))).status).toBe(400);
 expect(calls).toHaveLength(0);
});

it('accepts actual Android ordinary two-turn history after streamed reasoning', async () => {
 const {post} = await setup();
 for (const model of [qwen, gemma]) expect((await post(body({model, messages: history.messages}))).status).toBe(200);
});
