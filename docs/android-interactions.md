# Android foreground interactions

Nanodot uses the existing Android Chat and Care owners. The crystal, its action menu, the bottom conversation and the target cue are presentations of those owners, not separate agent loops. Login and gateway configuration are unchanged by this redesign.

## Crystal and conversation

Single tap opens chat; double tap observes the current screen; three taps enters user-guided Care. Long press reveals four glass action bubbles: Chat, Screen, Care and Voice. The same gesture reducer arbitrates movement, multiple taps and a held press. Releasing the held press does not select a child action. Each child has a 48dp target and a localized description. The menu is bounded, opens toward available space, and avoids the current target and conversation. If no valid arrangement fits, the existing entry points remain available.

The menu keeps the host/input focus. Selection, outside input, a new orb gesture, Stop, locking or disconnecting revoke it; panel Back closes transient menus before its existing keyboard/close behavior. Actual outside touch delivery and window geometry are native acceptance requirements, not conclusions from transparent pixels.

Voice uses the existing canonical chat binding, permission flow and recording owner. It finishes/cancels an active recording according to its current state; a new transcription stays in the editable draft until the user sends it. Opening a menu does not capture the screen, start recording or call a provider.

The foreground card has a transparent cobalt body, a fading inward bevel and short corner highlights. The host remains visible through empty history; local dark-blue surfaces support text and controls. These are drawn optical cues, not cross-app refraction. Waiting stays a passive dot and text, beside separate Stop, collapse and menu actions. The rounded draft field keeps its existing 48dp target and short-window editing behavior.

When reading the newest message, the transcript follows streaming text, its final reply and a shrinking viewport. Scrolling into older messages keeps that reading position while new text arrives; returning to the end resumes following. The existing bounded transcript and keyboard/target-aware window placement remain in use.

The default main-chat avatar uses the same retained blue crystal atlas as the orb. Existing user-selected and Hidden avatars remain respected. Generator-owned copy uses Dot and crystal examples across the 17 supported locales. Genuine upstream attribution, internal compatibility names and nanoMuse Cloud service identity remain explicit.

## Care instruction presentation

Care draws a blue outline around the currently validated target. There is no target-to-chat leader. A separate touch-through instruction appears, reveals whole Unicode graphemes, stays readable briefly and disappears. The complete instruction appears immediately in bottom chat and remains there. Text placement avoids the target and the whole chat card inside the observed host/keyboard viewport; when it cannot fit, bottom chat carries the instruction alone.

Cue expiry never completes a step. Its identity includes the session/step and current scene; stale targets, Stop and teardown cancel the decoration. Repeat replays only the current visual instruction, without a model, speech-provider or fresh-screen request. Reduced motion displays the complete cue text without typing or fading, then removes the cue after a finite hold; the full bottom message stays readable. Native bidi layout and measured text govern placement; glyphs are not positioned by assumed character width.

## Scenario contract

The planner describes one user action using current eligible nodes and one supported, observable expectation. Every next step requires fresh evidence. These rules are design and prompt guidance; they do not claim live-provider or third-party-app acceptance.

| Visible interaction | One-step guidance | Evidence and recovery |
|---|---|---|
| Search | Distinguish editing, suggestions, explicit submission and immediate results. | Verify query/result state; empty results preserve the query and invite refinement. Do not invent a Search button. |
| Commerce | Identify the visible item, variant and quantity separately. | Reobserve changed price, stock or delivery state; the user confirms purchase/payment. No fixed app coordinates. |
| Form | Focus one labelled field; preserve its draft and validation text. | Check the visible value/error and later saved state. Do not equate masked text or a Save tap with successful persistence. |
| Reading | Name the current heading or visible passage. | Reobserve after scrolling; retire the old position. Avoid the current target and keep the transcript movable. |
| Media | Ask the user to reveal hidden playback controls before targeting them. | Require visible state/label change; buffering, hidden controls and picture-in-picture need a new observation. |
| Settings | Say the desired setting state, not merely “tap here”. | Require a visible resulting state; disabled controls or unsupported evidence lead to clarification. |
| Chat | Separate draft, sent message and pending response. | Preserve the current input owner; animation alone does not establish delivery. |

Instructions use the user's language while preserving the exact target label. The strict existing action/expectation schema, validator, 30-second provider deadline and bounded request budget are unchanged. If the schema cannot prove an outcome, the planner must clarify instead of inventing a successful step. Full checked-state/media verification remains dependent on what the current snapshot and validator can actually observe.

## Reuse and provenance

Research on 2026-10-13 examined official source and license files. The implementation adapts interaction rules to the frozen Compose toolchain; it does not import entire applications or their account services.

- [Jetchat input](https://github.com/android/compose-samples/blob/5a390e4a9d3c4e5b30e942041bde93582c4d9492/Jetchat/app/src/main/java/com/example/compose/jetchat/conversation/UserInput.kt): draft/input-panel/IME ownership (Apache-2.0).
- [Telegram popup](https://github.com/DrKLO/Telegram/blob/f2908b14133bbffbf7ab04f641ecb5bfaf533242/TMessagesProj/src/main/java/org/telegram/ui/ActionBar/ActionBarPopupWindow.java): interrupted animation and dismissal (GPL-2.0).
- [Wikipedia search](https://github.com/wikimedia/apps-android-wikipedia/blob/1cc9ac0dfdaf8f353a83b39d426f3d1a15a4f199/app/src/main/java/org/wikipedia/search/SearchFragment.kt): distinct query and result states (Apache-2.0).
- [Fossify contact editor](https://github.com/FossifyOrg/Contacts/blob/ad5831b21546204f412c9f77e413186c5c078166/app/src/main/kotlin/org/fossify/contacts/activities/EditContactActivity.kt): draft, validation and save/discard decisions (GPL-3.0).
- [VLC controls](https://github.com/videolan/vlc-android/blob/91c78014517bfbc769df4634d06cbd40ebce77ec/application/vlc-android/src/org/videolan/vlc/gui/video/VideoPlayerOverlayDelegate.kt): keep essential controls available during accessibility interaction (GPL-2.0).
- [Jetsnack search/cart](https://github.com/android/compose-samples/tree/5a390e4a9d3c4e5b30e942041bde93582c4d9492/Jetsnack): deterministic commerce states (Apache-2.0); its checkout placeholder is not a payment engine.

Run [Android verification](android-verification.md) for candidate-bound checks. JVM policy tests, native fixtures, actual app navigation and real-provider journeys are separate proof levels. The installable build recipe remains [Build](build.md).
