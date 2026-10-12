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
