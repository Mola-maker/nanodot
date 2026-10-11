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

## Fix round1 — I1/I2, same implementer

This appendix supersedes the earlier candidate's credential-safety claims where
I1/I2 apply. Exact round base is `9cdb63014bdcac5860cf29400321858b9417c098`.
Root supplied full formal review `api-proof-review.md`, SHA256
`e8ca284da5451a20c3fe1256d878a7dd50a63b65571d9de9157c1e1b15b80afa`,
verdict C0/I2/M2. It was read in full and both Important findings were reproduced
before fixes. Root authorized the shared TEST-ONLY input consumption core,
actual Android Os adapter, real host POSIX substitute controls, and narrowly
scoped public native fixtures. No main/release sourceSet, dependency/version,
business provider/body, transport ownership policy or normal-state surface changed.
No helper/reviewer was dispatched. Review approval belongs to root's same reviewer.

I1 correction: the shared `ProofInputConsumption` core opens no-follow, verifies
the same metadata, reads/wipes bounded scratch and KEEPS that descriptor open
through fixed-path removal and the pathname-absence check. It then fstats that
SAME live descriptor, requires the original device/inode and nlink0, and only
then constructs/returns the credential String. Surviving hardlinks or renamed
originals fail closed with only `Proof input rejected`, without cause/raw OS
message. use/finally closes the handle and wipes scratch on all paths; finally
cleans only the fixed cache entry. The consumer never seeks/deletes unknown
aliases. `PrivateProofInput` is the actual Android adapter: original FileDescriptor
is passed to Os.fstat and stays owned by FileInputStream until core returns/fails;
Os.open remains O_NOFOLLOW/O_CLOEXEC and Os.remove remains restricted to the fixed
entry. The manual case still cannot construct provider/HTTP resources before the
core returns successfully. Its default public-race hook is no-op.

The four focused host controls run that SAME core against real Linux/JDK POSIX
operations. No fake android.* classes or production DI were introduced.
The owned public inode's live descriptor is found by device/inode in /proc/self/fd;
reading Unix metadata through that descriptor link provides original-inode nlink
after rename/unlink. Host UID/mode/symlink handling uses actual Unix metadata and
NOFOLLOW_LINKS. A controlled after-read hook creates a hardlink, or renames original
and installs a public replacement. Controls assert failure before handoff, original
alias remains readable, fixed entry removed, post-removal nlink1, descriptor closed,
and scratch zeroed. Unaliased success observes post-removal nlink0; no-file returns
before any open. This is **actual host POSIX/shared algorithm proof**, not Android
adapter/native filesystem permission proof.

Three root-later public-only native methods were added to the SAME existing manual
file: publicSyntheticUnaliasedInputIsRemoved, publicSyntheticHardlinkRaceIsRejected,
publicSyntheticRenameReplacementRaceIsRejected. They assume the fixed input and
known `nanodot-api-proof.public-alias` are absent before creating synthetic data,
use exclusive/no-follow0600 creation and actual Os.link/Os.rename, and clean only
those known fixture-created paths. They never construct transport/provider, read
an existing handoff or reserve an actual attempt. They were COMPILED ONLY here;
root must select/run them before any real credential handoff. They do not grant
this worker native/ADB/device authority or advance the actual counter.

I2 correction: the literal raw-line guard is retained. Before forwarding any data
line, the owned stream now handles exact DONE separately, scans standard quoted
JSON structural depth≤32 before recursive parser allocation, parses one complete
JSONObject, and recursively inspects decoded keys and every STRING value through
objects/arrays (≤2048 visited values). Unicode-escaped finish_reason, nested usage
strings and nested keys containing the credential are rejected BEFORE either raw
or decoded provider diagnostics can see the line. Malformed/non-object/trailing
JSON data, nonstandard single-quoted data, overdeep structure or traversal overflow
fail closed. Non-data comment/event/id/blank lines retain the literal guard and
are not sent to a JSON parser; the provider does not parse their diagnostics.
SSE forwarding remains incremental and byte-preserving for accepted lines,
16KiB/line and256KiB total; no request-body rewrite, DONE/finish/usage adaptation,
cap/thinking change or new business transport API. All parse/read failures become
fixed `Proof stream rejected` IOException with no raw cause/message.

### Round1 RED/GREEN and honest failures

All new evidence is under ignored `work/api-proof/fix-round-1/`; original sixteen
evidence-manifest entries were verified unchanged. Original10GREEN, initial RED,
reader failure, literal mutation and invocation failure remain immutable. Original
source-manifest hashes bind BASE9cdb blobs; the NEW11-entry fix1 source manifest
binds the current full candidate sources/docs, including unchanged build sourceSets.
The base full report was preserved as baseline-report.md before this append.

- Initial owned-control compile failed because Android's compile classpath does
  not expose Files.writeString/readString. Tests switched to write/readAllBytes;
  no production fix was applied. Proper SSE newline escaping was corrected before
  any escaped-case test executed. Compile failure log retained.
- First targeted7 run:3 escaped finish/usage string/key assertions FAILED as
  intended. Input controls also failed, but those input failures were fixture
  errors rather than valid RED: a transient directory-listing descriptor number
  could be reused by open. This was diagnosed and retained separately, not claimed
  as ownership-race RED.
- Corrected host fixture selects the unique PUBLIC inode by dev/ino, not descriptor
  number-set subtraction. Input-only RED4/3 assertion failures: absent-input passed;
  successful unaliased return lacked post-remove observation, hardlink and
  rename/replacement each wrongly returned a key. Their XML/log were retained.
- JSON-boundary-only RED2/2: malformed and overdeep data were forwarded instead of
  fixed-message refusal. These run against the existing owned response boundary,
  without requiring provider diagnostics to log untrusted malformed data.
- Root requested no competing large compilation while its ARM64 build ran. This
  worker prepared code/docs, then waited for root's exit0 notice before the next
  existing2G, owned-cache Gradle invocation. No shared build/cache writes occurred.
- GREEN focused own batch:19tests,0failures,0errors,0skips (15transport including
  original10 plus5new, and4host ownership controls). Both XML files captured
  fresh at2026-10-11T08:45:06/07. Executed OWN4-file filtered instrumentation
  compile, exit0/BUILD SUCCESSFUL11s,37tasks (5executed/32up-to-date). No native
  instrumentation/device test was executed; no whole package suite was run.

Exact command from WT root, using the same approved SDK/JDK and own GRADLE/ANDROID
user directories:

```sh
./android/src/android/gradlew -p android/src/android \
  :app:testDebugUnitTest --tests 'io.github.nanomuse.apiproof.*' \
  :app:compileDebugAndroidTestKotlin \
  -I "$PWD/work/api-proof/fix-round-1/fixture-only.init.gradle" \
  --offline --console=plain --no-daemon -Pnm.abi=x86_64
```

The filter's exact printed source list was ManualDeepSeekApiProofTest.kt,
PrivateProofInput.kt, ApiProofTransport.kt and ProofInputConsumption.kt, from the
same two owned source roots. api-proof-fix1-fixture-filter.diff records the exact
change from the preserved3-file filter (whose Task4 provenance SHA remains above).
Main and all JVM source compilation remain unfiltered; selected unit execution
only belongs to io.github.nanomuse.apiproof. Known8legacy mountedSessionId full
instrumentation failures remain binding/unwaived; this is never full compile PASS.
Fresh ZIP inspection still found0proof-package classes in the production debug
compile classes jar. No release/API/device/ADB/build scan/push/CI/deploy occurred.

### M1/M2 disposition and root gates

M1 is documented, without filtering any logs. The same three MockWebServer warnings
remain in fresh15-transport XML. Two follow naturally from duplicate-collection
and invalid-cap refusal: connection establishment precedes the network interceptor,
but no HTTP request bytes are sent. The third is the explicit DISCONNECT_AFTER_REQUEST
control: pinned MockWebServer4.12.0 bytecode increments atomicRequestCount and adds
the RecordedRequest at processOneRequest offsets34/47, then that policy closes/
returns false at86..91 BEFORE sequenceNumber increments at370. handle emits the
same generic “didn't make a request” warning whenever sequenceNumber is0. Thus
that warning is compatible with the test's observed one request and deliberately
forced disconnect. This is a dependency diagnostic distinction, not evidence of
an extra request/retry. The immutable JAR SHA is
`6784673687f4ac8f21679b9d4bc7cdb46e1a1ce1be9d3133b36bede59a741561`;
its focused javap output is retained. Unexpected warnings are neither discarded
nor reclassified by a global logger change.

M2 remains inherited SDK XML/AGP8.7.3/SDK36, manifest and main Kotlin warnings;
current scoped run re-emits SDK/AGP warnings while cached main avoids repeating
its compiler warnings. No upgrades or warning-free assertion.

Fix1 source/evidence hashes and exact filter diff are adjacent to this report;
fix1 inert host-handoff.TEMPLATE.sh remains comments+exit64 and adds only root-later
public case selection and same-FD zero-link gate notes. New evidence does not claim
native Os behavior, Android JSONObject execution, reflection or native no-file
skip passed. Root must perform same-reviewer re-review, native public controls,
no-input skip, exact target/header-mask provenance, normal-state baseline/after
checks and device ownership/release before supplying one private input and
reserving the actual case. Worker actual credential reads/API/native actions0;
shared actual counter remains root-supplied1/15. Independent logger approval at
be882 C0/I0/M1 is unchanged. This implementer does not approve its own fixes.


## Fix round2 — narrow strict pre-validation comment boundary

Exact BASE is `674cb4bce7c614d484253b1d646278b6be50a007`. The full SAME-reviewer
formal report `api-proof-fix-round1-review.md` was read and SHA256 verified:
`67c7abe60e41b13c63cd408e9f2a133cb72edd539ef1c757e96ff200145be8b9`.
It marks original I1/I2 ADDRESSED, prior M1 documentation addressed and M2 baseline
retained; source approval is withheld for exactly one NEW Important. This appendix
addresses only that new response pre-parser boundary. It does not reopen original
I1/I2 or assert source approval on behalf of the implementer.

The defect: the scanner considered quotes inside string-external block comments
as standard JSON quotes. In the exact public payload
`{"ignored":/*"*/` + `[` repeated33 + `0` + `]` repeated33 + `/*"*/}`,
comment quotes shield the array brackets and the scanner reaches JSONTokener
without its claimed pre-allocation nesting rejection. Actual Android tokenizer
comment support remains unverified; no Android acceptance, crash or allocation
claim is needed for this source defect. JVM parser refusal alone is inadequate.

Minimal correction: add `/` beside the existing single-quote rejection in the
scanner's **outside-quoted-string** branch. It rejects comment syntax before any
JSONTokener construction. The existing handling of quoted strings and backslash
escapes is untouched; `/` and `\/` inside standard strings continue to forward
unchanged. Scanner depth32, recursive decoded key/string inspection2048, literal
credential guard, 16KiB/line, 256KiB total, DONE handling, ownership, same-FD input
core, provider/body/cap/thinking and original sourceSets are untouched. No production
business/provider API or main/release source inclusion changed.

One test-only observable boolean `tokenizerReached` is set immediately BEFORE
`JSONTokener(payload)` and never reset. It has a private setter and no value from
response/input text. This is an observation seam in the existing TEST-ONLY helper,
not a parser replacement, fake Android class, copied scanner or production DI.
A false observation after refusal therefore directly establishes pre-validation
rejection instead of host tokenizer rejection. It is never written into the manual
result or any public response diagnostic.

### Exact controls and RED/GREEN

All new evidence is in ignored `work/api-proof/fix-round-2/`; no previous artifact
was overwritten. Original16 evidence hashes and fix1's18 evidence hashes were
verified unchanged. Original19 GREEN and every historical failure remain preserved.
`baseline-report.md` preserves the full BASE674cb report before this appendix.
The new11-entry source manifest covers the same full candidate source/document
list as fix1, binding the changed two files and unchanged ownership/build/docs.
The new13-entry evidence manifest binds fix2 logs/XML/filter/baseline plus the
supplied formal review. Two premature result snapshots copied while the final
Gradle process was still running are labeled `premature-prior-result-snapshot-*`:
they contain the previous failed run and are NOT final verification evidence.
Final XML was copied only after the process returned exit0 and checked separately.

The two new negative methods use exactly the requested shielded33-array payload
above and `{"ignored":/*public*/0}`. Each independently owns two public loopback
controls: one reads the actual owned response boundary, one collects the actual
OpenAIProvider Flow. No credential handoff is read. Boundary assertions require
fixed `Proof stream rejected`, null cause, zero forwarded sink bytes,
`tokenizerReached=false`, no DONE, closed response and one admitted exchange.
The actual provider asserts only its pre-existing local Started lifecycle chunk,
no response-derived Text/Usage/Finished/Thinking/tool/media chunks, no tokenizer,
no DONE and closed response. Each own transport admits one request; server2 totals
belong to the two separately constructed controls, never retries/follow-ups.

The positive standard data line is exactly
`{"choices":[],"public":{"url":"https://public.invalid/a/b","path":"a\/b"}}`.
It is prepended to the existing READY/final-choice-usage/DONE SSE. Raw owned-boundary
reading asserts the entire response body equals the original string, including
literal URL slashes and escaped path slash. A separate actual-provider collection
asserts READY, stop, freshinput6/cache4/prompt10/output2, DONE, closed response and
one exchange. The URL is public response text only and is never visited.

- Meaningful initial RED:3 new tests,2 assertion failures,0errors/0skips at
  2026-10-11T08:58:40. Both negative failures explicitly say
  `Comments must fail BEFORE JSONTokener allocation`: host tokenizer rejection
  already produced the fixed error, but the observation was true. The positive
  unchanged raw body and actual-provider control passed before the minimal fix.
- First final22 batch after slash correction:22tests/2failures,0errors/0skips.
  Both new negatives passed the boundary pre-validation/zero-forward checks but
  failed the fixture's initially overstrict `chunks.isEmpty()` assertion. Actual
  provider source sends `LLMStreamChunk.Started` before reading a response; this
  local lifecycle block is not remote content. The failure log/XML remain.
  Root explicitly ruled that no forwarded chunks means no response-derived blocks
  and authorized the exact `listOf(LLMStreamChunk.Started)` assertion. No provider
  change or broad chunk ignoring occurred. Initial meaningful RED remains valid.
- Final verification after that fixture correction:22tests,0failures,0errors,0skips
  (18transport = original15+3new, plus4unchanged host input controls), XML timestamps
  2026-10-11T08:59:59 and09:00:00. Gradle exit0/BUILD SUCCESSFUL18s,37tasks,
  3executed/34up-to-date. No repeated verification after this successful affected
  batch and no whole business/project suite run. One extra batch was justified
  only by the newly discovered fixture failure.

All three invocations used the same approved SDK/JDK and existing owned2G Gradle
profile, own `work/api-proof/gradle` and `work/api-proof/android-user`, `--offline`,
`--console=plain --no-daemon -Pnm.abi=x86_64`. Initial RED selected only
`*.ApiProofTransportTest.*comment*` and `*.ApiProofTransportTest.*slashes*`.
The two affected batches used this exact command:

```sh
./android/src/android/gradlew -p android/src/android \
  :app:testDebugUnitTest --tests 'io.github.nanomuse.apiproof.*' \
  :app:compileDebugAndroidTestKotlin \
  -I "$PWD/work/api-proof/fix-round-2/fixture-only.init.gradle" \
  --offline --console=plain --no-daemon -Pnm.abi=x86_64
```

The fix2 filter is a byte-identical COPY of the preserved fix1 filter (no new
fixture/helper filenames): SHA256
`35d87a7b736724b640c7dd97be96d8fed3e89a3de3216062096ba8a521a7f050`.
Its Task4 provenance and exact3→4 diff remain in previous appendices/artifacts.
OWN4 instrumentation Kotlin compile **executed** in the first22 batch, printed
exactly ManualDeepSeekApiProofTest.kt, PrivateProofInput.kt, ApiProofTransport.kt
and ProofInputConsumption.kt, and completed before the new unit assertion failure.
The final successful batch reused that compile UP-TO-DATE against unchanged
shared/native source. Both source roots remain; main and all unit source compilation
are unfiltered. Known8legacy mountedSessionId full instrumentation errors remain
binding/unwaived; neither batch establishes full instrumentation compile success.
Fresh production debug compile classes.jar inspection still found0proof-package
classes. No native test, APK/release/device/ADB operation occurred in this worker.

The existing three MockWebServer warnings remain visible with their fix1 precise
explanation; SDK XML/AGP/manifest/Kotlin baseline remains and no upgrade/suppression
was applied. Logs preserve these warnings without a warning-free claim.

Root must obtain SAME-reviewer scoped judgment on NEW Important1 and any new breakage
from this narrow diff. Original I1/I2 source findings remain addressed, while public
native filesystem controls/no-file skip, Android tokenizer behavior, reflected
client identity, target/source/APK header-mask provenance, owned teardown and normal
state baseline/after checks remain independent root runtime gates. This worker used
only public synthetic loopback data: real credential reads/API/native/device
operations0, shared actual budget unchanged1/15. Logger be882 C0/I0/M1 independent
approval remains valid. No helper/reviewer dispatch, push/CI/deploy or actual proof
claim. Root alone owns eventual native/real-provider execution.


## Public native runtime preflight fixture appendix

BASE `6d3cbfb08521c736e1ebd2025f6c2d34aacf9338`. Root reported SAME-reviewer
136ce777 source approval for fix2 and successful own test APK assembly. This new
acceptance-preparation task does not reopen the approved slash fix or I1/I2.
The implementer did not independently execute native cases or claim root's runtime
results. The existing public filesystem3 cases and no-input skip do not establish
Android JSONObject or actual reflected-client identity, so root explicitly requested
two public-only characterization methods before any actual input handoff.

Only `ManualDeepSeekApiProofTest.kt` received code additions, appended after its
existing methods. Existing four test bodies and their helper prefix are byte-for-byte
unchanged, checked against BASE; PrivateProofInput, ProofInputConsumption,
ApiProofTransport and unit tests are unchanged. No main/release API, dependency,
sourceSets, fixture filename/filter list or provider behavior changed.

`publicSyntheticRuntimeProviderAndStandardJsonPreflight` constructs the existing
owned ApiProofTransport with the public constant `synthetic-proof-key` and an inert
`https://public.invalid/chat/completions` URL. It invokes actual provider(), whose
existing reflection checks require field type/owned client identity, then independently
asserts the exact owned-client reference equals the provider's private client.
No Flow is created or collected and no HTTP Call is created/executed. It reflectively
invokes the SAME private validateSseData with standard JSON choices[]/public.url
`https://public.invalid/a/b`/public.path `a\/b`, requires successful return and
`tokenizerReached=true`, no DONE, HTTPstatus0 and exchangeCount0. A successful native
execution will characterize actual Android JSONTokener/JSONObject/traversal, while
this compile alone does not establish that success. Finally closes only owned
resources and requires isClosed plus exchangeCount0; the original shared provider
pool/dispatcher remain untouched by the existing helper's ownership policy.

`publicSyntheticRuntimeDecodedEchoAndCommentPreflight` uses three separately owned
fresh transports so the monotonic tokenizer observation starts false per input:
standard JSON with Unicode-escaped synthetic credential in decoded STRING value;
standard JSON with that escaped credential in a decoded object key; exact
comment-shielded33-array negative from fix2. All raw payloads lack the literal
synthetic credential. Direct precheck rejection must be IllegalStateException with
no cause and no synthetic key in its message. Decoded-key/string cases require
tokenizerReached=true, while the exact comment-shielded payload requires false,
characterizing native parser reach versus pre-parser refusal without copying the
scanner, substituting JSON classes or adding a business DI/API seam. Each requires
HTTPstatus0/exchangeCount0/no DONE; finally ownedclose/isClosed/zeroexchange.

The private fixture bridge uses reflected validateSseData and unwraps only
InvocationTargetException to its actual target exception for assertions. It does
not print exception data or write results. These are bounded public precheck inputs;
the bridge deliberately bypasses the response source's outer fixed IOException
wrapper and byte-forwarding layer. Consequently these new methods characterize
native JSON validation/reflection/ownership only, not SSE transport, raw error
rendering, a network model response or the actual manual proof. The earlier22 host
controls and native runtime/actual proof remain distinct evidence boundaries.
Neither method accesses context/cache/PrivateProofInput, preferences/session/DAO,
result storage, real credentials or attempt reservation. Constructing URL objects
is not a URL visit; no DNS/HTTP operation is invoked.

### Scoped compilation and preserved evidence

Root explicitly requested characterization with no artificial RED: existing behavior
is observed on Android later, not deliberately broken here. No unit suite was rerun
because shared code/22 unit sources did not change. One necessary offline OWN4 compile
was executed using the approved SDK/JDK, same existing owned2G Gradle profile and
own Gradle/Android-user directories:

```sh
./android/src/android/gradlew -p android/src/android \
  :app:compileDebugAndroidTestKotlin \
  -I "$PWD/work/api-proof/native-runtime-preflight/fixture-only.init.gradle" \
  --offline --console=plain --no-daemon -Pnm.abi=x86_64
```

Exit0/BUILD SUCCESSFUL9s,30tasks (2executed/28up-to-date). Executed Kotlin compile
printed exactly ManualDeepSeekApiProofTest.kt, PrivateProofInput.kt,
ApiProofTransport.kt and ProofInputConsumption.kt from the same two source roots.
Filter is byte-identical to fix2/fix1, SHA256
`35d87a7b736724b640c7dd97be96d8fed3e89a3de3216062096ba8a521a7f050`;
main/all unit compilation remains unfiltered. Known8legacy mountedSessionId full
instrumentation errors remain binding/unwaived. This is ONLY the owned4-file compile,
not full instrumentation success, APK assembly or native test execution.
`git diff --check` passed. Existing SDK/AGP warning boundaries remain; no upgrade or
warning suppression. No new failed build/test artifact arose in this appendix.

Ignored evidence directory `work/api-proof/native-runtime-preflight/` holds only
compile.log, byte-identical fixture-only.init.gradle and baseline-report.md.
Adjacent new source11/evidence3 manifests hash the full candidate source list and
those immutable artifacts. Original16 + fix1 18 + fix2 13 evidence entries were
re-hashed and all matched. Original22/0 GREEN plus every RED/error/fixture failure
is preserved. The report's old native-pending assertions are superseded only by
root's separately verified later runtime evidence, never by this worker.

Root must obtain one scoped source review of these appended public cases, assemble
its owned test APK and select the TWO exact new methods only for the additional
runtime checks. Root owns native execution/device5580 and the actual credential/API
budget. This worker performed no device/ADB/native test, key/.env/private-state read,
API request, Flow collection, model invocation, helper/reviewer dispatch, push/CI/deploy
or attempt reservation; supplied shared count remains1/15. Independently approved
fix2 at6d3cbfb0 and loggerbe882 remain approved facts; these newly appended fixtures
are a pending candidate until root review. No actual/native proof is claimed here.


## Public native hardlink capability diagnostic appendix

BASE `5713591014c3f3a666ccafb4cbd302b5eea53262`. Root received FULL SAME-reviewer
`api-proof-native-runtime-review.md`, SHA256
`6e0172b01b049cc058f8f95acdc978aee6ce78fa45403322327818730a30a5a9`,
Approved C0/I0/newM0 for the two public native runtime preflight methods. That source
gate is approved, while those methods' native execution remains pending per root.
This appendix neither reopens their implementation nor changes any security helper.

Root reported an actual native failure on its OLD signed6d APK: unaliased and
rename/replacement cases passed, absent-input was -4 skipped before provider, but
hardlink fixture failed at alias.readText with ENOENT. That evidence is root-owned
`work/private/android-api-public-native/instrumentation.txt`; this worker did not
read broader private data, alter that file or independently rerun a native case.
The observation DOES NOT establish that Os.link succeeded. Existing runCatching
wrapped callback errors into the same consumer fixed rejection, so a native Os.link
failure could satisfy the refusal assertions and only fail at later alias reading.
No SELinux/backing-filesystem/permission cause is inferred from ENOENT alone.

Only the same manual TEST-ONLY file changed. Existing
publicSyntheticHardlinkRaceIsRejected now sets linkCompleted only AFTER successful
Os.link, records its ErrnoException.errno if that operation throws, and requires
linkCompleted BEFORE any claim of expected consumer rejection/alias survival.
Its fixed public assertion includes errno (or null for unknown failure), directs
root to the diagnostic and never silently skips or reports green on a failed
callback. Consumer error sanitation, post-removal zero-link condition, metadata
checks and security algorithms are unchanged. Unknown callback failure also leaves
linkCompleted=false and fails rather than being accepted.

Added ONE `publicSyntheticHardlinkCapabilityDiagnostic`. It exclusively creates
`nanodot-api-proof.public-link-source` and `nanodot-api-proof.public-link-alias` in
the target app's same cache; both must be absent before any create and no existing
entry is removed. These distinct PUBLIC paths never touch the fixed credential
input/result. Source is exclusive O_NOFOLLOW/O_CLOEXEC0600 and contains only
`public-link-diagnostic`. The same live source FD is held by FileOutputStream through
metadata, Os.link, source removal and alias cleanup. Initial fstat/lstat device/inode
identity and nlink1 are checked. Actual Os.link success requires alias original-inode
identity and both alias/fd nlink2; explicit Os.link ErrnoException is captured only
around that operation, requires absent alias and original fd nlink1. Removing the
original pathname then requires fd nlink1 if alias exists, otherwise0; removing an
owned alias finally requires same fd nlink0 before descriptor closure.

The diagnostic sends instrumentation progress status2 with key
`public-link-diagnostic`. Value is a JSON object containing ONLY public errno and
boolean observations (no paths, inode numbers, content, credentials or exception
messages). `errno` is actual Os.link errno,0 for completed link; `linkCompleted`
is the operation outcome. Other booleans establish fd/path identity, initial one
link, alias/same inode and two links on success, or alias absence/single link on
refusal, then original-path removal and zero-link cleanup. Cleanup ErrnoException
adds only integer cleanupErrno and fails fixed. Unexpected metadata/setup/refusal
ambiguity fails with a fixed message/no raw cause; it is not classified as capability
refusal. Exclusive owned public paths are removed on failure where created, and the
FD is closed by use. Instrumentation progress contains no real key or API outcome.

An explicit Os.link capability refusal may complete this diagnostic characterization
with linkCompleted=false/nonzeroerrno; that is **not** a hardlink-guard PASS. Root
must interpret the public record together with JUnit completion:

- If linkCompleted=true, errno0 and all identity/count/cleanup checks pass, root can
  next run the CORRECTED hardlink race fixture to establish post-link guard behavior.
- If linkCompleted=false with nonzeroerrno, alias absent, single-before-removal and
  zero-after-cleanup checks pass, root has actual capability-refusal evidence. Keep
  native hardlink-guard acceptance unproven; preserve host POSIX ownership proof and
  seek an explicit bounded acceptance ruling or further public-path diagnosis.
  Do not rename the diagnostic PASS into security-guard PASS or silently waive it.
- If JUnit fails, errno0/noncompletion, ambiguous alias/count or cleanup failure,
  do not infer capability/security behavior. Inspect only this public diagnostic
  record and retain the failure before deciding the next bounded observation.

The approved two public runtime methods plus their private reflection bridge were
verified byte-identical to BASE. PrivateProofInput, ProofInputConsumption,
ApiProofTransport, provider/business source and all22 unit sources are unchanged.
No callback test was artificially broken to fabricate RED: root's original native
failure motivated this diagnostic; the implementation worker compiles only.

### Verification and scope

One necessary OWN4 compile-only invocation used the same approvedSDK/JDK/owned2G
cache/user directories, offline/no-daemon, no unit rerun:

```sh
./android/src/android/gradlew -p android/src/android \
  :app:compileDebugAndroidTestKotlin \
  -I "$PWD/work/api-proof/native-link-diagnostic/fixture-only.init.gradle" \
  --offline --console=plain --no-daemon -Pnm.abi=x86_64
```

Exit0/BUILD SUCCESSFUL8s,30tasks (2executed/28up-to-date). Executed Kotlin compile
printed the same exact FOUR owned filenames/two roots; byte-identical filter SHA256
remains `35d87a7b736724b640c7dd97be96d8fed3e89a3de3216062096ba8a521a7f050`.
Main/all unit sources remain unfiltered, sourceSets/versions unchanged. Known8legacy
mountedSessionId full instrumentation errors remain binding/unwaived; this compile
is never a full instrumentation PASS. `git diff --check` passed. ExistingSDK/AGP
warnings remain, no upgrades/suppression.

Ignored `work/api-proof/native-link-diagnostic/` preserves compile.log, unchanged
filter and BASE full report. Adjacent `api-proof-native-link-source-manifest.txt`
has11 full candidate source entries; `api-proof-native-link-evidence-manifest.txt`
has3 evidence entries. Both use sha256sum text:64hex, two spaces, WT-relative path,
one entry per line. Original16 +fix1 18 +fix2 13 +native-preflight3 evidence hashes
all matched unchanged; original22 host GREEN and every historical failure remain.
Root's native failure evidence is separately owned and was not read/modified here.

This diagnostic/callback-tightening candidate needs scoped source review, then root
runs ONLY the diagnostic method first. No native success, capability outcome or
hardlink cause is claimed by compilation. Root alone holds device/native execution,
real-key/API and attempt authority. This worker performed no input/.env/private-state
read, provider/Flow/model/API/native/ADB/device operation, helper/reviewer dispatch,
push/CI/deploy or counter reservation; supplied shared actual count remains1/15.
Approved source facts at loggerbe882, fix2 6d3cbfb0 and runtimepreflight57135910 remain;
this new candidate is pending root review independently of those approvals.
