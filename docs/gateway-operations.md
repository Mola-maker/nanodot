# Gateway operations

The candidate is source-reviewed deployment preparation. Production TLS, models, clients,
Chinese audio and remote counters still require recorded live acceptance. Keep the existing
Cloud accounts/providers and their real origins. The new OpenAI base is
`https://molamaker.si/v1`; use a dedicated project credential, never a Cloud token.

## Reviewed resources and controls

| Resource | Exact intended change |
| --- | --- |
| Worker | Create independent `nanodot-gateway`; retain unrelated Workers including `oi-wiki` |
| AI | `AI` binding; allowlisted Free models only, no paid fallback |
| Assets | `ASSETS`, generated `gateway/public`, Worker first for every request |
| SQLite Durable Object | `FREE_GATE` → `FreeGate`, migration `v1` / `new_sqlite_classes`; global object `global-free-tier-v1` |
| Domains | Custom Domains `molamaker.si`, `www.molamaker.si`; `www` responds 301 to apex before auth; no workers.dev/preview URLs |
| Secret | `GATEWAY_KEY_HASHES`: JSON mapping stable client IDs to unique SHA256 credential digests, entered privately via dashboard or stdin |
| Variables | Initially `INFERENCE_ENABLED=false`, `TTS_ZH_VERIFIED=false`; enable inference only after rechecking Free; `TTS_ZH_LANG` absent until exact Chinese acceptance |
| Mail/DNS | Snapshot record IDs/types first; add only the two web domain connections; preserve all MX/TXT/mail records and existing routes |
| Temporary operator | `nanodot-task6-operator`, no routes/workers.dev/preview URLs; same production `FREE_GATE` external `script_name=nanodot-gateway`, no migrations |
| Operator secrets | `OPERATOR_KEY_HASHES` contains only the dedicated `operator-task6` digest; that identity/digest must also be in production `GATEWAY_KEY_HASHES` for admission; `AUDIO_PROBE_LANG` is an exact candidate variable |
| Local relay | `nanodot-task6-local-relay` is never deployed; local service `OPERATOR` remotely binds `nanodot-task6-operator`, listens on 127.0.0.1 only |

Workers Free must be rechecked at action time. [Workers AI pricing](https://developers.cloudflare.com/workers-ai/platform/pricing/)
provides 10,000 Neurons/day, reset at 00:00 UTC, with Free requests blocked after the allocation.
The app additionally admits at most 40 attempts/day across all AI routes and 12/client/rolling
60 seconds, atomically before inference; errors and cancellations are not refunded. Request
counts are not Neuron estimates. Never upgrade or exhaust inference to demonstrate quota.

## Permission proposal before any new credential

Use the retained authenticated dashboard for one-time creation, SQLite migration, secret entry
and both Custom Domains, after reviewing the exact resource diff. Do not issue a persistent
broad deployment token to bootstrap. Record actual namespace ID, version ID and domain IDs
in ignored evidence; do not invent them before creation.

For later CI, create and verify a protected `gateway-production` GitHub environment before
adding any secret. Its required human reviewer is `Mola-maker` (GitHub user ID `229602071`),
with `prevent_self_review=false` and a custom deployment branch policy allowing only `main`.
The owner explicitly permits the initiating user to review their own deployment; every deployment
still requires Mola-maker's actual human approval. An agent must never submit that approval.
Store only `CLOUDFLARE_API_TOKEN` there and `CLOUDFLARE_ACCOUNT_ID` as an environment variable. Use a short-lived account API token with **Editor only for the existing
`nanodot-gateway` Worker**. CI uploads/activates versions; it does not create Workers, modify
routes/domains, migrate storage, set project secrets or deploy the operator.

The [current Workers authorization specification](https://developers.cloudflare.com/workers/authorization/workers/)
requires product Admin to create a Worker; Editor can deploy existing Workers. Domain changes
also require zone Workers Routes Write and currently do not support per-Worker roles. Hence
domain bootstrap belongs to the already authenticated dashboard; no broad persistent token
is justified. [Durable Object permissions](https://developers.cloudflare.com/workers/authorization/durable-objects/)
follow the implementing Worker. Bindings do not require separate direct-resource permissions
to deploy. No DNS Edit, KV, R2, D1, AI REST inference token, user-details or all-zone scope is
requested for routine version CI. If actual Wrangler authorization rejects this precise scope,
stop and report the failed action; request the specific additional permission only after review.

## Source checks and bootstrap sequence

```sh
npm ci --prefix gateway
npm ci --prefix website
node --check scripts/gateway/verify-live.mjs
node --test scripts/gateway/*.test.mjs
npm --prefix gateway run typecheck
npm --prefix gateway test
node --test site/tests/*.test.mjs
NANODOT_DOCS_BASE=/docs/ npm --prefix website run docs:build
node scripts/site/build.mjs
node scripts/gateway/verify-live.mjs --scan-site gateway/public --report evidence/task-6/static-scan.json
export WRANGLER_LOG_PATH="$PWD/evidence/task-6/wrangler-logs/"
mkdir -p "$WRANGLER_LOG_PATH"
npm --prefix gateway run dry-run
cd gateway
npx wrangler deploy --config wrangler.operator.jsonc --dry-run --outdir dist-operator
npx wrangler deploy --config wrangler.operator-relay.jsonc --dry-run --outdir dist-relay
```

Wrangler compilation logs use the ignored writable `evidence/task-6/wrangler-logs/` directory
instead of a potentially read-only default home directory. Apply this on the next already-required
dry-run; never rerun merely to hide retained logging errors.

Node 22 is required. The gateway test pool sets `remoteBindings:false`; no models run in CI.
The old raw-source GitHub Pages publishing path is closed. Default documentation builds
retain their old base; only the gateway build uses `/docs/`. Generated outputs and evidence
are ignored. Before Phase B archive commit, artifact hashes, account Free confirmation,
existing routes/domain IDs and all DNS record types/IDs. Then, under approved dashboard/CLI
control, deploy the independent Worker with migration and assets and set the hash secret via
private stdin/dashboard (never argv, chat or logs). Keep inference disabled initially. Confirm
health/auth/preflight/404 and valid TLS; then make a separately reviewed configuration change
to `INFERENCE_ENABLED=true` while still keeping TTS false. CI cannot bootstrap this sequence.

## No-AI and bounded live acceptance

Load `NANODOT_TEST_GATEWAY_KEY` from a private local environment with shell tracing off.
Never put it in argv or commit it. The no-AI command needs no attempt ledger:

```sh
node scripts/gateway/verify-live.mjs --base https://molamaker.si --report evidence/task-6/no-ai.json
```

It checks health/configuration, unauthorized/authorized models, JSON/no-store API 404,
unknown webpage no-store 404, exact apex/www CORS preflight with required methods/headers, foreign-origin refusal, public pages/assets,
and credential-free www301. Every authenticated redirect is refused, including same-host
redirects; Bearer is never forwarded. Static built-site checks and response scans are distinct:
the verifier scans fetched public responses for the actual test key, while the builder copies
only its fixed reviewed asset list. A production missing secret returns 503, not a false 401 pass.

Initialize **one** ignored, mode0600 ledger `{ "totalAttempts": 0, "attempts": [] }` for the
entire task. Reserve before every actual dispatch across probes, all clients, cancellations,
failures and retries. Maximum 15; never reset/refund it. The verifier has explicit `text`,
`vision`, `care`, `cancel` stages requiring `--live-ai --free-plan-confirmed --ledger PATH`.
Vision additionally requires a local nonprivate PNG and `--image-sha256`; no remote image fetch.
It records response hashes/status and sanitized request IDs, not credentials/prompts. Chinese
SSE and no-tools structure are automatic checks; image text semantics, care proposal and
native cancellation still require human/client observation. The cancel stage must observe a read pending across
an event-loop turn, invoke local abort/cancel and observe that read close or reject with AbortError.
Empty streams or streams naturally ended before that point fail. Local cancellation does not
prove that upstream AI physically stopped. No successful HTTP implies those.

Budget before dispatch: Android text1, desktop text1, Web text1, iOS text1, vision1, ASR1,
private Chinese/mixed TTS2, conditional production TTS2, care1, cancel1 = 12, leaving3 reserved
for reviewed failures/retries. No-AI tests consume0; skip conditional production TTS if candidate
acceptance fails. Coordinate actual clients before consuming attempts, use the same source
commit and prioritize Android. Native secure storage, overlay and real simulator XCTest are
separate requirements. An unsigned iOS device archive is not simulator test evidence.

## Private TTS and isolated remote counter proof

Do not use remote SQLite previews: [DO environments](https://developers.cloudflare.com/durable-objects/reference/environments/)
document the external script binding to the same Objects and the SQLite remote-development
limitation. Instead deploy the route-less operator service with its own dedicated credential.
[Local development](https://developers.cloudflare.com/workers/local-development/#using-remote-resources-with-durable-objects-and-workflows)
documents access to remote DOs through a deployed Worker and remote service binding.
The relay configuration uses the installed Wrangler schema's `services[].remote:true`.
The relay has no AI/DO binding and forwards only authenticated POST speech/proof paths;
the deployed operator binds the production namespace, and candidate speech calls the exact
`global-free-tier-v1` object before its AI binding. No ephemeral preview namespace is involved.
Installed Wrangler also opens an authenticated transport preview session named
`nanodot-task6-local-relay` via account subdomain/script `edge-preview` APIs. This uploads
Wrangler's proxy transport, not a preview SQLite namespace or a deployed public relay.
Session setup is a separate action-time control operation; actual authentication/permission
scope must be reviewed then. Do not assume the production CI token suffices, create a broad
persistent token, or publish proxy/session URLs or preview tokens. The transport has been
compiled/local-tested; actual remote connectivity remains unverified.

Only after approval and credential setup, start from the repository root:

```sh
node scripts/gateway/start-operator-relay.mjs --free-plan-confirmed --session-controls-reviewed
```

The launcher requires private `CLOUDFLARE_API_TOKEN`/`CLOUDFLARE_ACCOUNT_ID` env,
reads `GET /accounts/<id>/workers/subdomain`, and refuses to launch unless it already exists.
It never logs the subdomain or control token and forces loopback/warn-level Wrangler logging.
Wrangler then uses `GET /accounts/<id>/workers/subdomain/edge-preview` and
`POST /accounts/<id>/workers/scripts/nanodot-task6-local-relay/edge-preview`.
Installed Wrangler describes OAuth `workers_scripts:write` as script/DO/subdomain/trigger/tail
control; this is broader than per-Worker CI. No new OAuth login/default scope or transport
token has been authorized here; exact usable account permissions remain an action-time gate.
Do not bypass the launcher: Wrangler auto-registers a random subdomain via PUT if absent.
Do not use `--local` here: that disables the explicit remote service. Do not deploy the relay.
Invoke `verify-audio.mjs` candidate with `--probe-base http://127.0.0.1:8788/v1`,
`--probe-local-relay-confirmed --probe-private-confirmed --probe-shared-gate-confirmed`, the
exact `--candidate-lang`, and shared ledger. `AUDIO_PROBE_CREDENTIAL` stays in local env.
Hash the two decoded MP3s, verify full ffmpeg decode and duration, then have a person listen
to those exact Chinese and mixed clips; `--stage listen --listened-chinese-and-mixed` binds
listening evidence to both hashes. Only a reviewed follow-up sets production exact
`TTS_ZH_LANG` and `TTS_ZH_VERIFIED=true`; production audio is then separately checked.

The private `/quota-proof` endpoint calls the same production namespace/class but uses
**`task6-isolated-quota-v1`**, 13 server-timed admissions for the operator identity, and has
no inference path. It demonstrates real SQLite rolling-counter denial when deployed; it
never seeds or touches `global-free-tier-v1`. A repeat may admit fewer than12; record that
honestly. Its HTTP429 is explicitly an isolated operator response, **not production HTTP429**.
The existing workerd SQLite tests prove daily40/concurrency/persistence locally; injected
quota refusal tests prove the HTTP pipeline locally. Actual production HTTP429 is unverified
unless naturally encountered within the legitimate<=15 live attempts. Provider daily exhaustion
is likewise unverified unless naturally reached. No reset endpoint or production counter seeding.
For an approved no-AI remote counter check, with the local relay already running:

```sh
node --input-type=module <<'JS'
import { writeFile } from 'node:fs/promises';
const key = process.env.AUDIO_PROBE_CREDENTIAL;
if (!key) throw Error('Dedicated operator credential missing');
const response = await fetch('http://127.0.0.1:8788/quota-proof', {
  method: 'POST', headers: { Authorization: `Bearer ${key}` },
  redirect: 'error', signal: AbortSignal.timeout(30000),
});
const proof = await response.json();
if (response.status !== 429 || proof.scope !== 'isolated-object-not-production-http429'
    || proof.objectName !== 'task6-isolated-quota-v1') throw Error('Counter proof failed');
await writeFile('evidence/task-6/isolated-counter.json', JSON.stringify({
  scope: proof.scope, objectName: proof.objectName, httpStatus: response.status,
  admitted: proof.admitted, denied: proof.denied, retryAfterSeconds: proof.retryAfterSeconds,
  actualProductionHttp429: 'unverified',
}, null, 2), { mode: 0o600 });
JS
```

Installed session teardown clears the refresh timer, aborts requests, terminates the tail
WebSocket and disposes local workerd; no DELETE control API appears in this path. Do not
claim stopping the local process immediately revokes server preview tokens. Record teardown,
then revoke the dedicated operator credential and remove only its temporary Worker via the
reviewed dashboard controls; production DO storage and mail remain. No cleanup was run here.
Remove the operator credential from production hashes after acceptance, stop the local relay
and disable/remove only the temporary operator service after recording its version/bindings.

## Evidence and rollback

Keep sanitized evidence under ignored `evidence/task-6/`: source commit and SHA256 manifests,
Worker/version/namespace/domain IDs, before/after web record and route diff, TLS timestamp,
shared attempt ledger, model/capability PASS/FAIL/unverified, all four real client checks,
audio hashes/listening and exact language. Preserve mail record equality without publishing
mailbox values. CLI auth readiness may be summarized, never log `whoami` personal output.

For an existing deployment, from `gateway/`, record the prior version before activation:

```sh
npx wrangler deployments list
npx wrangler versions list
npx wrangler rollback PRIOR_VERSION_ID --message "Restore reviewed gateway version"
```

Version rollback does not undo Durable Object storage/migrations or Custom Domains. Keep
migration `v1` and namespace; never delete production counters. For the first deployment,
there is no prior gateway version: deploy the same source with `INFERENCE_ENABLED=false`
and `TTS_ZH_VERIFIED=false`, retaining assets/models and clear503 recovery. Restore only the
new web-domain connections if necessary using their captured IDs; never delete mail or an
unrelated Worker. Clients retain prior provider configurations and can select another existing
provider. Do not switch a new installation default before real acceptance; no automatic Release
or TestFlight publication is part of this workflow.
