# Private Workers AI gateway

The gateway in `gateway/` supplies private authentication, a durable free-tier admission gate, an allowlisted model catalogue, and chat/vision protocol adapters. Chat can attempt inference when the configuration is valid. Transcription and speech routes remain disabled with `503 inference_unavailable`; these routes consume no admission budget. The empty asset staging directory is not a completed website. This work does not change existing app connections or nanoMuse Cloud.

The intended origin is `https://molamaker.si`, with OpenAI base URL `https://molamaker.si/v1`. These addresses describe the intended deployment, not a verified live service. Existing Cloud and third-party providers retain their real origins.

## Project credentials

An operator supplies independent project credentials to authorized clients. This gateway does not create user accounts, registration, a Hub or conversation sync. A Cloudflare control token belongs only in the deployment environment and must never be entered as a project credential.

`GATEWAY_KEY_HASHES` is a Worker secret containing a JSON object of stable, nonempty client IDs mapped to 64-character SHA-256 hexadecimal digests of their project credentials. Store the original credential only in the client's existing encrypted storage. Never publish a shared key, embed it in an APK or website, or record it in source, logs or documentation. Duplicate digests and malformed or empty configuration fail closed. Assign distinct credentials to distinct clients; the server derives identity from the matched hash rather than a client-supplied ID.

API requests use the `Authorization: Bearer` header. Missing or incorrect authorization returns JSON `401 unauthorized`; credentials in query parameters are refused, even alongside a valid header. Authentication never reads the request body. The gateway emits no request-body, credential or URL logs, and Worker observability is disabled in the staging configuration.

## Admission limits

`FreeGate` uses the SQLite Durable Object migration `new_sqlite_classes`. All AI routes must use the single namespace object named `global-free-tier-v1` (exported as `FREE_GATE_NAME`); sharding by client, model, route or region would defeat the global budget.

The gate admits at most **40 AI attempts per UTC day across all clients**, and at most **12 per authorized client in a rolling 60-second window**. It checks and increments both counters inside one synchronous SQL transaction before inference. A denial returns `allowed: false` with a positive integer `retryAfterSeconds`; the chat handler translates this into HTTP `429` and `Retry-After`. When both limits apply, the retry time covers both. Failed or cancelled attempts stay counted: there is no refund method.

The daily counter resets at 00:00 UTC. A rolling window continues across midnight. Expired minute entries are deleted on admission, and the single daily row is replaced when the UTC day advances. Persistent counters survive object eviction. Backwards server-clock movement cannot restore spent quota. Only authorized client IDs, counters and admission timestamps are stored; there are no prompts, responses or chat history.

The internal `admit(clientId, nowMs)` RPC must receive the authenticated identity and the Worker's `Date.now()`, never request fields or headers. Chat validates its model, body, format and budgets first, then calls admission immediately before attempting AI. Disabled audio routes do not call admission.

These are application request limits, not a Neuron meter. Cloudflare's account-wide Free allocation is **10,000 Neurons per day**, resetting at 00:00 UTC; other applications on the same account also consume it. The actual logged-in target must be verified as Workers Free before enabling inference. Do not purchase, upgrade, switch automatically to Paid models, or alter another project's billing. If the target is not Free, keep inference disabled. See [Cloudflare Workers AI pricing](https://developers.cloudflare.com/workers-ai/platform/pricing/).

## Responses and configuration

API requests first check the secret plus `AI`, `ASSETS` and `FREE_GATE` bindings. Missing or invalid configuration returns JSON `503 gateway_unconfigured`, before checking authorization. With valid configuration, authentication precedes route and method selection; unknown `/v1` routes return JSON `404`, and unsupported methods on inference routes return JSON `405`. API failures never fall through to HTML assets. Error responses are not cached.

`GET /healthz` reports only `deployed`, `configured` and `inferenceEnabled`; it neither calls AI nor spends quota. It returns `503` for incomplete configuration and reports `inferenceEnabled: true` when the configured chat handler can attempt inference. This is code/configuration readiness only. A healthy response does not verify the account plan, model access or live inference. Non-API GET/HEAD requests use the `ASSETS` binding; the Worker runs first so assets cannot bypass API routing.

## Chat and image wire protocol

Authenticated `GET /v1/models` returns an OpenAI list with `id`, `name`, and `architecture.{input_modalities,output_modalities}`. The catalogue contains Qwen text→text, Gemma text+image→text and Whisper audio→text. Whisper metadata describes its model capability; its transcription route is still unavailable. MeloTTS is omitted until Chinese output is actually verified. See the current primary model schemas for [Qwen](https://developers.cloudflare.com/workers-ai/models/qwen3-30b-a3b-fp8/), [Gemma](https://developers.cloudflare.com/workers-ai/models/gemma-4-26b-a4b-it/) and [Whisper](https://developers.cloudflare.com/workers-ai/models/whisper-large-v3-turbo/).

`POST /v1/chat/completions` accepts only `@cf/qwen/qwen3-30b-a3b-fp8` and `@cf/google/gemma-4-26b-a4b-it`. A JSON body contains `model`, nonempty `messages`, optional boolean `stream`, and an optional token budget (`max_completion_tokens` or `max_tokens`). Omitted stream is false; omitted output budget is 2,048. Both budgets, if present, must agree. Qwen receives `max_tokens`; Gemma receives `max_completion_tokens`. Only validated messages, budgets, sampling (`temperature`, `top_p`), function definitions and Gemma usage-stream options reach the binding. Unknown top-level fields are discarded. Non-text response formats and non-auto tool choice are refused before admission.

The body limit is 4 MiB, text context (including tool definitions and arguments) is 16,000 JavaScript characters, and output is at most 2,048 tokens. Only Gemma user content may contain an `image_url` part: its URL must be a PNG/JPEG base64 data URL. Remote URLs are never fetched. One image may be sent, with at most 2 MiB decoded bytes and dimensions of 1–1280 pixels. The server validates container framing and dimension headers; the client must re-encode images within those limits. Invalid model, content, image or format receives JSON `400`; an oversized body receives `413`, all before quota admission.

Both models accept documented function definitions. Qwen's verified schema supports simple properties containing `type` and `description`; unsupported schema keywords are refused. Gemma accepts OpenAI function definitions and assistant `tool_calls` plus `tool` results with `tool_call_id`. **Qwen tool history is currently refused with `400`** because its published message schema does not establish their encoding. Reasoning-history input is refused for both models until its encoding is verified; generated reasoning is preserved in output. Android omits this unsupported history field only for the parsed `https://molamaker.si` origin (default/443 port), even when bundled model enrichment enables reasoning. Other origins retain their existing echo rules. Use Gemma for a verified agent tool round trip. There is no automatic model substitution. The gateway returns function calls and never executes tools. Care-mode callers send `tools: []`.

Non-stream responses use `choices[].message`, completion metadata and upstream usage. Streaming responses use SSE `choices[].delta` chunks, preserving text, reasoning, function calls, usage and stop/length/tool finish reasons, followed by exactly one `data: [DONE]`. The documented [native Workers AI response stream](https://blog.cloudflare.com/workers-ai-streaming/) is adapted from `response` text events and explicit `[DONE]` into the same shape. A native `[DONE]` establishes completion; ordinary EOF does not. Empty streams, invalid events, upstream errors and truncated streams emit a sanitized SSE `error` and close without a fabricated stop or `[DONE]`. UTF-8 decoding preserves text across arbitrary byte splits. Empty tool arrays, tool IDs/indexes alone and empty reasoning fields do not establish output; reasoning text and function name/argument fragments must have valid types before they count.

HTTP errors remain JSON: `401` for credentials, `429` plus `Retry-After` for quotas, `503` for unavailable configuration/model service. Errors after streaming headers are sent become SSE errors. Client abort and `reader.cancel()` release and cancel the local upstream reader. Cancellation cannot guarantee that Cloudflare stops already-started inference or billing, and no admitted attempt is refunded.

## Local verification

Use Node 22 and the committed public-registry lockfile:

```sh
npm --prefix gateway ci --registry=https://registry.npmjs.org/
npm --prefix gateway test
npm --prefix gateway run typecheck
npm --prefix gateway run dry-run
```

Tests use the current Cloudflare Vitest plugin, real local SQLite Durable Objects, randomly generated test credentials, and `remoteBindings: false`. They perform no real model calls. Shared wire fixtures are also consumed through Android’s actual `ProviderFactory.streamMessage` and `OpenAIModelsApi` in `GatewayProtocolTest`; no substitute client parser is used. Do not enable remote bindings in tests. Dry-run bundles configuration without supplying a secret or deploying resources; successful bundling does not establish readiness for production.

Implementation references: [SQLite transactions](https://developers.cloudflare.com/durable-objects/api/sqlite-storage-api/), [SQLite migration](https://developers.cloudflare.com/durable-objects/best-practices/access-durable-objects-storage/), [Worker asset routing](https://developers.cloudflare.com/workers/static-assets/binding/) and [Cloudflare Vitest integration](https://developers.cloudflare.com/workers/testing/vitest-integration/write-your-first-test/).
