# Android verification

Use this workflow for Nanodot Android acceptance and APK delivery. The repository-local `skills/verify-nanodot-android/SKILL.md` is loaded through AGENTS.md; it is not installed globally. It adapts selected [pstack](https://github.com/cursor/plugins/tree/fe53d9e9093dbb9ae298563b1a9d702dd6e2ca08/pstack) 0.15.21 principles under MIT © 2026 Lauren Tan. The source lock and license are under that skill's `references/` directory.

A passing build, fixture and real app journey answer different questions. Keep those results separate. The original goal remains Android-first, transparent blue glass, bottom bubble chat, automatic avoidance and user-guided Care; this workflow does not complete other platforms or reopen login/gateway deployment.

## Prepare and identify {#prepare}

Use the [unified build profiles](build.md). Run commands from the repository root with the established toolchain environment. `NANODOT_ENV_FILE` may point at the existing local environment; do not load provider secrets for build checks.

```sh
bash scripts/build-nanodot-apk.sh doctor
bash scripts/build-nanodot-apk.sh check
bash scripts/build-nanodot-apk.sh debug-x86_64 --with-test
```

`check` means the full JVM suite unless `NANODOT_TEST_SCOPE=nanomuse` is explicitly selected; report that narrower scope. `debug-x86_64` is the emulator candidate, while `debug --with-test` builds the arm64 pair. Neither x86 success nor a local native cache proves arm64 runtime behavior. Preserve receipt cache provenance.

Set `NANODOT_RECEIPT` to the exact `receipt.json` printed by the chosen build, not the newest file found by a glob. Set `NANODOT_QA_HOME` to the already-owned local harness directory from the current handoff. It contains `install-candidate.py`, `run-native-case.py`, the QA mode guard and the live bridge. These local helpers are not bundled in the APK or downloaded by this skill. Inspect them before reuse; if absent, record the harness prerequisite rather than silently constructing a second driver.

```sh
: "${NANODOT_RECEIPT:?select the explicit paired candidate receipt}"
: "${NANODOT_QA_HOME:?locate the existing owned QA harness}"
python3 scripts/android/check_verification_evidence.py receipt "$NANODOT_RECEIPT"
```

The checker reads local bytes only. Certificate fields must agree, but actual signature verification remains the build's `apksigner` evidence. It does not establish current installed state.

## Native doctor and sidebar {#doctor}

One coordinator owns Gradle and the device. The existing local harness is pinned to `emulator-5586` / `Nanodot_V2_QA`; never drive `emulator-5584` or a shared device by default. Before first drive and after a surprising failure, directly inspect identity and the installed APK hashes:

```sh
"$ANDROID_HOME/platform-tools/adb" -s emulator-5586 emu avd name
"$ANDROID_HOME/platform-tools/adb" -s emulator-5586 shell getprop ro.product.cpu.abi
"$ANDROID_HOME/platform-tools/adb" -s emulator-5586 shell pm path io.github.nanodot.app
"$ANDROID_HOME/platform-tools/adb" -s emulator-5586 shell pm path io.github.nanodot.app.test
```

Read `sha256sum` for the exact returned APK paths and compare with the receipt. Record API, locale, font scale, theme, rotation and animation settings. Inspect the current loopback bridge health and decoded video, not just a cached screenshot or open tab. Keep the private sidebar authentication URL out of logs and commits. Never clear app data, replace signing identity or alter another guest to repair a mismatch.

Use the guarded installer for a changed candidate:

```sh
python3 "$NANODOT_QA_HOME/install-candidate.py" "$NANODOT_RECEIPT"
python3 scripts/android/check_verification_evidence.py receipt "$NANODOT_RECEIPT" \
  --installed "$NANODOT_QA_HOME/installed-candidate.json"
```

The second command compares the saved installation record, not a fresh device query. Keep the direct observations and their time in the run record. A partial install must be repaired before testing the pair.

## Drive and capture {#drive}

Read `skills/verify-nanodot-android/references/features.md`. Select the changed feature's actual user route and explicit fixture method. Set `NANODOT_CASE` to a new lowercase label using letters, digits and hyphens; the runner refuses an existing directory.

```sh
: "${NANODOT_CASE:?choose a fresh evidence label}"
python3 "$NANODOT_QA_HOME/run-native-case.py" "$NANODOT_CASE" \
  'io.github.nanomuse.dot.chat.DotForegroundLayoutTest#transcriptAndFullTouchControlRemainVisibleInShortImeAndMovedUpFramesAtAllFontScales' \
  --expected 1
python3 scripts/android/check_verification_evidence.py native \
  "$NANODOT_QA_HOME/logs/$NANODOT_CASE"
```

This is one JUnit method with multiple layout scenarios, not proof of a complete user/provider journey. The runner stores `result.json`, `instrumentation.log` and `after.png`; inspect new captures. Its guard switches the existing sidebar to automated mode and locks manual input while retaining actual video. Do not interact manually during that mode. A native timeout is bounded at 180 seconds and leaves automated mode active: stop the exact known test/app work, inspect cleanup and only then restore manual mode. Killing the adb client alone does not stop instrumentation.

For the actual product pass, launch the installed app through its launcher, enable its accessibility service through Settings if needed, return to a harmless host and use the orb. Preserve before/action/after screenshots or video, UI state and relevant logs. Verify glass readability on dense/inverse host content, bubble order, draft/history persistence, menu/IME/Back order, touch targets, temporary upward avoidance and compact behavior. Cover 200% fonts and short/IME hosts when affected. Keep the chat visible. Freeze host content and animation phase before any pixel comparison; a redesign is not required to match the old UI pixel for pixel.

Real model and voice checks need their separate authorized configuration and request/result evidence from the app. A configured key, fixture reply, injected transcript or visual recording animation is not real-provider/microphone proof. If prerequisites are missing, record that proof level as `not_run` or `blocked` and continue independent checks.

## Evidence and repair loops {#evidence}

Use `skills/verify-nanodot-android/references/run-record.md`. Record status (`not_run`, `pass`, `fail`, `blocked`) separately from proof level (`source`, `artifact_integrity`, `unit`, `native_fixture`, `native_user_flow`, `real_provider`). Bind the receipt, source fingerprint, APK hashes, device conditions, route, actions, expected/observed result, artifacts and cleanup. The offline checker deliberately cannot bind old fixture output to today's installation or judge screenshot content.

Each repair iteration has a hypothesis, one justified change and a focused check. After two unchanged failures, inspect the premise and state before another patch. Do not weaken expected behavior, erase failed evidence or repeatedly run the full matrix without a reason. Reuse a cheap deterministic regression where available; an unrelated compile error is not a behavioral RED. Keep product bugs separate from harness drift. Cleanup restores only settings/resources the check changed and retains evidence; a requested live sidebar may remain open.

| Loop | Existing owner and limit | Evidence required |
|---|---|---|
| Build/install | Unified wrapper; frozen source fingerprint and paired receipt; coordinator serializes builds/install | Byte/signature/package/ABI evidence; a changed source during build rejects the candidate |
| Native fixture | Existing runner/QA mode guard; explicit methods, 180-second limit | JUnit result plus captured actual state; timeout requires device cleanup before another attempt |
| Care | `CareController`, `CarePlanner`, `ConfiguredCarePlanner`; one provider request, 5-second capture and 30-second planning deadlines; no automatic provider retry/fallback | Current snapshot and one checkable user step; a fresh observation verifies the outcome; `UserConfirmed` stays distinct from `Observable` |
| Chat/provider/voice | Existing canonical binding and cancellation owner | Stop revokes work; late output cannot restore a stopped task; real provider/audio is measured separately from held local fixtures |
| CI/review | Existing task/PR owner and scoped review | Inspect real artifacts and actionable changes; do not auto-merge, deploy or reopen frozen services |

Care's prompt reinforces this evidence sequence without adding tools, execution, schema fields or an unlimited loop. A proposed step, elapsed time or model assertion is not completion. Keep generation/revision fences and Stop in code; prompt text cannot replace them.

## Skill validation and delivery {#delivery}

The pure Python checker can be tested without Gradle, a device or a provider:

```sh
python3 -m unittest discover -s scripts/android -p test_verification_evidence.py -v
```

A new or changed verification recipe is a draft until the coordinator runs launch/doctor/one mapped feature/evidence/cleanup on the actual target and records the result. Do not repeat already-passing unrelated suites just to demonstrate the skill. For delivery, identify the final APK's ABI, signing and SHA-256 and retain limitations. A debug APK may be installable while hardware/real-provider checks remain incomplete. Nothing in pstack grants publication or external messaging permission.

## Earlier cobalt snapshot — 2026-10-13 {#cobalt-snapshot-2026-10-13}

The cobalt-message candidate was built as paired debug APKs for x86_64 and arm64-v8a. The x86_64 main/test bytes were verified after installation on the isolated Android emulator. Both builds share source fingerprint `1cc6e334e6d357b48a555bfcd22a5ebf8c5d190c9acca15d312f8386121d6d3d`; arm64 installation/runtime has not been checked on physical hardware. These are development artifacts, not a release qualification.

- Scoped JVM checks passed. The refreshed native message-layout test passed all18 normal/short-IME/moved-up and font/sender combinations, and the glass test passed its4 theme/font combinations after fixing2px clipping at200% font size. The real split-screen/IME fixture also passed.
- One real DeepSeek text exchange returned HTTP200 and six SSE deltas, with44 input and6 output tokens. The main chat and foreground cobalt bubble displayed the same canonical reply, `Nanodot 连接正常。`. The panel was opened with a real orb tap; sending used the existing `chat.uiPrompt` entry into the shared production VM/provider, so this does not establish a physical Send-button tap.
- The provider exchange used a one-use localhost test relay that read the existing private credential only in host memory. It forwarded the real SSE unchanged, replaced system context with public test text, limited output to128 tokens and disabled tools/thinking. Default-context/tool execution, microphone input and screen interpretation are outside this proof. No credential is in the APK or repository.
- In this snapshot, normal Care controls passed, but the 200% font Back case, compact-menu Expand admission and static-outline top-edge case failed. The static outline was visibly clipped at the top; fixture admission/scroll assumptions also needed repair. Restoring the service after gallery tests exposed a separate `BadTokenException` during overlay attachment. These historical failures remain recorded; the focused follow-up below updates their status.

## Care follow-up (r13) — 2026-10-13 {#care-r13-2026-10-13}

These native fixture results belong to the installed x86_64 development pair with source fingerprint `162a7d2406d0941d8cf54d57d182fb0963a861c6f0187bbb9871a04b24a5cee4`:

| APK | SHA-256 |
|---|---|
| Main | `9fb4b7f6155aef6b7e386703db24615138777c24df72eff69a7bd71ca4dba241` |
| Test | `09e588a71d34b28b0fc445a61a0ee04c89957029a24ebd3c51c87f7b13b8f884` |

The earlier layout, glass, split-screen and real DeepSeek evidence remains bound to `1cc6e334…`; it is not a rerun on this pair.

- **Static outline: pass** (`reset-r13-static-bound`). Removing the focus window's second system-inset adjustment fixed the top-edge clipping. All four outline edges rendered and the target region stayed identical across two captures 300ms apart. The fixture checks the attached View's screen bounds and screenshot pixels; the non-touchable, non-focusable visual layer need not appear in the accessibility window list.
- **200% font controls and single Back: pass** (`reset-r13-font2-diagnostics`). The fixture retained full 48dp control admission, selected Copy within the menu, observed the host touch, and sent one Back. Back was accepted before a later observation reached `Paused`, removed the card and cleared the outline. This supports a fixture waiting issue, without proving the sole cause of the earlier failure. Production Back handling was unchanged.
- **Compact controls: guest completion, harness incomplete** (`reset-r13-compact`). After short scrolls based on the current viewport, the guest TestRunner reported one test, zero failures and zero ignored tests. The host runner exited 143 without persisting its normal stdout/result, so this is not an overall harness pass.
- **Overlay attachment remains unresolved.** One serialized service rebind and subsequent UiAutomation observation showed no new crash. This does not fix the earlier `BadTokenException` or exclude a reattachment race.

Keep the original Android-first delivery goal open. The harness limitation, unresolved attachment defect and remaining device/provider coverage prevent whole-assistant acceptance.
