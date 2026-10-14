---
name: verify-nanodot-android
description: Verify Nanodot Android foreground chat, orb and Care behavior on the existing isolated emulator, and connect native evidence to a paired APK candidate. Use for Android acceptance, UI regressions and installable APK delivery.
---

# Verify Nanodot Android

Keep the approved Android-first goal and visual contract: transparent blue glass, bottom chat bubbles, automatic avoidance and user-guided Care. Use the existing build wrapper and device harness. This skill is repository-local and loaded from AGENTS.md; it is not a globally installed plugin.

## Resume and select

Read the current task ledger, source diff, last build/installation receipts and relevant entry in [features](references/features.md). Distinguish evidence inherited for unchanged code from checks needed for the current candidate. Use [the verification handbook](../../docs/android-verification.md) for commands and the [run record](references/run-record.md) for evidence fields.

Name the feature, actual entry point and observable success condition before driving. A behavior fix must return to the same repro; a redesign is checked against the approved behavior/design, not pixel equality with the old UI. Native fixture and real user/provider results remain distinct.

## One device owner

The coordinator alone runs Gradle, installs or drives the guest. Read-only feature reviewers may work in parallel. Resolve `NANODOT_QA_HOME` from the existing local handoff, not an arbitrary downloaded harness. Its `install-candidate.py`, `run-native-case.py`, QA mode guard and live scrcpy bridge must already exist and be inspectable. The current harness owns only `emulator-5586` / `Nanodot_V2_QA`; do not use the old guest, create another AVD or clear app data to make a check pass.

1. Run the documented build doctor. Select the explicit paired receipt emitted by the unified build wrapper. Check local APK integrity; preserve source dirty/fingerprint and any native-cache provenance limitation.
2. Before installing/driving, verify the guest identity, installed APK hashes and bridge health directly. Historical JSON and an open browser tab alone do not prove readiness. On any surprising failure, repeat the relevant doctor or restore a known scene before another drive.
3. Use the existing guarded installer and explicit-method native runner. Automated mode locks sidebar input while preserving real video. After a timeout, the runner keeps automated mode; the coordinator must stop the known test/app work and verify cleanup before restoring manual input. Never kill processes by broad name.
4. Drive the selected user route as well as the relevant fixture. Preserve before/action/after evidence, input/IME/host conditions and side effects. View new captures. Test-only setup or a mock provider must be labeled; it is not proof of real model, microphone or third-party app support.
5. Check the evidence and restore settings changed by the check. Keep evidence after cleanup. Leave the user's live session visible when requested; stop only disposable work you started. Do not delete receipts/screenshots/logs.

## Bounded repair loop

One attempt means a named hypothesis, the smallest justified change, one focused check and recorded result. Do not rerun the full matrix without a new change, failure or unresolved concern. For an unchanged failure, stop blind retries after the second attempt and inspect the premise/owner/state before choosing another hypothesis. This limits blind repetition, not the authorized task. Reproduce a cheap deterministic bug before fixing; label an unrelated compilation failure honestly.

Care product loops keep their existing controller, generation, Stop and deadlines. No retry count, automatic clicking, provider fallback or authorization is added by this skill. A review finding is triaged before changing code. Record any inability to reach the requested surface rather than weakening the acceptance condition.

## Report and maintain

Report `not_run`, `pass`, `fail` or `blocked` per feature and proof level, with exact candidate and evidence paths. Artifact integrity, JVM tests and instrumentation do not imply real-user/provider success. An installable APK report names ABI/signing and remaining device limits.

Update the feature map only for observed source drift; record product failures as failures. A new or changed harness recipe stays unverified until the coordinator runs it once on the actual target, retains evidence and checks cleanup. No automatic publication, merge, external messaging or login/gateway change follows from a pass.

Adapted from poteto's pstack, MIT © 2026 Lauren Tan. See [source lock](references/pstack-source-lock.json) and [license](references/PSTACK-LICENSE). Host-specific tools, model names and unbounded autonomous retries are intentionally not imported.
