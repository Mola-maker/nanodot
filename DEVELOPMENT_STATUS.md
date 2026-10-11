# Nanodot development snapshot

Snapshot: 2026-10-11. Android remains the delivery priority. This records current source branches and observed verification; it is not a release or full acceptance report.

## Source branches

| Area | Branch | Frozen source |
|---|---|---|
| Android integration | [nanodot/android-dot-care](https://github.com/Mola-maker/nanodot/tree/nanodot/android-dot-care) | `bbfdc469` |
| Voice ownership, scoped review approved | [nanodot/android-dot-voice](https://github.com/Mola-maker/nanodot/tree/nanodot/android-dot-voice) | `94cdbb0c` |
| Care native test harness, source review approved | [nanodot/care-native-acceptance](https://github.com/Mola-maker/nanodot/tree/nanodot/care-native-acceptance) | `c0dea86b` |
| Desktop UI candidate | [nanodot/ui-desktop](https://github.com/Mola-maker/nanodot/tree/nanodot/ui-desktop) | `88c542b2` |
| iOS UI candidate | [nanodot/ios-native](https://github.com/Mola-maker/nanodot/tree/nanodot/ios-native) | `805923c3` |
| Design system | [nanodot/ui-system](https://github.com/Mola-maker/nanodot/tree/nanodot/ui-system) | `128cfb2f` |
| Gateway and website source | [nanodot/workers-ai-site](https://github.com/Mola-maker/nanodot/tree/nanodot/workers-ai-site) | `06812a7e` |
| Care planner | [nanodot/care-planner](https://github.com/Mola-maker/nanodot/tree/nanodot/care-planner) | `0fe9df1e` |
| Care controller | [nanodot/care-controller](https://github.com/Mola-maker/nanodot/tree/nanodot/care-controller) | `cd252169` |
| Care focus UI | [nanodot/care-focus-ui](https://github.com/Mola-maker/nanodot/tree/nanodot/care-focus-ui) | `af692ef1` |
| Android API proof tooling | [nanodot/android-api-proof](https://github.com/Mola-maker/nanodot/tree/nanodot/android-api-proof) | `a921f939` |
| Android logging safety | [nanodot/android-log-safety](https://github.com/Mola-maker/nanodot/tree/nanodot/android-log-safety) | `be882776` |
| Earlier Android interaction source | [nanodot/android-dot-care-work](https://github.com/Mola-maker/nanodot/tree/nanodot/android-dot-care-work) | `44fb0c3b` |
| Historical floating-chat native test candidate | [nanodot/android-dot-ui-acceptance](https://github.com/Mola-maker/nanodot/tree/nanodot/android-dot-ui-acceptance) | `428c2574` |

The default branch still contains the earlier P0 snapshot, `7ed8ce6e`. Publishing these development branches preserves review boundaries; it does not merge their contents into the default branch.

## Android observations

- The installed integration APK is based on `bbfdc469`, SHA256 `f5fc54e49dc67adb0ae67ece6a80a9da8755b09d7a5faff7804f6311048ed825`. It builds and installs in the existing API 35 virtual phone. Earlier product regressions comprise 45 cases and a separate targeted 23-case run.
- Voice fix1 at `94cdbb0c` passed complete, unfiltered main Kotlin compilation and 51 focused JVM tests, with zero failures, errors or skips. Independent scoped re-review approved the three fixes for pipe retirement, automatic endpoint finishing, and locale restart ownership. Eight distinct failing behavioral counterexamples were preserved before the fixes. This candidate has not been merged or installed.
- An earlier full JVM run had 1,753 tests and 42 failures. Its logs/XML remain retained. No base full-suite comparison established that all 42 were baseline failures; no whole-suite PASS is claimed.
- The Care native harness compiled, was independently reviewed, and its matching debug-signed test APK was installed. The actual host smoke failed at the window-focus observation gate before the three test cases or visual acceptance. A subsequent single recovery passed the limited state-restoration checks. The original FAIL remains a FAIL.
- Independent diagnosis found that the observed API 35 `dumpsys window windows` output lacked the required focus fields; `dumpsys window displays` supplied them. The original stop check also observed a real exiting window. A separate controller candidate is being prepared to correct the observers and reuse the installed APKs; its new execution is pending review. Local in-progress tooling is not included in this source snapshot.
- Earlier native floating-chat attempts failed, including a test-host Kotlin runtime failure. Full send/stream/same-conversation completion remains unaccepted. Source approval and recovery do not establish UI success.
- Actual Android provider streaming and Care planner requests previously returned HTTP 200. Genuine microphone/ASR/TTS acceptance, voice permission/foreground-service/UI integration, circle selection, full care guidance and the remaining UI/device matrix are still pending.

## Other delivery gates

Desktop and iOS candidates remain separate branches. Local desktop UI checks and iOS static checks are distinct from native platform acceptance. Gateway/website source exists, but deployment at `molamaker.si`, Cloudflare Workers AI integration and free-quota enforcement are not complete. Earlier P0 GitHub Actions builds do not verify these newer candidates.

Eleven upstream issues were submitted and read back: #332–340, #347, and [#356: Provider cancellation and VAD capture](https://github.com/nano-muse/nanoMuse/issues/356). Controlled tests in #356 do not establish native microphone behavior.

Only committed source and this public status are synchronized. Local `.env`, API/Cloudflare credentials, signing keys, private emulator baselines and clipboard payloads remain local.
