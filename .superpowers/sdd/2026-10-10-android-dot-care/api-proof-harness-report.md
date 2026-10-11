# Android API proof harness candidate

Prepared 2026-10-11 in the clean supplied `nanodot/android-api-proof` worktree, base
`be8827762ec0333892dd0fe5da8ee59889a050ec`. **No real API request, credential read,
Android/device/ADB operation or native test execution occurred.** Shared actual
attempt total remains root-supplied **1/15**; root alone maintains it. This is a
candidate for independent root review, not actual Android/vendor proof.

## Authorization and scope

Read the full root brief and bounded DeepSeek preflight, AGENTS/CONTRIBUTING, TDD,
writing-good-tests, systematic-debugging and verification skills. Root explicitly
approved minimal annotated test/androidTest sourceSets sharing the SAME
`src/apiProof/java` helper; no main/release inclusion, dependency/version change,
provider rewrite, new public business transport API, ChatViewModel/session/settings
or DAO edits. Root separately approved private-client reflection with identity
verification and response-only diagnostic sanitation. No helpers/reviewers were
spawned. Header masking at base be882 has root-supplied independent approval
C0/I0/M1 and integration; this implementer did not re-review it.

The supplied source-filter provenance was read in full:
`/home/jlu/桌面/nanodot/work/worktrees/android-dot-care/.superpowers/sdd/2026-10-10-android-dot-care/evidence/task-4/task4-only.init.gradle`,
SHA256 `62bfb0653da5ff571aab1c526ef24a0d248987da2f22d51c5658c8dbfb6bc43f`.
`api-proof-fixture-filter.diff` records the exact adaptation. Both the owned native
source root and shared test helper root are retained; includes are only
`ManualDeepSeekApiProofTest.kt`, `PrivateProofInput.kt`, `ApiProofTransport.kt`.
Main and all JVM unit sources remain unfiltered. **This is OWN fixture filtered
compile evidence, never full instrumentation compile success.** The known eight
legacy mountedSessionId errors remain binding and were neither waived nor scanned
again. Root supplied baseline evidence at sibling Task4
`evidence/task-4/recovered-full-check.log`; no original worktree/output was changed.

## Candidate behavior

The manual case selects the actual Kotlin `OpenAIProvider.streamMessage`, via one
helper call and exactly one terminal collection, dispatched to IO so the provider
cancellation watcher can unblock its synchronous reader. It sends only the public
synthetic prompt “Reply with the word READY.” to fixed
`https://api.deepseek.com/chat/completions`, model `deepseek-flash`, documented
`max_tokens=64`, existing method cap64/`max_completion_tokens=64` and explicit
`thinking.type=disabled` through existing chatExtraBody. Empty tools/images,
null system/temperature, default non-OAuth/non-Azure chat route; no body rewrite.
The undocumented companion cap is retained and asserted, not declared accepted
by the actual service. No title/summary/fallback/retry/resubscription loop exists.

The actual private `client` is eagerly constructed, not lazy. Test-only reflection
checks its exact OkHttpClient type, replaces it before streaming, then checks
object identity. If that shape changes, setup fails closed. The untouched original
references the shared pool; no original/shared resource is canceled, closed,
evicted or altered. Owned transport explicitly owns a new dispatcher/pool,
direct proxy setting on that client only, no event logger, retries/redirects off,
authenticator/proxyAuthenticator NONE, connect/write10s/read15s/call40s limits.
One atomic network-interceptor gate precedes OkHttp CallServerInterceptor's HTTP
header/body send. It blocks automatic follow-ups and later Calls/Flow collections;
endpoint/method and actual serialized cap/thinking/prompt are checked before wire
admission. This counts admitted HTTP exchanges, not DNS/TLS attempts or successful
completions. Manual outer timeout45s, no catch-and-retry. Finally cancels owned
calls, evicts only owned pool and shuts down only owned executor.

Response evidence is intentionally bounded and adapted: successful response
headers become fixed content-type only; non-2xx body is closed and replaced by a
constant error; SSE remains incremental, each line≤16KiB and total≤256KiB, and a
line containing the in-memory credential is rejected before provider diagnostics.
No remote-controlled IOException cause/message is forwarded. The transport
observes `[DONE]` separately because the provider also emits Finished on EOF.
This proves the provider parser through an adapted diagnostic response boundary;
**it does not prove unmodified vendor response-header/error-body rendering.**

`PrivateProofInput` accepts only the fixed targetContext.cacheDir cache entry,
regular/non-symlink/no-follow file, exactly0600, same process UID, one hard link,
8..512 ASCII credential bytes. lstat and open/fstat inode/device/metadata checks,
bounded one read, scratch wiping, and removal plus absence check happen BEFORE
provider or HTTP-resource construction. Finally removes the fixed entry on
validation/read failure; remove does not follow the input symlink. Absent input
skips through JUnit Assume before construction and does not write a new result.
No credential in APK, args, preferences, logs, host argv or report. The immutable
credential String exists in process memory; root must finish that process after
its future attempt because explicit String erasure is unavailable.

Atomic owned cache result has mode0600 and only these top-level fields:
endpoint, model, status, configuredcap, exchangecount, deltacount, finish, usage,
text. status contains only outcome enum, HTTP integer, observed DONE and owned-close
booleans; configuredcap contains the two fixed64 caps and disabled thinking.
Finish is allowlisted stop/length or null. Usage is numeric fresh/cached/full
prompt/output/total; total uses full prompt+output. Text saves only exact public
READY or `[omitted]`, never arbitrary vendor content. Failure messages are fixed;
no raw exception/header/error dump is exported. Stale result must be removed by
root before reserving a new run; file presence alone is not fresh-run proof.

## Offline verification and failures

All commands used approved read-only SDK/NDK/JDK from root env.sh (JDK21.0.12.1+1,
SDK36, NDK27.2.12479018), overriding both GRADLE_USER_HOME and ANDROID_USER_HOME
with this worktree's ignored work/api-proof directories. Caches/wrapper were
copied independently; no shared-cache writes/build outputs were reused. rclone.aar
was copied only after verifying ui-system bytes SHA256
`f147950c5d708bf250e46d8fce0bb76314958d11c6fd347bf37b5d4c21e75f11`,
9,935,672bytes. Every Gradle command was --offline and focused; loopback
MockWebServer only. Sandbox initially blocked Gradle's local daemon socket;
auto-reviewed escalation allowed the authorized offline/local-socket execution.
No public network, build scan, publish, push, CI, deploy or device operation.

- Initial RED:5 tests,3 intended assertion failures: duplicate collection,307
  follow and503 Retry-After follow. Minimal test helper lacked the gate/policy.
  Existing request/SSE characterization passed; those already-existing provider
  behaviors are not claimed as new failing-before-fix behavior.
- First GREEN:5 tests,0 failures/errors/skips with the strict shared transport.
- Expanded run: OWN fixture compile failed at exactly two new Os.unlink references.
  Android exposes Os.remove; that direct API mismatch was corrected. No legacy
  source was changed. Initial failure log retained.
- Expanded reader control:10 tests,1 failure. MockWebServer throttle applied to
  request upload too, so no recorded request/active reader was observed; suppressed
  server shutdown error was retained. Replaced it with an empty-body response
  declaring Content-Length1000. Now the test observes headers200 and started
  response reader before timeout, then observes close within4s and owned teardown.
- Expanded GREEN:10 tests,0 failures/errors/skips, OWN three-file filtered fixture
  compile passed. This run did not execute Android instrumentation.
- Sanitation mutation RED:temporarily removed only credential-line rejection;
  the selected echo test failed (1/1 failure). Strict source was restored before
  final verification. No mutation touched production source.
- Final invocation first failed because -I relative path was resolved below the
  -p project path. The command was corrected to an absolute init path; path-failure
  log retained. Final results are recorded below after fresh output inspection.

The ten controls cover actual provider body/current final-choice usage SSE,
second Flow collection prevention,307,503,disconnect,credential echo rejection,
response header/error sanitation,EOF-vs-DONE distinction,observed active-reader
cancellation/owned cleanup,and wrong configured cap refusal before request bytes.
Existing SDK36/AGP8.7.3 compatibility warning, manifest warnings and main-source
Kotlin deprecations remained visible. No claim of warning-free full build.
A local ZIP inspection found zero io/github/nanomuse/apiproof classes in the
production debug compile classes jar; shared helper is test-only. No release
build or APK was produced by this task.

Exact source hashes are in api-proof-source-manifest.txt. Exact evidence hashes
are in api-proof-evidence-manifest.txt. Evidence lives only in this worktree's
ignored work/api-proof; the full report/manifests/filter diff are in the candidate.
The inert ignored host-handoff.TEMPLATE.sh has only comments and exits64; it
performs no transfer/device/API operation. Bilingual docs describe its boundary.

## Required root gates

1. Fresh independent review of candidate/helper/filter/inert handoff template;
   exact target APK provenance and header masking inclusion must be established.
2. Native no-input skip BEFORE provider/network; Android reflection identity,
   sameUID/0600/no-symlink/one-read/pre-network removal and owned teardown need
   root-owned runtime evidence. JVM checks/filtered compile do not establish them.
3. Root owns device5580 release/ownership and any target-app startup/network/state
   behavior. The fixture itself never reads settings or DAO; instrumentation of
   normal target app does not establish startup isolation or normal-state
   preservation. Root must establish its authorized baseline/after comparison.
4. Root alone creates private input and reserves one actual attempt. One failed
   status/400/timeout/cancel consumes it and ends without retry. Root owns the
   ledger, credential transfer, device commands, cleanup and actual result.
5. Direct provider Android/HTTP/SSE/model/key proof remains distinct from canonical
   Dot UI/session/persistence/gateway proof. No actual proof is claimed here.

## Final fresh verification

From this worktree root, with owned environment overrides described above:

```sh
./android/src/android/gradlew -p android/src/android \
  :app:testDebugUnitTest --tests 'io.github.nanomuse.apiproof.ApiProofTransportTest' \
  :app:compileDebugAndroidTestKotlin \
  -I "$PWD/work/api-proof/fixture-only.init.gradle" \
  --offline --console=plain --no-daemon -Pnm.abi=x86_64
```

Exit0, BUILD SUCCESSFUL in11s,37 tasks (3executed/34up-to-date).
Fresh unit XML timestamp2026-10-11T08:28:19:10tests,0failures,0errors,0skips.
Filtered native compile was up-to-date against unchanged restored strict sources;
its prior executed successful compile and exact3-source print are preserved in
focused-verification.log. `git diff --check` exited0. No native test was run.
