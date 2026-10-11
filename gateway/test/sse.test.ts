import toolOutput from './fixtures/tool-output-validation.json';
import normalFixture from './fixtures/normal-stream.json';
import fixture from './fixtures/stream.json';
import { expect, it } from 'vitest';
import { normalizeAiStream } from '../src/sse';
const model = '@cf/qwen/qwen3-30b-a3b-fp8';
function source(text: string) { const bytes = new TextEncoder().encode(text); return new ReadableStream<Uint8Array>({ start(c) { for (const byte of bytes) c.enqueue(new Uint8Array([byte])); c.close(); } }); }
const read = (text: string) => new Response(normalizeAiStream(source(text), model, new AbortController().signal)).text();
it('preserves split UTF8, reasoning, tools, usage and length in standard SSE', async () => {
 const text = 'data: {"choices":[{"delta":{"content":"你好","reasoning_content":"思考","tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"lookup","arguments":"{}"}}]},"index":0}]}\r\n\r\ndata: {"choices":[{"delta":{},"finish_reason":"length","index":0}]}\n\ndata: {"choices":[],"usage":{"prompt_tokens":2,"completion_tokens":3,"total_tokens":5}}\n\ndata: [DONE]\n\n';
 const result = await read(text); expect(result).toContain('你好'); expect(result).toContain('思考'); expect(result).toContain('tool_calls'); expect(result).toContain('"finish_reason":"length"'); expect(result).toContain('"total_tokens":5'); expect(result.match(/\[DONE\]/g)).toHaveLength(1);
});
it('normalizes documented native response events and explicit DONE', async () => {
 const result = await read('data: {"response":"你好"}\n\ndata: [DONE]\n\n'); expect(result).toContain('"content":"你好"'); expect(result).toContain('"finish_reason":"stop"'); expect(result).toContain('[DONE]');
});
it('never fabricates success for empty, malformed, errored or truncated streams', async () => {
 for (const input of ['', 'data: {"response":"partial"}\n\n', 'data: {oops}\n\n', 'data: {"error":{"message":"private failure"}}\n\n', 'data: [DONE]\n\n', 'data: {"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}\n\ndata: [DONE]\n\n']) { const result = await read(input); expect(result).toContain('"error"'); expect(result).not.toContain('[DONE]'); expect(result).not.toContain('"finish_reason":"stop"'); expect(result).not.toContain('private failure'); }
});
it('cancel propagates to source and releases its reader', async () => {
 let cancelled = false; const upstream = new ReadableStream<Uint8Array>({ cancel() { cancelled = true; } });
 const output = normalizeAiStream(upstream, model, new AbortController().signal); const reader = output.getReader(); await reader.cancel(); expect(cancelled).toBe(true); expect(upstream.locked).toBe(false);
});
it('abort interrupts a pending read and releases the source', async () => {
 let cancelled = false; const upstream = new ReadableStream<Uint8Array>({ cancel() { cancelled = true; } }); const abort = new AbortController();
 const output = normalizeAiStream(upstream, model, abort.signal); const result = new Response(output).text(); abort.abort(); expect(await result).toContain('"error"'); expect(cancelled).toBe(true); expect(upstream.locked).toBe(false);
});

it('produces the same wire events consumed by the real Android provider', async () => {
 const input = fixture.events.map(e => `data: ${JSON.stringify(e)}\n\n`).join('') + 'data: [DONE]\n\n';
 const output = await read(input);
 const events = output.split('\n\n').filter(e => e.startsWith('data: {')).map(e => { const { id, object, created, model, ...event } = JSON.parse(e.slice(6)); return event; });
 expect(events).toEqual(fixture.events);
});

it('normalizes the ordinary two-turn Android response fixture', async () => {
 const input = normalFixture.events.map(e => `data: ${JSON.stringify(e)}\n\n`).join('') + 'data: [DONE]\n\n';
 const events = (await read(input)).split('\n\n').filter(e => e.startsWith('data: {')).map(e => { const {id, object, created, model, ...event} = JSON.parse(e.slice(6)); return event; });
 expect(events).toEqual(normalFixture.events);
});

const wire = (events: unknown[]) => events.map(e => `data: ${JSON.stringify(e)}\n\n`).join('') + 'data: [DONE]\n\n';
const normalizedEvents = (output: string) => output.split('\n\n').filter(e => e.startsWith('data: {')).map(e => {
 const {id, object, created, model, ...event} = JSON.parse(e.slice(6)); return event;
});
it('rejects empty tool arrays with the same error wire consumed by Android', async () => {
 const output = await read(wire(toolOutput.empty_upstream_events));
 expect(normalizedEvents(output)).toEqual(toolOutput.empty_normalized_events);
 expect(output).not.toContain('[DONE]'); expect(output).not.toContain('"finish_reason":"stop"');
});
it('requires typed nonempty reasoning or meaningful function deltas', async () => {
 const empty = [{reasoning_content: ''}, {reasoning: null}, {tool_calls: [{index: 0, id: 'metadata_only', type: 'function'}]}, {tool_calls: [{index: 0, function: {name: '', arguments: ''}}]}];
 const invalid = [{reasoning_content: {}}, {reasoning: 1}, {tool_calls: {}}, {tool_calls: [null]}, {tool_calls: [{index: -1, function: {name: 'lookup'}}]}, {tool_calls: [{index: 0, type: 'custom', function: {name: 'lookup'}}]}, {tool_calls: [{index: 0, id: 2, function: {name: 'lookup'}}]}, {tool_calls: [{index: 0, function: {arguments: {}}}]}];
 for (const delta of [...empty, ...invalid]) {
  const output = await read(wire([{choices: [{index: 0, delta}]}, {choices: [{index: 0, delta: {}, finish_reason: 'stop'}]}]));
  expect(output).toContain('"error"'); expect(output).not.toContain('[DONE]'); expect(output).not.toContain('"finish_reason":"stop"');
 }
});
it('preserves genuine tool-only output and its continuation arguments', async () => {
 const output = await read(wire(toolOutput.tool_only_events));
 expect(normalizedEvents(output)).toEqual(toolOutput.tool_only_events);
 expect(output).toContain('[DONE]'); expect(output).not.toContain('"error"');
});
