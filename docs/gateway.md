# Private Workers AI gateway

The gateway in `gateway/` supplies private authentication and a durable free-tier admission gate. Inference is currently disabled: authenticated requests to the staged chat, transcription and speech routes return `503 inference_unavailable`. No model call is made and these unavailable requests consume no admission budget. The empty asset staging directory is not a completed website. This work does not change existing app connections or nanoMuse Cloud.

The intended origin is `https://molamaker.si`, with OpenAI base URL `https://molamaker.si/v1`. These addresses describe the intended deployment, not a verified live service. Existing Cloud and third-party providers retain their real origins.

## Project credentials

An operator supplies independent project credentials to authorized clients. This gateway does not create user accounts, registration, a Hub or conversation sync. A Cloudflare control token belongs only in the deployment environment and must never be entered as a project credential.

`GATEWAY_KEY_HASHES` is a Worker secret containing a JSON object of stable, nonempty client IDs mapped to 64-character SHA-256 hexadecimal digests of their project credentials. Store the original credential only in the client's existing encrypted storage. Never publish a shared key, embed it in an APK or website, or record it in source, logs or documentation. Duplicate digests and malformed or empty configuration fail closed. Assign distinct credentials to distinct clients; the server derives identity from the matched hash rather than a client-supplied ID.

API requests use the `Authorization: Bearer` header. Missing or incorrect authorization returns JSON `401 unauthorized`; credentials in query parameters are refused, even alongside a valid header. Authentication never reads the request body. The gateway emits no request-body, credential or URL logs, and Worker observability is disabled in the staging configuration.

## Admission limits

`FreeGate` uses the SQLite Durable Object migration `new_sqlite_classes`. All future AI routes must use the single namespace object named `global-free-tier-v1` (exported as `FREE_GATE_NAME`); sharding by client, model, route or region would defeat the global budget.

The gate admits at most **40 AI attempts per UTC day across all clients**, and at most **12 per authorized client in a rolling 60-second window**. It checks and increments both counters inside one synchronous SQL transaction before inference. A denial returns `allowed: false` with a positive integer `retryAfterSeconds`; the inference handler must translate this into HTTP `429` and `Retry-After`. When both limits apply, the retry time covers both. Failed or cancelled attempts stay counted: there is no refund method.

The daily counter resets at 00:00 UTC. A rolling window continues across midnight. Expired minute entries are deleted on admission, and the single daily row is replaced when the UTC day advances. Persistent counters survive object eviction. Backwards server-clock movement cannot restore spent quota. Only authorized client IDs, counters and admission timestamps are stored; there are no prompts, responses or chat history.

The internal `admit(clientId, nowMs)` RPC must receive the authenticated identity and the Worker's `Date.now()`, never request fields or headers. The next inference implementation will validate its model and payload, then call admission immediately before attempting AI. The current unavailable routes do not call admission.

These are application request limits, not a Neuron meter. Cloudflare's account-wide Free allocation is **10,000 Neurons per day**, resetting at 00:00 UTC; other applications on the same account also consume it. The actual logged-in target must be verified as Workers Free before enabling inference. Do not purchase, upgrade, switch automatically to Paid models, or alter another project's billing. If the target is not Free, keep inference disabled. See [Cloudflare Workers AI pricing](https://developers.cloudflare.com/workers-ai/platform/pricing/).

## Responses and configuration

API requests first check the secret plus `AI`, `ASSETS` and `FREE_GATE` bindings. Missing or invalid configuration returns JSON `503 gateway_unconfigured`, before checking authorization. With valid configuration, authentication precedes route and method selection; unknown `/v1` routes return JSON `404`, and unsupported methods on staged inference routes return JSON `405`. API failures never fall through to HTML assets. Error responses are not cached.

`GET /healthz` reports only `deployed`, `configured` and `inferenceEnabled`; it neither calls AI nor spends quota. It returns `503` for incomplete configuration and reports `inferenceEnabled: false` in this stage. A healthy response does not verify the account plan, model access or live inference. Non-API GET/HEAD requests use the `ASSETS` binding; the Worker runs first so assets cannot bypass API routing.

## Local verification

Use Node 22 and the committed public-registry lockfile:

```sh
npm --prefix gateway ci --registry=https://registry.npmjs.org/
npm --prefix gateway test
npm --prefix gateway run typecheck
npm --prefix gateway run dry-run
```

Tests use the current Cloudflare Vitest plugin, real local SQLite Durable Objects, randomly generated test credentials, and `remoteBindings: false`. They perform no real model calls. Do not enable remote bindings in tests. Dry-run bundles configuration without supplying a secret or deploying resources; successful bundling does not establish readiness for production.

Implementation references: [SQLite transactions](https://developers.cloudflare.com/durable-objects/api/sqlite-storage-api/), [SQLite migration](https://developers.cloudflare.com/durable-objects/best-practices/access-durable-objects-storage/), [Worker asset routing](https://developers.cloudflare.com/workers/static-assets/binding/) and [Cloudflare Vitest integration](https://developers.cloudflare.com/workers/testing/vitest-integration/write-your-first-test/).
