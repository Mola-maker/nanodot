# Task 4 — native placement timeout diagnostic

Scope: `DotChatPanelTest.kt` and this report only. Root owns Gradle, native device operations and API budget. Read repository/Android contribution rules, Task 3 brief/report and systematic-debugging before changes.

## Evidence and boundary

Root-run candidate `3d910a70` chat matrix ran four actual native methods: keyboard Back, reopening generation and accessibility-overlay canonical owner passed; `placementKeepsDraftHistoryAndBinding` failed at `openCanonical` line 379, after font scale 2.0 was observed, while waiting for attached plus the exact ordinary foreground frame. The unique fixture's `loadSession EXIT` is present (138ms), so this failure is not a missing canonical history load.

Source evidence: `work/android-v2-fresh/logs/candidate-3d910a70-chat/instrumentation.log` and `fixture-logcat.txt` relative to the task base `/home/jlu/Documents/Codex/2026-10-10/now-make-a-plan-for-the-2`.

The failing method's **before-admission** snapshot already differs from the passing full-screen case:

- Display: 1080×2400, rotation 0.
- Host id 160; accessibility window `(184,53)-(1264,2400)`.
- Matching root id 160, root bounds `(184,53)-(1264,2453)`; `hostViewport` returns the root rectangle.
- Passing preceding method: host/root `(0,0)-(1080,2400)` and settled actual panel `68,1059,945,1320`.

At density 2.625, `foregroundFrame` on the unusual root rectangle produces ordinary frame left 251.5, top 1112, width 945, height 1320 (rounded x252/y1112). Its right/bottom exceed the physical display. This is a concrete hypothesis boundary: stale/transformed launch root geometry versus controller parameters versus WindowManager clipping. The old evidence does **not** record the failing actual panel frame and cannot determine which of these happened. `ScreenRepository.hostViewport` obtains root bounds; the controller and test both use the same unchanged `foregroundFrame` formula. Changing the expected formula or adding tolerance would hide the discrepancy.

## Diagnostic change

`panelWithinSettledHost` logs geometry when an observation changes: pinned host id/viewport, exact expected frame, actual native origin/size, WindowManager requested x/y/width/height, attached/shown state, display size/rotation, density and font scale. The acceptance predicate is byte-for-byte unchanged.

`openCanonical` retains its original assertion; on timeout it captures host windows, native accessibility tree/screenshot and dumpsys window/accessibility **before** placement cleanup removes the panel and restores font scale. Diagnostic failure is suppressed on the original assertion rather than replacing it.

No production edit or acceptance weakening. Exact draft/list index-offset, same view/list/binding, one inert held stream, and visible native input/Expand/Stop intersections of at least48dp remain unchanged.

## Validation and remaining action

`git diff --check` passed. No Gradle/device operation was run by this worker. Root should compile the updated AndroidTest source and run only `DotChatPanelTest#placementKeepsDraftHistoryAndBinding`, retaining `T4_PANEL_GEOMETRY`, `T4_HOST canonical-settle-timeout` and cache files `t4-canonical-settle-timeout-*` if it fails. Native success and root cause are not claimed before that observation.

If actual panel differs from requested parameters while the host stays outside display, investigate native host geometry/admission rather than weakening the test. If actual equals requested while expected changes, investigate refresh ordering. If this isolated rerun succeeds, the original observation remains a transition/cache hypothesis; require the matrix rerun before accepting it.
