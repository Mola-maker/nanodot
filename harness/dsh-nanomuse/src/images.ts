/**
 * Pictures through an own key (0.1.41 "Choice"): the avatar studio's candidates and poses
 * drawn by the provider the person chose under Settings → Models, not only by Nanodot
 * Cloud. Three shapes, the same three the Python runtime's `nanomuse/avatar/studio.py`
 * tells apart:
 *
 *  - `openai`: `POST {base}/images/generations` and `POST {base}/images/edits` (multipart),
 *    OpenAI's shape — OpenAI itself, SiliconFlow, Volcengine Ark, Zhipu, xAI, Gemini's
 *    OpenAI-compatible layer, any hand-made endpoint;
 *  - `dashscope`: Model Studio's own host has no OpenAI images API; its native
 *    `multimodal-generation/generation` takes qwen-image / wan-image, with a data-URI picture
 *    for a pose (an older text-only qwen-image is posed by `qwen-image-edit-max`);
 *  - `openrouter`: OpenRouter's Image API, `POST {base}/images` with `model` and `prompt`,
 *    `input_references` for a pose; the picture comes back as `data[0].b64_json`.
 *
 * The relay (`shape: cloud`) is drawn through `relay.ts` as before; it is listed here so one
 * value names where a picture would come from. No Node-only API beyond `fetch`, `FormData`
 * and `Blob`; the tests pass a fake `fetch`.
 */
import { RelayError } from './relay.ts'

export type ImageShape = 'cloud' | 'openai' | 'dashscope' | 'openrouter'

/** Where a picture would be drawn: the account, or an own row with its key. */
export interface ImageEndpoint {
  shape: ImageShape
  /** The row's base URL (`…/v1`, `…/compatible-mode/v1`, `…/api/v1`), without a trailing slash. */
  baseURL: string
  apiKey: string
  model: string
  /** `nanoMuse Cloud`, or the row's label. */
  label: string
  /** `nanomuse` for the account, else the own row's id. */
  instanceId: string
}

const SIZE = '1024x1024'
const TIMEOUT_MS = 180_000

/** The shape an own row speaks, by its catalogue id and base URL. */
export function imageShapeOf(providerId: string, baseURL: string): Exclude<ImageShape, 'cloud'> {
  const b = baseURL.toLowerCase()
  if (providerId === 'openrouter' || /openrouter\.ai/.test(b)) return 'openrouter'
  if (providerId === 'bailian' || /dashscope|aliyuncs\.com/.test(b)) return 'dashscope'
  return 'openai'
}

/** Model Studio's root (`https://dashscope.aliyuncs.com`) from a row's `…/compatible-mode/v1` or `…/api/v1`. */
export function dashScopeHost(baseURL: string): string {
  return baseURL.replace(/\/+$/, '').split(/\/compatible-mode|\/api\/v1/)[0] ?? baseURL
}

/** One picture from words, as PNG (or whatever the model gave) bytes. */
export async function generateImage(ep: ImageEndpoint, prompt: string, fetchImpl: typeof fetch = fetch): Promise<Buffer> {
  if (ep.shape === 'dashscope') return dashScope(ep, ep.model, [{ text: prompt }], dashScopeParams(ep.model), fetchImpl)
  if (ep.shape === 'openrouter') {
    const res = await post(fetchImpl, `${ep.baseURL}/images`, ep.apiKey, { model: ep.model, prompt, n: 1, size: SIZE, output_format: 'png' })
    return imageOf(res, fetchImpl)
  }
  const body: Record<string, unknown> = { model: ep.model, prompt, n: 1 }
  // xAI's image models take neither `size` nor `quality`; gpt-image-* always answer inline and refuse `response_format`
  if (!/x\.ai/.test(ep.baseURL)) body.size = SIZE
  if (!/^gpt-image/.test(ep.model)) body.response_format = 'b64_json'
  const res = await post(fetchImpl, `${ep.baseURL}/images/generations`, ep.apiKey, body)
  return imageOf(res, fetchImpl)
}

/** The same character in another pose: the picture and the instruction. */
export async function editImage(ep: ImageEndpoint, png: Buffer, instruction: string, fetchImpl: typeof fetch = fetch): Promise<Buffer> {
  const dataUrl = `data:image/png;base64,${png.toString('base64')}`
  if (ep.shape === 'dashscope') {
    // qwen-image-3.x, wan-image and the qwen-image-edit-* models take a picture themselves; an older text-only qwen-image is posed by qwen-image-edit-max on the same key
    const threeX = ep.model.startsWith('qwen-image-3') || ep.model.startsWith('wan')
    const model = threeX || ep.model.includes('edit') ? ep.model : 'qwen-image-edit-max'
    const params = threeX ? dashScopeParams(ep.model) : { n: 1, watermark: false }
    return dashScope(ep, model, [{ image: dataUrl }, { text: instruction }], params, fetchImpl)
  }
  if (ep.shape === 'openrouter') {
    const res = await post(fetchImpl, `${ep.baseURL}/images`, ep.apiKey, { model: ep.model, prompt: instruction, n: 1, size: SIZE, output_format: 'png', input_references: [{ type: 'image_url', image_url: { url: dataUrl } }] })
    return imageOf(res, fetchImpl)
  }
  const form = new FormData()
  form.set('model', ep.model)
  form.set('prompt', instruction)
  form.set('n', '1')
  if (!/x\.ai/.test(ep.baseURL)) form.set('size', SIZE)
  if (!/^gpt-image/.test(ep.model)) form.set('response_format', 'b64_json')
  form.set('image', new Blob([new Uint8Array(png)], { type: 'image/png' }), 'idle.png')
  const res = await fetchImpl(`${ep.baseURL}/images/edits`, { method: 'POST', headers: auth(ep.apiKey), body: form, signal: AbortSignal.timeout(TIMEOUT_MS) })
  return imageOf(res, fetchImpl)
}

function auth(apiKey: string): Record<string, string> {
  return apiKey ? { authorization: `Bearer ${apiKey}` } : {}
}

async function post(fetchImpl: typeof fetch, url: string, apiKey: string, body: unknown): Promise<Response> {
  return fetchImpl(url, { method: 'POST', headers: { ...auth(apiKey), 'content-type': 'application/json' }, body: JSON.stringify(body), signal: AbortSignal.timeout(TIMEOUT_MS) })
}

function dashScopeParams(model: string): Record<string, unknown> {
  const params: Record<string, unknown> = { size: SIZE.replace('x', '*'), watermark: false }
  if (model.startsWith('qwen-image')) params.prompt_extend = false
  return params
}

async function dashScope(ep: ImageEndpoint, model: string, content: Record<string, string>[], params: Record<string, unknown>, fetchImpl: typeof fetch): Promise<Buffer> {
  const host = dashScopeHost(ep.baseURL)
  const res = await post(fetchImpl, `${host}/api/v1/services/aigc/multimodal-generation/generation`, ep.apiKey, { model, input: { messages: [{ role: 'user', content }] }, parameters: params })
  if (!res.ok) await fail(res)
  const body = (await res.json().catch(() => ({}))) as { output?: { choices?: { message?: { content?: { image?: string }[] } }[] }; message?: string }
  const url = body.output?.choices?.[0]?.message?.content?.find((c) => c.image)?.image
  if (!url) throw new RelayError(502, 'image', (body.message ?? 'No picture came back.').slice(0, 200))
  return fetchBytes(fetchImpl, url)
}

/** The picture in an OpenAI-shaped (or OpenRouter's) images reply: inline, or fetched from the URL given. */
async function imageOf(res: Response, fetchImpl: typeof fetch): Promise<Buffer> {
  if (!res.ok) await fail(res)
  const body = (await res.json().catch(() => ({}))) as { data?: { b64_json?: string; url?: string }[]; error?: { message?: string } }
  const first = (body.data ?? [])[0]
  if (first?.b64_json) return Buffer.from(first.b64_json, 'base64')
  if (first?.url) return fetchBytes(fetchImpl, first.url)
  throw new RelayError(502, 'image', (body.error?.message ?? 'No picture came back.').slice(0, 200))
}

async function fetchBytes(fetchImpl: typeof fetch, url: string): Promise<Buffer> {
  const res = await fetchImpl(url, { signal: AbortSignal.timeout(TIMEOUT_MS) })
  if (!res.ok) throw new RelayError(502, 'image', `The picture could not be fetched (${res.status})`)
  return Buffer.from(await res.arrayBuffer())
}

/** The provider's refusal in one sentence: its `error.message` or `message`, else the status. */
async function fail(res: Response): Promise<never> {
  let message = `HTTP ${res.status}`
  let code = `http_${res.status}`
  try {
    const body = (await res.json()) as { error?: { code?: string; message?: string } | string; message?: string; code?: string }
    if (body && typeof body.error === 'object' && body.error) {
      if (body.error.message) message = body.error.message
      if (body.error.code) code = String(body.error.code)
    } else if (typeof body?.error === 'string') message = body.error
    else if (body?.message) message = body.message
    if (body?.code && code.startsWith('http_')) code = String(body.code)
  } catch {
    // not JSON
  }
  throw new RelayError(res.status, code, message.slice(0, 200))
}
