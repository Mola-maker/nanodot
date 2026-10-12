# Nanodot design system

Nanodot uses each platform's native controls and accessibility behavior. Dot's bundled offline avatar is the default visual. System fonts, colors and controls render immediately; no font download, video, remote avatar or network response is required. Existing custom avatars and saved appearance preferences remain authoritative.

## Token sources {#token-sources}

`config/brand.nanodot.json` is the only color authority. `config/ui.nanodot.json` owns dimensions and durations. Run `python scripts/gen-ui-tokens.py`; `--check` exits unsuccessfully for stale output without writing files. Both inputs, schema, integer dimensions and color contrast are validated before output changes. Invalid colors, negative spacing and insufficient contrast leave every existing output unchanged. Changed files are staged, then individually replaced atomically; unchanged files keep their timestamps. `scripts/rebrand.py` shares the Android color renderer, so a branding rerun preserves the same Material mapping.

| Token | Value | Native use |
| --- | --- | --- |
| Space | 4, 8, 12, 16, 24, 32, 48 | dp on Android, points on iOS, CSS px on pointer clients |
| Body / care | 16 / 18 | sp on Android; system text with Dynamic Type on iOS; rem on Web |
| Touch target | 48 | minimum Android dp; iOS retains its native accessible minimum; pointer buttons remain generous |
| Page / press / focus | 180 / 100 / 200 ms | duration, never a requirement to animate |

Kotlin exposes `DotTokens.Space.S4` through `S48`, `Type.Body/Care`, `Motion.Page/Press/Focus/Breath/TrailLag/TrailFade/Snap` and `TouchTarget`. Swift exposes `NanodotTokens.Space`, `Typography`, `Motion` (seconds), `touchTarget` and `Colors.Light/Dark`. Swift callers use scaled system text, not fixed-size text that defeats Dynamic Type. Desktop exports `dotTokens` from `harness/dsh-nanomuse/src/client/dot-tokens.ts`. Web and showcase CSS expose `--dot-background`, `--dot-surface`, `--dot-text`, `--dot-primary`, `--dot-on-primary`, `--dot-care`, `--dot-outline`, `--dot-space-*`, `--dot-type-*`, `--dot-touch-target` and `--dot-motion-*`. CSS follows system dark mode by default; `data-dot-theme="light|dark"` selects the saved override. These generated exports are consumed by later platform shell/component tasks.

The desktop stylesheet consumes the shared light/dark tokens for its built-in blue palette. A branding rerun refreshes that token export without rewriting the TypeScript template or the saved profile accent. Secondary and muted text use the text role; outline remains a border or disabled-state role. Existing Chat, Tasks, Devices and Settings navigation, rem sizing and reduced-motion behavior remain in place.

## Color roles {#color-roles}

`DotColors.lightScheme/darkScheme` supplies all color roles supported by the pinned Material 3 library to Android's existing `MinisTheme`; the saved theme selection still decides which scheme is active. Never use a light on-primary foreground on a dark primary button. Text on surface and on-primary text both meet a 4.5:1 contrast ratio in each approved palette. Generated roles also check tertiary, secondary and container foreground pairs.

| Material roles | Brand token |
| --- | --- |
| primary, secondary, error, surfaceTint | primary |
| onPrimary, onSecondary, onTertiary, onError | onPrimary |
| tertiary | care |
| background, primary/secondary/tertiary/error containers, surfaceVariant, surfaceDim, surfaceContainerLowest | background |
| onBackground, onSurface, onSurfaceVariant, every on-container role, scrim | text |
| surface, surfaceBright, other surfaceContainer roles | surface |
| outline, outlineVariant | outline |
| inverseSurface / inverseOnSurface / inversePrimary | surface / text / primary of the opposite palette |

Error intentionally reuses the approved high-contrast primary pair; no additional hue is introduced. An error always has localized text and an error icon or state announcement. Success likewise has text and a check icon. Color alone never distinguishes a result. Material supplies disabled opacity, ripples and focus behavior; do not recolor these with arbitrary constants.

## State contract {#state-contract}

All families below apply this contract. Component-specific exceptions are explicit; a dialog or navigation destination need not invent a loading spinner when no operation is pending. Product strings (including error, retry and success announcements) come from platform localization resources in every existing locale. The rendering fixture's English labels are test data, never product copy.

| State | Required behavior |
| --- | --- |
| Enabled | Native role, accessible name, actionable target and keyboard operation |
| Disabled | Native disabled semantics; no click or value change; keep label and reason available |
| Pressed | Native ripple/highlight; do not move the target; 100 ms if a custom transition is necessary |
| Focused | Visible focus cue, logical order, native keyboard behavior; optional 200 ms transition |
| Loading | Visible localized progress text and native progress semantics; prevent duplicate submissions; stop/cancel stays reachable |
| Success | Localized result text plus check icon or state announcement; retain useful focus |
| Error | Inline or contextual localized reason plus icon/announcement; keep entered data and avoid moving focus unexpectedly |
| Empty | Explain the empty state with localized text and a relevant action; no dead placeholder control |
| Retry | Named native action; preserve data, retry only on request and reflect loading/result states |

## Native components {#native-components}

| Family | Native implementation and state behavior |
| --- | --- |
| Primary / secondary / icon button | Android M3 `Button` / `OutlinedButton` / `IconButton`; native iOS and desktop equivalents. Enabled, disabled and pressed use native semantics. Loading blocks duplicate activation, keeping stop available. Success/error use adjacent status; empty means omit an irrelevant action or explain its unavailability. Retry is a separately named button. Icon buttons have a localized accessible name; decorative icons have none. Minimum Android target is 48 dp. |
| Text input | M3 `OutlinedTextField` or native text field. Enabled/editable, disabled/read-only and focused states stay distinct. Loading may retain editing unless an operation requires locking it. Success has confirmation; error uses label, supporting text and icon; empty retains label/placeholder; retry preserves the value. Native IME actions, selection, password masking and autofill remain intact. |
| Dropdown | M3 exposed dropdown or native picker/menu. Enabled/pressed opens the menu; disabled cannot open it. Selection is announced. Loading, success or error describes option-fetch status; empty explains why no options exist; retry reloads only on request. Escape/back dismisses and returns focus to the trigger. |
| Switch / checkbox | M3 `Switch` / `Checkbox` or native toggle. Native checked, unchecked, mixed where applicable and disabled semantics; label toggles the value. Pressed feedback is native. Loading reflects pending save without accepting duplicate toggles; success/error text names the saved/failed value; retry retains the chosen value. Empty is only applicable to the enclosing settings group, which shows an explanation. |
| Card / list row | M3 `Card` / `ListItem` or native grouped/list containers. Only actionable rows are focusable/clickable; disabled and pressed apply to their action. Loading, success/error, empty and retry belong to the row's content. A plain informational card never pretends to be a button. Reading order follows visible content and every secondary action has its own name. |
| Dialog / sheet | M3 `AlertDialog` / `ModalBottomSheet` or native modal. Enabled/disabled/pressed apply to contained controls. Loading retains cancel/stop; success can dismiss with an announcement; error preserves content and offers retry; empty explains the absence of content. Modal focus is contained, dismissal returns to the trigger, back/escape behaves natively, and destructive actions are explicitly named. |
| Navigation | M3 navigation bar/rail or native tabs/sidebar. Every destination retains a visible localized text label at 200% text; fit or wrap the layout without hiding labels. Selected destination is announced; disabled destinations explain availability; pressed/focus stay native. Loading, success/error, empty and retry render inside the destination, never disable all navigation. Back and the current conversation's send/stop controls remain reachable. |

## Layout and motion {#layout-and-motion}

Android uses compact below 600 dp, medium from 600 through 839 dp, and expanded at 840 dp and above. Compact uses bottom navigation and one column; wider layouts use a rail and may show sessions beside chat. Actual usable space, large text and the keyboard can collapse a pane. Chat, Tasks, Devices and Settings retain their meanings; Tasks retains Feed, Ideas, Goals and its Library entry. Existing models, skills, connectors, memory, files, backups and terminal remain reachable through Settings or their existing contextual entries.

Honor reduced motion on every platform. Android duration scale zero produces static states; iOS reads Reduce Motion; CSS sets generated durations to zero under `prefers-reduced-motion`. Custom animations must use these tokens and system settings. Avoid flashing loops and announcements or focus changes on animation frames. Use native nonanimated focus indication when motion is disabled.

## Verification {#verification}

Run `python -m pytest tests/test_ui_tokens.py -q` and `python scripts/gen-ui-tokens.py --check`. The debug-only `DotTokenGalleryFixtureActivity` and instrumentation `DotTokenGalleryRenderTest` render M3 controls with the real theme, disabled controls, error/supporting text, empty/retry states, dialog, sheet and navigation at 100%, 150% and 200% text in both palettes. Capture and inspect the actual device screenshots; a passing capture test is evidence of rendering, not evidence of successful screen-reader behavior or native sandbox operation. The fixture has no production route and is absent from release builds; the reusable production gallery belongs to the subsequent component task.

The platform acceptance matrix also checks landscape/split windows, phone/tablet, keyboard/IME, screen reader, saved theme selection, reduced motion and reachable send/stop/back actions. Android remains the first implementation; iOS native verification requires its simulator/device toolchain. [Build instructions](build.md) describe the separate platform checks.

## Android page components {#android-page-components}

`DotPageState` describes real `Loading`, `Ready`, `Empty(message)` and `Error(message, retryable)` states. `DotStatusSurface` adds a readable message, a glyph, polite announcements and an existing-handler Retry. `DotOperationStatus` offers Cancel only when the operation owns a cancellation handle. `DotComponentGallery` is a reusable native specimen gallery with enabled, disabled, focused/pressed, loading, ready, empty, error, retry, dialog and sheet controls; its local choices perform no provider work.

The chat composer consumes the mounted session's existing VM and Send/Stop callbacks. At large text with a short IME viewport it collapses header chrome and keeps the unchanged draft in a scrolling single-line composer. Native keyboard visibility, editable bounds and pointer Stop are verified separately from navigation after dismissing the keyboard.

Cloud form and account refresh jobs, and the Network probe, cancel their actual OkHttp call. Cancellation preserves entered fields and produces no artificial error. The shared Cloud request builder and response parser preserve origins, bearer credentials and HTTP error details; blocking `profile`/`putProfile` calls used by ProfileSync retain their synchronous boundary. Cloud refresh may retain cached account information on the existing offline fallback.

Page states follow their owners. Library and profile activity load real local data. Goals, Ideas, Models, Hands and Computers often render existing synchronous stores, readiness or configuration immediately; those boundaries do not manufacture loading or failure. Feed shows its real generating flag and retains the dedicated conversation route. Its manual run shares a session queue with scheduled work, so the page does not advertise unsafe per-run cancellation. Avatar cancellation retains completed candidates, saved appearance and retry context, and ignores cancelled jobs' late callbacks.

Shared interactive controls retain native 48dp bounds and selected semantics. Body text uses generated tokens; larger system fonts grow controls and scroll content. Android duration scale zero renders static loading/avatars and disables custom page, press and focus motion. New Nanodot labels are present in all 17 existing Android resource locales. Native Activity instrumentation checks actual production pages and resources; it does not claim the unrelated, currently uncompilable full Android instrumentation suite passes. Actual TalkBack next-item focus events are a separate screen-reader gate.

Shared status text uses the active scheme’s onSurface foreground and status-role icon colors, including on pages without an enclosing Material surface. The gallery supplies a Material surface; native pixel contrast is checked separately from semantic label presence.

Send/Stop keeps the primary/onPrimary pair through focus and press, using native ripple and a 100/200 ms focus/press cue (static with reduced motion). Goals completion and Feed Like use native checked controls backed by their existing stores; Add and post-information actions have localized names. Cloud mode/usage tabs expose actual selected state with native tab roles and 48 dp targets.

The immutable Dot foreground snapshot carries connection and account epochs, mode, canonical binding and a display operation. Its operation is a presentation priority over independent capture, reply, voice and Care owners; it never replaces their lifecycles.

Shared motion durations: Page 180 ms, Press 100 ms, Focus 200 ms, Breath 3200 ms, TrailLag 80 ms, TrailFade 240 ms and Snap 240 ms. TrailFade is the complete sample lifetime; TrailLag does not extend it. Snap is a semantic response duration, translated to a platform spring with damping ratio 0.9; stiffness is local to Android.

Foreground placement has explicit Bottom, AboveTarget and Compact results in the existing host-coordinate geometry engine. Placement updates the mounted native window and remembered list; it does not bind, cancel or recreate chat. Geometry invalidation interrupts the shared Snap spring; reduced motion uses immediate positioning. Compact Expand and adjustment controls retain at least 48dp targets inside the same scrollable composer viewport. The status capsule uses 14sp descriptive text, polite state semantics and a named Stop tied to the current owner. Its bounded chat/Care host participates in existing capture exclusion and teardown.

Compact status/Stop uses the existing 48dp scrolling controls budget instead of subtracting a second fixed row from history. Explicit status/Stop line heights and zero vertical button padding preserve complete text and the 48dp hit target at 200% font.

Android foreground chat and Care share a transparent pale-blue glass base, gradient edge highlights and local message/caption/control plates. Empty history is transparent, with no backdrop blur; text keeps a readable local substrate. Care's mounted target owner uses the same bounded compact Expand and click/drag adjustment controls. Target avoidance applies to current validated Care bounds; a host-wide target requires the user to move the compact card to expose the desired area.

The screen-analysis cancellation surface mounts before sampling. API34+ window capture excludes accessibility overlays while their controls stay visible; the foreground Chat flow on API26–33 uses fresh host nodes. Existing explicit Care/selection display capture retains its original permission and exclusion path. Status projects the active owner's errors, so unrelated prior capture failures are not Care evidence.
