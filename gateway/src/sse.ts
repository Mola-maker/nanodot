import { record } from './errors';

/** Both documented Workers AI native response SSE and OpenAI choices SSE. */
export function normalizeAiStream(source: ReadableStream<Uint8Array>, model: string, signal: AbortSignal): ReadableStream<Uint8Array> {
  const reader = source.getReader();
  const decoder = new TextDecoder('utf-8', { fatal: true });
  const encoder = new TextEncoder();
  const id = `chatcmpl-${crypto.randomUUID()}`;
  const created = Math.floor(Date.now() / 1000);
  let pending = '', data: string[] = [], terminal = false, native = false, seen = false, finished = false, released = false;
  let controller: ReadableStreamDefaultController<Uint8Array>;
  const emit = (value: unknown) => controller.enqueue(encoder.encode(`data: ${typeof value === 'string' ? value : JSON.stringify(value)}\n\n`));
  const chunk = (choices: unknown[], usage?: unknown) => ({ id, object: 'chat.completion.chunk', created, model, choices, ...(usage ? { usage } : {}) });
  const release = async (reason?: unknown) => {
    signal.removeEventListener('abort', abort);
    try { await reader.cancel(reason); } catch { /* source failure is already reported */ }
    if (!released) { released = true; reader.releaseLock(); }
  };
  const fail = async () => {
    if (terminal) return;
    terminal = true;
    emit({ error: { code: signal.aborted ? 'request_cancelled' : 'upstream_stream_error', message: signal.aborted ? 'Request cancelled.' : 'Upstream stream failed or ended without completion.' } });
    controller.close();
    await release();
  };
  function abort() { void fail(); }
  const event = () => {
    if (!data.length) return;
    const payload = data.join('\n'); data = [];
    if (payload === '[DONE]') {
      if (!seen || (!native && !finished)) throw new Error('Incomplete stream');
      if (native && !finished) emit(chunk([{ index: 0, delta: {}, finish_reason: 'stop' }]));
      terminal = true; emit('[DONE]'); controller.close(); void release(); return;
    }
    const value: unknown = JSON.parse(payload);
    if (!record(value) || value.error) throw new Error('Upstream failure');
    if (Array.isArray(value.choices)) {
      const choices = value.choices.map((choice: unknown) => {
        if (!record(choice) || !record(choice.delta)) throw new Error('Invalid choice');
        const delta: Record<string, unknown> = {};
        for (const key of ['role', 'content', 'reasoning_content', 'reasoning', 'tool_calls']) {
          if (choice.delta[key] !== undefined) delta[key] = choice.delta[key];
        }
        if (delta.content != null && typeof delta.content !== 'string') throw new Error('Invalid content');
        if (delta.content || delta.reasoning_content || delta.reasoning || delta.tool_calls) seen = true;
        if (choice.finish_reason != null) {
          if (!['stop', 'length', 'tool_calls', 'content_filter'].includes(choice.finish_reason)) throw new Error('Invalid finish');
          if (!seen) throw new Error('Empty completion');
          finished = true;
        }
        return { index: choice.index ?? 0, delta, ...(choice.finish_reason != null ? { finish_reason: choice.finish_reason } : {}) };
      });
      if (choices.length || value.usage) emit(chunk(choices, value.usage));
    } else if (typeof value.response === 'string') {
      native = true;
      if (value.response) { seen = true; emit(chunk([{ index: 0, delta: { content: value.response } }])); }
      if (value.usage) emit(chunk([], value.usage));
    } else throw new Error('Unknown upstream event');
  };
  return new ReadableStream<Uint8Array>({
    start(c) { controller = c; signal.addEventListener('abort', abort, { once: true }); if (signal.aborted) void fail(); },
    async pull() {
      if (terminal) return;
      try {
        while (!terminal) {
          const newline = pending.indexOf('\n');
          if (newline >= 0) {
            const line = pending.slice(0, newline).replace(/\r$/, ''); pending = pending.slice(newline + 1);
            if (line === '') { const hadData = data.length > 0; event(); if (hadData) return; }
            else if (line.startsWith('data:')) data.push(line.slice(5).replace(/^ /, ''));
            if (data.join('').length > 256 * 1024) throw new Error('Oversized event');
            continue;
          }
          const next = await reader.read();
          if (terminal) return;
          if (next.done) { decoder.decode(); await fail(); return; }
          pending += decoder.decode(next.value, { stream: true });
          if (pending.length > 256 * 1024) throw new Error('Oversized line');
        }
      } catch { await fail(); }
    },
    async cancel(reason) { terminal = true; await release(reason); },
  });
}
