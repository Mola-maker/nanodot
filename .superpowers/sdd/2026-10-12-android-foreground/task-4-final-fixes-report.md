# Task 4 final review fixes — R1–R4

Owner: `v2_final_fixes`. Whole-branch findings: `task-4-final-review.md` (reviewed b715d038..3d910a70). One combined source fix wave; Root alone runs Gradle/device and commissions independent rereview. Separately owned glass/gesture/motion/ordinary Chat fixtures are not staged by this worker.

## R1 — reachable Screen capture Stop and strict overlay exclusion

`DotInteractionCoordinator.handle` mounts the bounded owned panel before invoking the suspend sampler/fallback, publishes Capturing before mounting, and passes its own foreground StateFlow into that window. Existing Stop invalidates foreground tickets, cancels the opening job, cancels/rebinds only its admitted adapter binding, retains the panel/draft, and projects fresh evidence. Successful late snapshots still pass admission/binding checks and are closed on rejection.

`ScreenRepository.sample` selects a small platform capture policy before sampling. API34+ foreground Chat and existing Care use native host-window capture. Application-window node selection and Android's native window capture exclude accessibility overlays, so these paths retain visible controls instead of waiting under alpha zero. Fresh node-only reads also need no alpha hiding. Selection/display pixel capture keeps the existing traversal-based exclusion path.

Root approved the compatibility ruling: foreground Chat on API26–33 returns a fresh host text snapshot rather than a display screenshot. Public display capture cannot simultaneously exclude a visible overlay and keep its controls visible. Cost: less pixel context for Double on these older APIs. Existing explicit Care/selection pixel capture and projection permission flows are retained. No permissions bypass or new capture grant. Paired docs describe the limitation.

Primary platform reference: https://developer.android.com/reference/android/accessibilityservice/AccessibilityService (`takeScreenshotOfWindow` excludes accessibility overlay contents).

Tests: new `ScreenCaptureModeTest` (older foreground nodes/newer host-window/unchanged explicit pixel capability); new real-window `DotForegroundReviewTest.heldScreenCaptureKeepsActualStopReachableAndClosesLateSuccess` holds only the sampler, clicks actual enabled >=48dp Stop, checks unchanged window/draft, ticket invalidation/fresh binding and recycled late bitmap/no staged preview. `hostWindowPixelsExcludeMountedStatusWithoutHidingItsControls` separately invokes the real repository/native API34+ screenshot with a mounted status card and first proves a contrasting visible display pixel (a channel below180) inside the mounted panel, then requires white native-window pixels (all channels >=250) at that exact host coordinate plus node exclusion and retained visible window. Held sampler coverage is not itself raw screenshot-callback evidence.

## R2 — actual Care compact placement and bounded user adjustment

`CareOverlayWindow` retains the full placement result, forwards Compact plus Expand/Adjust/drag into `CarePanel` and shared foreground chrome, and caps the fallback at the mature 156dp reply/control budget. Explicit Expand restores the ordinary cap. Move clamps the same native card to the current viewport and stores fractional placement; unchanged target refreshes preserve it, while a changed target resets adjustment. No move invokes enter/changeGoal/cancel/bind/pause. Instruction bounds/leader update follows the same moved card; focus target remains with the real Care owner.

`CareCard.kt` forwards optional callbacks into its existing `DotForegroundChat`. Its remembered goal editor and lazy transcript remain in the same composition. A host-wide target necessarily overlaps every possible card location: explicit adjustment exposes the needed part; docs do not promise geometric nonintersection when impossible.

Behavioral RED was observed before production edits using the offline harness compiling live CareOverlayWindow: all 10 original attachment/Back cases passed, new real-owner collision failed `Compact placement did not reach the actual CarePanel`. GREEN after wiring: 11 passed/0 failed. The test invokes actual production window callbacks and checks movement, retained adjustment, out-of-range drag clamping, expansion and unchanged view/owner; it does not render Compose.

New native `CareOverlayTest.compactTargetCareRetainsTranscriptWhileAdjustedDraggedAndExpanded` uses the real controller/validator/session with an inert wide-target snapshot, actual native 48dp adjustment/Expand (hit regions intersect actual card/host) and raw drag, retained window/conversation/goal/generation, then an actual unsent editable Care draft retained through a shorter-host relayout. Target collision and goal editing are distinct phases because editing invalidates target evidence. It does not claim exact remembered scroll index/offset; same composition/list persistence remains source proof, with broader ordinary Chat scroll coverage elsewhere.

## R3 — owner-scoped error evidence

Projection accepts adapter capture errors only in Chat/Screen with an admitted binding. Care uses its controller error evidence. Opening Care no longer announces an unrelated previous Screen failure. Independent business owners and their generation checks remain unchanged.

New `DotForegroundReviewTest.failedScreenCaptureDoesNotContaminateHealthyCareStatus` drives unavailable capture and unavailable fresh fallback through the actual coordinator, observes Error, opens healthy Care, observes Idle, checks the status resource and actual mounted waiting caption plus Stop. Constructor sampler dependencies substitute only local capture outcomes; no network/provider. No behavioral native RED was executed by this worker for R1/R3: their source findings were independently reviewed; native execution is Root-owned.

## R4 — maintained standalone Care attachment harness

Updated controlled stubs for foreground target/state, current status capsule and actual Care callback surface; attachment now executes the controlled content callback. Added native width/height and existing leader-argument compatibility. Original attachment/disposal/Back tests still run against live production files. Harness contains no Gradle subprocess, downloads or device calls.

Exact harness arguments (absolute cached compiler/runtime jars) are saved in `evidence/task-4-final-fixes/harness-args.json`. Reproduce from this checkout with:

```bash
python3 - <<'PY'
import json, pathlib, subprocess
args = json.loads(pathlib.Path('.superpowers/sdd/2026-10-12-android-foreground/evidence/task-4-final-fixes/harness-args.json').read_text())
raise SystemExit(subprocess.run(args, check=False).returncode)
PY
```

Observed files: `care-harness-baseline.log` (10/0), `care-harness-red.log` (10/1 expected collision), `care-harness-green.log` (11/0). `python3 -m py_compile scripts/android/test-care-card-attachment.py` and `git diff --check` passed. No Gradle/device command was run by this worker.

## Separate native fixture corrections within owned lifecycle file

Per Root's native evidence, `DotOverlayLifecycleTest` service-connected lazy probes run on Main (same instrumentation deadlock class already corrected in gesture/motion). Screen-off coverage waits boundedly for actual !interactive AND zero owned windows rather than assuming broadcast delivery within 500ms. Existing zero-window assertions and late-accessibility-event checks remain; wake observes actual attached orb boundedly. Root saw prior sleep fail in the matrix but pass isolated on unchanged production: fixture synchronization correction, not a production repair.

## User-approved glass/docs steering and unproven R5

Separate glass author owns `DotForegroundChat.kt` and new glass helper, committed independently. Paired Android/design docs and Unreleased describe transparent blue base/edge highlights, empty-history host readability, local reading plates, current validated Care target avoidance, and movable compact fallback. No arbitrary reading/gaze/all-text inference is claimed.

Root's initial R5 stale-window hypothesis was not established: candidate574 timeout evidence shows only orb/current external host, no mounted Chat panel and no T4_PANEL diagnostics. No production rebind change was made from that inference. Independently owned ordinary Chat fixture waits for native host readiness after font configuration; Root owns retest.

## Verification/handoff state

First Root shared build failed at CareOverlayWindow.kt186: ComposeView.apply's implicit View.foreground Drawable shadowed the owner StateFlow. Qualified `this@CareOverlayWindow.foreground`; offline harness rerun 11/0. The controlled harness did not model View.foreground and therefore could not catch the receiver ambiguity. Native compile remains necessary evidence.

Kotlin/test sources frozen for Root shared rerun. Root-reported build/unit/native results will be appended after completion; compile cannot replace native acceptance. Three separately owned diagnostic files (`DotForegroundGestureNativeTest.kt`, `DotForegroundMotionNativeTest.kt`, `DotChatPanelTest.kt`) untouched. Login/auth/gateway/credential/endpoint/transport/API boundaries unchanged. No remote stop, model request, new conversation store, version bump or upstream source edit.

Root shared build2 log `task4-glass-integration-build2.log` ends BUILD SUCCESSFUL in2m25s/79tasks. Worker read the completed log and fresh ScreenCaptureModeTest XML (2tests/0failure/error). After completion strengthened test-only capture pixel discrimination and clipped Care hit regions; another AndroidTest compile is pending Root. Production/unit sources are unchanged.

Root reported627tests/89suites with0failures/errors/skips; worker independently summed current scoped XML: 89 suites, {'tests': 627, 'failures': 0, 'errors': 0, 'skipped': 0}. Main/testAPK pinned by Root as glass-integrated-r1. New capture marker searches the lower half of the mounted panel over the fixture's empty white host, avoiding upper fixture content. Test-only strengthened sources need a fresh AndroidTest compile.

## Production source proof

- `android/src/android/app/src/main/java/io/github/nanomuse/dot/DotInteractionCoordinator.kt:25` — sampler dependency boundary.
- `android/src/android/app/src/main/java/io/github/nanomuse/dot/DotInteractionCoordinator.kt:115` — owner-scoped capture error.
- `android/src/android/app/src/main/java/io/github/nanomuse/dot/DotInteractionCoordinator.kt:196` — pre-suspension mounted status/Stop.
- `android/src/android/app/src/main/java/io/github/nanomuse/dot/DotInteractionCoordinator.kt:202` — late snapshot admission/disposal.
- `android/src/android/app/src/main/java/io/github/nanomuse/dot/overlay/DotOverlayController.kt:308` — visible overlay-free capture branch.
- `android/src/android/app/src/main/java/io/github/nanomuse/dot/screen/ScreenRepository.kt:72` — policy-driven capture exclusion.
- `android/src/android/app/src/main/java/io/github/nanomuse/dot/screen/ScreenCaptureMode.kt:9` — platform compatibility policy.
- `android/src/android/app/src/main/java/io/github/nanomuse/dot/care/CareOverlayWindow.kt:63` — Care compact placement.
- `android/src/android/app/src/main/java/io/github/nanomuse/dot/care/CareOverlayWindow.kt:84` — bounded retained Care adjustment.
- `android/src/android/app/src/main/java/io/github/nanomuse/dot/care/CareOverlayWindow.kt:187` — actual owner composition wiring.
- `android/src/android/app/src/main/java/io/github/nanomuse/dot/care/CareCard.kt:73` — shared controls forwarding.

## Native setup follow-up after first Root execution

Root executed `heldScreenCaptureKeepsActualStopReachableAndClosesLateSuccess` from glass-integrated-r1: immediate failure at withHost101 (`getInstance` null, 0.005s), before capture or Stop assertions. Evidence: `work/android-v2-fresh/logs/glass-r1-held-stop/instrumentation.log`. Instrumentation restarted the target process; an external accessibility grant did not establish the in-process service singleton before the fixture read it. This is NOT a passing behavior result or a demonstrated production Stop failure.

Added shared test-only `DotNativeServiceFixture`, used from @Before/@After by DotForegroundReviewTest and CareOverlayTest. It reads and validates raw original accessibility settings before UiAutomation, requires an already-granted unlocked isolated scene, preserves other granted services, removes/regrants only this service through the framework when needed, waits boundedly for connected state using Main-only lazy probes, and rejects a nonempty foreground owner. @After closes only fixture foreground work, restores overlay preference-derived enablement/lock state, observation flags and exact raw settings using the same validated argv restoration as the existing gesture fixture. No manual onServiceConnected, app-data clear, credential or production change. Setup failures retain @After restoration authority.

This worker ran only source/diff checks; no Gradle/device command. Root must compile the helper and rerun native held capture/status/Care cases. Existing 627/89 unit/main/test build evidence precedes this test-only setup follow-up and is not presented as fresh compile evidence for it.
