# Android workflow and foreground UI reset implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. User approved direct implementation on 2026-10-12; no repeated approval gate.

**Goal:** Deliver a reproducible Android build entry, a clear glass foreground conversation, a real sidebar preview and installable APK while retaining the original Nanodot Ultra roadmap.

**Architecture:** Extend the existing build wrapper and native Compose windows. Reuse current brand generator/crystal atlas and the latest DeepSeek handoff semantics; preserve canonical Chat/Care owners, cancellation, voice and target observation. Do not copy the changing desktop execution implementation.

**Tech Stack:** Existing Kotlin2.1/Compose/Material3, Gradle8.11.1/AGP8.7.3/JDK21, Python/shell build tooling.

**Spec:** /home/jlu/Documents/Codex/2026-10-10/now-make-a-plan-for-the-2/outputs/Nanodot-Workflow-Reset-2026-10-12/README.md (user approved).

## Global Constraints
- Android first; current worktree reused; no login/gateway changes or model calls for UI validation.
- Keep transparent glass, bottom chat bubbles, automatic avoidance, and compact conversation still visible.
- One foreground state header, directly reachable Stop during active work; contextual main action and overflow.
- At least48dp touch targets, readable normal/200% font, IME/rotation/dark/light support.
- Same canonical binding/history/draft across movement; Android Care remains user-guided, no automatic clicks.
- Reuse brand/token generator and existing imagegen atlas. Do not import obsolete LXGW handwriting as new body type. Use coherent native sans typography first; draft desktop font choices are not fabricated installed assets.
- Generated source only through generator. Newstrings all17Androidlocales; upstreameditsannotated; docsEN/ZHandCHANGELOG; DCOcommits.
- OnlyrootownsGradle/AVDinput/install; emulator5586only;5584untouched. Main/testartifact provenance anddebug/release distinction explicit.

## Review Focus
- Missing/misversionedtoolchains faildoctor before expensivebuild; filenamesincludeprofile/sourceidentity.
- Drift betweenbuiltmainandtestAPK cannot masquerade as singlecandidate.
- Smallhost/font200/IME preservescurrentmessageandprimarycontrol withoutnestedbuttonwall.
- Caretarget overlapsbottompanel: move/compact/expandretainprotection andsameowner.
- Screenshot/nativefixture evidence versusrealproductpreview andmanualinput mustremainseparate.

### Task 1: Unified Android workflow
**Files:** scripts/build-nanodot-apk.sh; scripts/android/env.sh; new scripts/android/build_profile.py; tests/test_android_build_profile.py; docs/build.md and docs/zh/build.md; AGENTS.md commandlink ifneeded.
**Interfaces:** preserve debug|release entry forCI; add doctor and explicit debug-x86_64/check profiles, pairedtestoption; wrapperusesGradleWrapper. Candidate receiptscontaincommittree/dirtyflag/profile/ABI/diagnosticflags/actualpackageSDK/signature/main+testhash. Doctornorepair/download/install.
- [x] AddmeaningfulREDtests fordoctor/version/profile/artifactidentity andparsefailures.
- [x] Implementboundedextension preservingexistingCIartifactpaths whereconsumed; uniqueimmutablecandidatefolder pluscompatibilityoutputs.
- [x] RunfocusedPython/shellchecks; rootrunactualdoctor againstexistingtasktoolchain. IndependentreviewandDCOcommit.

### Task 2: Foreground presentation
**Files:** dot/chat/{DotForegroundChat,DotChatPanel}.kt; dot/overlay/{DotOverlayController,DotForegroundGeometry}.kt; dot/care/{CareCard,CareOverlayWindow}.kt; dot/ui/{DotForegroundStatus,DotGlassSurface}.kt; dot/voice/DotVoiceControls.kt ifneeded; corresponding tests/resources; docs/android.md and docs/zh/android.md.
**Interfaces:** preserveexistingbusinesscallbacks/sessionowners; presentoneheader; headeracceptsstatusandtrailingactions, controlsplaceonecontextualprimary; secondaryactionsinaccessiblemenu. Windowdesiredheightadaptscontent/host; targetboundremainsconsideredwhenexpanded. Reusecurrenttokens/atlas; native sans textcoherent.
- [ ] Addbehavioraltestsforactionpolicy/placementregressionsandnativevisualfixturewithsyntheticmodels(noAPI).
- [ ] Replace duplicatechrome/buttonwall, lighterglass, compactcomposerandCarecontrols. Preservevoicecapabilitiesviaexistingcallbacks.
- [ ] Testsfornormal/200%smallhost/IME andsamebinding; rootserializedcompilethenrealpreview. IndependentreviewandDCOcommit.

### Task 3: Verify and deliver
**Files:** existingnativeQAfixtures, docs/changelog, outputartifactreceipts.
- [ ] Runfreshfocusedunits+main/testbuildusingnewentrywithexactfrozencandidate.
- [ ] InstallverifiedmatchingAPKsfresh5586; runnativecriticalcaseswithoutsimultaneousmanualinput; captureproductChineseandEnglish/dark/light/largefont/IME/avoidance screenshots, reviewactualframes.
- [ ] Leave a real normalproductscene in live8768sidebar andreturnmanualcontrol; notfixture-onlypreview.
- [ ] Buildarm64candidatewithsameentry/CI, verifyactualsignature/package/ABI/hash; nofakefullnativearm64claimfromx86UI.
- [ ] Commit/pushauthorizedexistingPRafterreview, outputAPK+receiptandoriginalgoalcoverage. Originalgoal remainsincompleteuntilvoice/lasso/Careandmultiplatformpendingitemsactuallyverified.

## 2026-10-13 user-approved interaction addendum

This addendum follows the user's explicit implementation request and supersedes the old “connected instruction bubble” in the external reset README. The original Ultra scope, Android-first delivery, login/gateway freeze, canonical owners, bottom glass conversation, automatic avoidance and real-sidebar evidence remain binding.

### Task 4: Reference motion and evidence workflow
- Reuse the three retained interaction reference images and existing imagegen crystal atlas; implement restrained blue crystal afterimages on the existing bounded trail clock.
- Adapt selected pinned MIT pstack verification practices into this repository's AGENTS entry, verification skill, feature/run evidence templates and read-only checker. Do not import Cursor tools, model selection or unbounded product retries.
- Independently review source and run the same feature on the actual owned candidate. Reference concepts, native fixtures and real product/provider evidence remain separate.

### Task 5: Long-press crystal actions and home identity
- Emit one long-press menu event from the existing gesture reducer; retain single/double/triple taps, drag and cancellation arbitration.
- Show four glass child orb actions: Chat, Screen, Care and Voice. Reuse existing admission/voice permission/lease flows; voice only writes a draft. No fake action or new model request.
- Use bounded owned windows, at least 48dp targets, finite smooth motion, reduced-motion support and safe alternatives near edges/IME/target/chat. Dismiss on selection, outside input, new orb gesture, Stop/lock/disconnect and existing panel Back where applicable.
- Generate default main-chat/avatar mood resources from the same blue crystal atlas. Preserve user-selected/hidden avatars, internal identifiers and genuine upstream attribution/service identity. Repair exact display-brand misses via the generator and retain all 17 locales.

### Task 6: Target outline and ephemeral instructions
- Remove target-to-chat leader lines. Keep a target-border trace and add touch-through foreground instruction text: appear, reveal by Unicode grapheme, hold, disappear. The full instruction remains in the bottom chat for reading and accessibility.
- Place text away from the target and chat within the current host/IME viewport. If no safe region exists, retain the full chat instruction without obstructing the target. Disappearance never means task completion.
- Bind all animation to current session/scene/step. Stop, stale scene, new step and teardown cancel it immediately. Reduced motion shows complete text without stagger and retains usable time to read. Repeat replays the visual instruction without a model request.
- Use the researched mainstream/FOSS scenario catalogue (search, commerce, forms, reading, media, settings and chat) to require fresh observable evidence before each next step; never hard-code third-party coordinates or infer completion from an animation.

### Shared interface and ownership checks

| Tasks | Shared interface | Ruling |
|---|---|---|
| 2, 5 | DotOverlayController / canonical chat admission | Menu implementation extends current presentation without replacing Chat owner; coordinator alone runs builds/device. |
| 2, 6 | CareOverlayWindow / placement / conversation | Cue implementer owns Care presentation; existing transcript/Stop/placement invariants remain. |
| 5, 6 | Back dismissal | Menu exposes one dismissal method; Care author integrates it after interface handoff. |
| 4, 5, 6 | Motion/accessibility/owned capture windows | Finite motion, reduced motion, 48dp and capture exclusion apply to every new surface. |
| 5, 5 | Brand vs menu locale resources | Brand repair owns only the three existing keys; menu author adds new keys without regenerating unrelated files. |
| 3, 4–6 | Artifact and evidence | Rebuild the main/test pair after source freezes; old candidate native results are inherited evidence, not proof of new code. |

Acceptance adds native long-press/tap/drag arbitration, actual child actions and dismiss lifecycle, no rendered leader, Unicode/RTL cue timing and placement, Chinese/English main avatar and live overlay, and new exact paired APK receipts. The existing broader native matrix is reused only where source and candidate limitations are explicit.


## 2026-10-13 conversation readability follow-up

- The user narrowed cobalt-blue transparency to the text-bearing conversation bubbles. Other glass chrome and the crystal/menu palette keep their approved appearance.
- Increase message padding, inter-message spacing and line height; reserve bounded history space for actually displayed empty/error/preview states as well as persisted replies. Preserve the 180dp history budget cap, automatic avoidance, compact controls and 48dp actions.
- The user explicitly authorized real DeepSeek calls from the existing private `.env` for Android acceptance. The earlier no-provider limitation is lifted for this verification route; login/gateway deployment remains frozen. Keep credentials out of source, screenshots, logs and commits. Use the canonical Android chat path and existing bounded request accounting; do not call a mock or direct CLI response an app-flow pass.
