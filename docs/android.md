# The Android app

`android/` is nanoMuse for the phone: [OpenMinis](https://github.com/OpenMinis/OpenMinis)
1.13 (GPL-3.0): a native app whose agent runs **on the phone**, with a Linux shell, a
browser, MCP servers, skills and scheduled tasks inside the APK, with nanoMuse's own
identity, design and features on top. Everything of nanoMuse's lives in
`io.github.nanomuse.*`; edits inside OpenMinis files are marked `// nanoMuse:`. The
application id is `io.github.nanomuse.app` and the URL scheme the system knows the app by is
`nanomuse://`, so a phone that also has OpenMinis installed never has two apps claiming one
scheme; inside the app, links stay `minis://…` (upstream's vocabulary, shared with the sandbox,
the system prompt and the model), a `nanomuse://` link arriving from the system is read as its
`minis://` form, and every link of the app's own that the app opens itself is addressed to
`MainActivity` by name, never resolved by the system
(`io.github.nanomuse.deeplink.OwnScheme`). The sandbox's offload socket, the abstract socket
proot talks to the app through, is the app's own too: `io.github.nanomuse.app.native-offload`
rather than upstream's `native-offload`, since abstract sockets are one namespace for every
app on the device and whichever app bound the shared name second would die at start; when
even that name is still held by a previous process of ours, the server binds a per-process
name and tells proot that one (`io.github.nanomuse.sandbox.OffloadSocketName`). The
history of this choice is in [roadmap.md](roadmap.md); what came before it (a WebView
around a Python server, two APK flavours) is kept as a design record in
[archive/android-python-line.md](archive/android-python-line.md) and is not what you
download today.

One APK, one architecture: `nanoMuse-<version>-arm64.apk` (arm64-v8a, Android 8.0 / API 26
or newer, `targetSdk` 35). Every release is signed with the same key, so a newer APK
installs over the old one and keeps its data. No Play Store listing: *Settings → Version* shows
the installed build next to the newest release (`nanomuse.cn/dl/index.json` first, this
repository's GitHub Releases second: *Latest 0.1.x, you have it* / *0.1.x is out, tap to
update*) and offers the download.

## App navigation

The Android home has four labeled destinations: **Chat**, **Tasks**, **Devices** and
**Settings**. Tasks contains the existing Feed, Ideas, Goals and Library pages. Returning
from another destination keeps the selected task page and conversation draft. An active reply
continues while Tasks is open. Settings
continues to provide models, terminal, skills, connectors, memory, files, backup and
permissions; the session drawer retains the complete chat archive and profile paths.
Model setup stays in the Chat panel while the other destinations remain available.

Navigation adapts to the allocated window: below 600 dp it uses bottom navigation,
600–839 dp a labeled rail, and from 840 dp a session list beside the conversation.
Large system text can collapse the conversation list into the drawer to keep enough room
for the composer. Bottom labels use two rows at 150% and 200%; rail labels stay visible.
Back closes the drawer, returns from Tasks to Chat, then returns a side conversation to
the main conversation. Existing session links still target the same home host across
rotation and window resizing; session IDs and stored model settings are unchanged.

## Dot orb gesture foundation

The pure `io.github.nanomuse.dot.gesture.DotGestureReducer` prepares gesture arbitration
for the accessibility orb. Single and double taps wait for the system's tap
interval; a qualifying third tap emits only a triple-tap intent. Holding the next tap
suspends that sequence's deadline. Movement beyond touch slop becomes a drag, which
remains valid during a long trajectory. A stationary long press suppresses tap actions;
cancellation, rotation, and additional fingers clear pending gestures. The adapter must
supply `ViewConfiguration` limits and monotonic times. The orb uses these system limits and event times.

When you enable Nanodot’s accessibility service, a 48dp Dot orb appears above other apps.
Settings → Appearance → Show Dot orb hides it immediately and preserves that choice
across service reconnects. Locking the phone, disabling accessibility, or rotating the
display clears pending gestures and removes the old window; unlock and rotation restore
it only if the switch is on. Drag the orb to move it to an edge; its position is saved
relative to the usable display area, excluding system bars and display cutouts. On Android
8–10, Dot waits for its native content frame before restoring its position. Screen-region
selection is not available yet; drags currently move the orb. Other apps receive touches outside the orb.

TalkBack exposes Open conversation, View screen, and Start care actions. A single tap
opens a foreground Compose conversation in a real accessibility overlay. A double tap
captures the host before opening that panel and shows a removable preview with its capture
time. The preview is a historical attachment to the named conversation, even after the
panel opens. It stays in private cache until you press Send; removing it, collapsing the
panel, changing conversations or cancelling deletes owned temporary copies. Confirmed
message media remains in conversation history. The overlay and the main app share the
same ChatViewModel and draft-to-persisted session identity.

Back first hides the keyboard, then collapses the panel to the orb. Send, Stop and Retry
use the existing chat pipeline. An empty conversation and an unconfigured model have
explicit states. Older devices show a clearly labelled fresh text-only preview unless
separate screen-capture permission is active; pixel errors may also fall back to newly
sampled accessible text. Voice, care and screen-region selection remain unavailable.
Showing the orb or opening conversation alone never captures, records audio or calls a
model. Live gateway text/SSE and unique-host vision acceptance remain pending deployment.

## Dot scene sampling

Dot’s read-only screen repository copies accessible host nodes and redacts password text
and descriptions. Each requested snapshot carries its host window/display, scene version,
rotation, insets, keyboard bounds, capture time and pixel transform. The `insets: RectPx`
field stores left/top/right/bottom host edge margins from the display, including keyboard
and multiwindow space; it is not a rectangle or universal system `WindowInsets`, and its
width/height must not be used. Only Dot’s owned orb and panel
windows are excluded; Nanodot’s normal Activity can be the host. Captures run one at a time,
temporarily hide the orb and panel through a completed draw, and release cancelled or late buffers.
Locked devices, changed scenes, permission loss, rate limits and timeouts return an
unavailable code; an earlier image is never substituted. Callers close accepted snapshots.

Android 8–10 returns fresh accessible text by default. Settings → Appearance → Allow
screen capture opens a separate explanation and Android’s screen-recording consent dialog.
Permission enables pixels while a visible foreground notification offers Stop capture;
stopping, locking the device or disconnecting accessibility ends that grant’s session.
Android 11–13 uses display screenshots. Android 14+ uses the host window for care and the
display for chat or selection. Secure-window errors are available on Android 14+ window
capture. Older display/projection APIs can produce a black host instead: this is reported
as `blank_host_unavailable` when the publicly accessible `android:id/content` viewport,
intersected with the host window, is entirely black. This can also mean a truly black page,
rather than proof of secure content. Without a public viewport node, the check uses the
host window; visible system bars can prevent detection. Older APIs cannot universally
identify protected pages, although Android still masks their pixels.

The additive `captureNodes(generation)` entry explicitly re-reads the current scene for a
fresh text-only fallback after a pixel error. The conversation coordinator labels that fallback as text only. The repository itself
does not call a model or upload a screen.

## Care planner foundation

The source-only `io.github.nanomuse.dot.care.CarePlanner` prepares **one instruction for
you to perform**. It accepts a goal, the caller-owned current `ScreenSnapshot`, and
steps the caller declares previously verified. It calls the selected existing
`LLMProvider.sendMessage` once with empty tools and images, temperature 0 and
`ThinkingLevel.OFF`; it has no Hands, gesture, input, clipboard or agent-loop executor,
and performs no planner retry or provider fallback. A provider may materialize its
own transport stream; OFF is an interface request, not proof of every remote model's
reasoning behavior. The planner and controller foundations are not yet connected to the orb or care UI.

The request is JSON in a user message, with a fixed system contract: goals, node text
and past steps are quoted, untrusted context, never system instructions. Only enabled,
non-password nodes with unique references and finite, positive bounds inside the current
snapshot are sent. Pixels are not uploaded. Empty or unusable node sets return a readable
clarification supplied by the caller from `nm_care_clarify_no_nodes` in all 17 locales;
future care callers must resolve that resource in their own locale. The planner never
closes or recycles the caller's snapshot. It cannot handle a Canvas-only page as a verified
node target.

Output is a single strict JSON object, with case-sensitive values. A step has exactly
`type: "Step"`, `snapshotId`, `scene` (`packageName`, `windowId`, `displayId`, `version`),
`targetRef`, `action` (`Open`, `Focus`, `Copy`, `Paste`, `Search`), `instruction`, nullable
`copyText`, `expectation`, and `confidence`. Expectations are `PageContains` with `text`,
`Focused` with `ref`, `TextEquals` with `ref` and `text`, or `ResultsFor` with `query`;
each also has its `type`. A clarification has only `type: "Clarify"` and `question`.
Obvious script forms, extra fields, bare coordinates, duplicate keys, stale scenes/references,
password targets, nonfinite values or confidence below 0.7 are rejected. Focus/text
expectation references must equal the current target. These are user intents and
observable postconditions, not action handlers; the verifier owns completion.

Script refusal also recognizes unfenced calls (including whitespace-separated numeric
arguments), member/index assignments, function bodies,
arrow functions and variable declarations, independent of specific function names. Ordinary
Chinese queries, numbers, prose and HTTP(S) URLs remain data. This conservative check may
refuse an ordinary label that resembles code; rephrase it. Finite static recognition does
not prove that every encoded program or language is script-free. Output remains plain
user guidance and can never enter an executor.

One call is limited to 30 seconds, at most 1,024 output tokens (or the provider's smaller
ceiling), 32,768 total request characters, 8,192 response characters, 128 eligible nodes,
a 2,048-character goal, 1,024-character instruction/expectation text and 2,048-character
copy text. A known model context window is checked conservatively against request UTF-8
bytes plus the output allowance. Oversize context is rejected instead of truncating
identity or text. At most the last eight structurally valid declared steps with an older
or equal scene version and different snapshot ID are sent as past context; this is not independent
proof of their completion. Malformed, unsupported, truncated, tool or media output returns
`recovering_invalid_proposal`; timeout, provider and budget failures have data-only codes
for the later controller's localized Recovering state. Caller cancellation propagates.

Fake-provider unit results validate request isolation and schema rejection only. A real
model response targeting a current fixture node, native care interaction, TalkBack and
evidence-based advancement require separate gates; no fixed fake proposal counts
as model success. The default service and existing stored credentials are unchanged.

## Care evidence controller foundation {#care-evidence-controller}

The `CareController` exposes a state flow for observing, clarifying, guiding,
waiting, verifying, recovering, pausing and ending a task. It does not click, type, paste,
read the clipboard or run Hands. Its caller owns one coroutine scope and supplies fresh
screen sampling, the read-only planner and the current host scene. Accessibility events
only request a debounced sample; elapsed time never completes a step.

`CareVerifier` compares the proposal's original snapshot with fresh host evidence. It
relinks a unique target using its view ID and Android class, or a stable accessible
label when no view ID exists. Snapshot-local references and old bounds are never reused.
Only a newly focused correct field or a readable change to the expected field text can
advance automatically. An already satisfied condition, wrong field, unrelated scroll,
advertisement refresh or changed app/window/display does not establish completion.

The current node DTO has no trustworthy page-title, result-query or clipboard metadata.
Open, Search and Copy therefore require explicit confirmation when their condition
cannot be established. Matching page text alone cannot prove navigation or search
results. A ref-free `pendingInstruction` exposes only the current confirmation text, and
clears on pause, goal change or ending. Confirmation resamples the current host and records
its origin as user-confirmed; it does not claim automatic evidence or invent a scene
version. The planner can retain caller-declared confirmed history at the same scene
version with a different snapshot ID. Current-snapshot IDs and future versions remain excluded; this
is quoted past context and cannot override current screen evidence.

Pause and automatic-operation admission clear the target and reject pending replies.
Stopping automatic operation does not resume care by itself. Explicit resume observes
again. Goal changes and cancellation invalidate late captures and model replies, and
owned snapshots close after use. Protected, blank, unavailable and empty captures
recover without recording a successful step. Ending a task is an explicit user decision.

This foundation is covered by deterministic JVM tests using the public snapshot DTO.
Native wrong-target/advertisement checks, TalkBack and a full care task remain separate
acceptance gates; unit tests do not establish those results.

## Care guidance overlay {#care-guidance-overlay}

Triple-tap the same Dot, or choose its accessible **Start care** action, to open the purple
care entry and enter a goal. Opening it does not contact a model or capture the screen.
Starting a goal reads the current host and requests one instruction using your selected
visible text model, or your configured default model group. Missing configuration appears
as a recovery message with a Settings → Models instruction. Care uses a separate tools-empty
planner; it never enters the chat agent loop or retries with a different provider.

The short instruction card scrolls within the available host/keyboard area. Its buttons
copy the proposed text to the clipboard, repeat the instruction using the existing reader,
pause, look again, change the goal, or end guidance. Copying only happens when you press
**Copy text**; Care never types or pastes into the host. Uncertain steps show the pending
instruction and **I completed this step**. That explicit confirmation samples again before
recording user-confirmed evidence. It does not count a generic touch as completion.

A single purple outline marks the validated current target without taking touches or
accessibility focus. Host content, keyboard or display changes clear it before fresh
observation. Dot and Care windows are excluded from host events and hidden during capture.
The short focus transition follows Android's animator duration setting; scale zero uses
a static outline with no animation loop. Back pauses and closes the card; reopening offers
explicit resume with fresh observation. Single/double tap or entering the main chat pauses
competing Care. Locking, hiding Dot or losing the service clears its windows and speech.

Real Hands runs, permitted remote shell/open/task calls, and accessibility action commands
pause Care before acting. Overlapping operation owners keep it paused until every owner
releases; their completion never automatically resumes Care. Existing permission and risk
checks remain in place. Read-only accessibility node/screenshot requests need no action lease.

The native fixture uses an inert public provider and a disposable test host. Native touch,
copy, Back, TalkBack, light/dark, portrait/landscape and 100/150/200% font acceptance are
separate root-run gates; successful source compilation is not native or real-model acceptance.

## Install

1. Download `nanoMuse-<version>-arm64.apk` from the
   [latest release](https://github.com/nano-muse/nanoMuse/releases/latest). Verify with
   `sha256sum -c nanoMuse-<version>-arm64.apk.sha256` if you like.
2. Open it. Android asks once to allow installs from your browser or file manager.
3. **Sign in.** The first screen is the account: a phone number or an e-mail address,
   a code, or a password once you have set one. It is what lets your devices work as one
   ([hub.md](hub.md)) and brings a model to start with ([cloud.md](cloud.md): a free pool per
   account (¥10 at the time of writing) and more for each friend invited, and for them;
   the app prints the relay's current figures; then [your own key](own-key.md)). Then choose which model answers: the account's
   own, or a key of your own (any OpenAI-compatible endpoint, or the OAuth sign-ins the
   app ships with). Four models are set, not one, and they live on one page, **Settings →
   Models** (the card at the top of Settings opens it): **Chat**, the model that talks with
   you (the account's menu opens on `deepseek-v4.1-flash`); **Operating the screen**, the one
   that looks at the screen (`qwen3.8-27b`, the account's or the same model under your own
   key); **Making pictures** and **Making clips**. Each row shows `<provider> · <model>` and
   opens a picker: the *nanoMuse Cloud* group first when signed in, the recommended model
   first and marked, then one group per provider of your own with only the models that fit
   the row. A group shows eight models until you tap *Show N more* (the catalogue's default
   for the row first, then the one in use, then the rest as the provider lists them); past
   eight models in all, a *Search models* field above the groups filters every group live by
   id or name, and *No model matches* says when nothing does; the search and the opened
   groups survive a rotation and the trip through *Add a provider*. A chat pick there, or in the
   chat's own `•••` menu, is the default for new chats and moves the main chat
   (*Applies to the main chat and to new chats; a side chat keeps its model.*); a side
   chat already open keeps its model. The other three pickers
   open with *Automatic* (*Currently nanoMuse Cloud · qwen3.8-27b*), which forgets a choice
   made there and lets the row follow the order below again. Signed in, the page opens with
   one switch, *Use nanoMuse Cloud models*: off, the account leaves every picker and the
   automatic order, no side call (titles, memory, pictures, clips) runs on it, and only the
   *Use nanoMuse Cloud this time* button on a refused turn does; the sign-in stays for sync and
   your devices. If the chat model is still the account's at that moment, the Chat row moves
   to your first own chat model (the catalogue's default for that provider), as every client
   does, and the page says *Applies to the main chat and to new chats*. Switching off is
   refused with one sentence while the chat model is still the account's and you have no
   chat model of your own, since nothing else could answer: the switch stays on and *Add a
   provider of your own first; with nanoMuse Cloud off, nothing else could answer.* appears
   under it. The Cloud provider's own page (Settings → *Manage Providers*) follows the same
   rule; a provider of your own switched off there while the chat ran on it hands the chat
   to the next source, and when none is left a send says *No model can answer right now.
   Choose one under Settings › Models.* A phone with a key of
   its own works signed out: the welcome page offers *Use your own API key instead*. Running
   everything yourself with no account at all is the runtime's `cloud.required = false`.
4. Optional: the two permissions that let the agent use your phone's apps (skippable and
   revocable). *Settings → Hands* keeps its row for the screen model, which opens the same
   picker; *More picture and clip options* at the foot of the Models page is the old *Image &
   video models* page (typing a model name, what stops without each model, re-checking which
   video models a key can use), also at `minis://settings/media`.

**Use it for.** When a provider of your own is saved, a new one or a key added to one that
had none, a card asks what this key should handle: one switch per capability the catalogue
gives the vendor (chat, operating the screen, making pictures, making clips), all on. *Use
it* switches the ticked rows to this provider, each on the catalogue's default for that job
(`defaults.chat` and friends in `providers.json`), or on the first model of its list that
fits when the default is not there; *Not now* changes nothing, and so does an unticked switch.
Signed in, the body says nanoMuse Cloud keeps the rest. The page is the place to change it later.

**When you have not chosen.** Chat is the relay's recommended model when signed in, else the
first provider of your own. The screen, pictures and clips follow the chat provider when it is
a provider of your own with that capability (its catalogue default); else the relay's model
when signed in; else the first provider of your own that can. A choice always wins, and
nanoMuse Cloud never steps in front of a provider you chose. When a model of your own fails,
nothing falls back on its own: the error card gets *Use nanoMuse Cloud this time* (signed in
only), which runs that one turn on the relay's chat model and changes no row.

**Your own relay.** The sign-in screen talks to nanoMuse Cloud unless you tell it
otherwise: *Use a different server* under the sign-in form takes the address of a relay
you run yourself ([cloud.md](cloud.md), `cloud/docker-compose.yml`), *Check* asks its
`/healthz` and shows the version it answers with, *Use this server* keeps the address
across launches. `https://` is required for anything outside your own network; plain
`http://` is accepted for an address on your own network, judged by the same rule as a
model server's (below: the private ranges as parsed addresses, `localhost`, a name without
a dot, a `.local`, `.lan`, `.home`, `.internal`, `.home.arpa` or `.ts.net` name). Settings → Account shows *Server: `<host>`* with *Change*, which signs the phone out first: the key it holds belongs to the
server that issued it. Everything the app does with the account (sign-in, the hub, sync,
usage, the model menu) goes to that address.

## What the phone does

| | |
| --- | --- |
| **The agent, on the phone** | A sandboxed Alpine Linux, a real shell, files, a browser, MCP servers, skills in the Agent Skills format, memory, scheduled tasks; approvals before deleting, sending or paying. The model is the account's or your own; nothing you say passes through the project's relay when you use your own key. |
| **Hands** (`hands/`) | With *Hands* on and the accessibility service enabled, the agent looks at the phone's screen and taps, types and swipes in its apps: the last rung after APIs, fetches and the browser. A stage over the app shows where it is about to tap and a capsule with the step and **Stop**; a glow breathes along the screen's edges (blue working, amber waiting; 2.4 s in, 2.4 s out, like every working light on every client since round 9: nothing runs round the rim or slides down the screen, and the capsule's ring and bars breathe at the same pace; everything holds still under the system's reduce-motion setting); when a tap would send, post or delete, the capsule itself asks (**Allow** / **Deny**, the same request the chat card and the notification show), so you are not pulled back into nanoMuse (money is confirmed with the screen lock in the chat card; the capsule offers *Open* for that one); the capsule only ever answers its own request, never a shell or browser card of another conversation, and **Stop** while a card is waiting denies it and ends the run at once. Passwords and codes are always yours to type. The same action a third time on a screen that did not change gets a line in the history so the model changes tack, and a sixth ends the run as *infeasible* with the action named (`HandsLoopWatch`, the floor the runtime has too), instead of one model call per step until the thirty-minute limit. Every step's screenshot is written next to the run's trace under the session's `attachments/hands/<run>/`; the ten newest runs keep all of theirs, an older run keeps its trace and the last screen (the one the chat shows), and past 200 runs the oldest is removed (`HandsTraces`). [gui.md](gui.md) is the design record; the app's own hands are in `io.github.nanomuse.hands`. |
| **Reach** (`reach/`) | Your computers, from the phone: "say it on the phone, it gets done there" (a shell command, a file, the computer's screen, or a whole task for the nanoMuse running there). The way in is the hub (next row): install nanoMuse Desktop on the computer and sign in with the same account, and it is under *Account → Devices* within seconds, on any network, the only way since 0.1.24 (the local-network host script is gone). *Settings → Computers* lists the account's computers and says which account the phone uses, since a missing computer has nearly always signed in with another one. Approvals are decided on the phone before anything is sent. [every-device.md](every-device.md). |
| **The hub** (`hub/`) | Every signed-in device of the account meets on the relay's hub: the phone sees your computers, asks them to do things, gets their approvals as cards, and can be asked by them. A foreground service keeps it reachable in the background (Android 13+ asks for the notification permission for that). [hub.md](hub.md). |
| **Coding agents** (`ui/coding/`) | The Cursor, Codex and Claude Code sessions on your computers, seen and steered from the phone. [coding-agents.md](coding-agents.md). |
| **Account** (`ui/cloud/`) | Who is signed in, the password, every device holding a key, usage by kind and by model, the ways out. When the free pool is spent or past 80 %, the account page and the refused turn show the ways on: your own key, that is, the providers of the catalogue, the region's first (Alibaba Cloud Bailian for people in mainland China: the UI in simplified Chinese, a phone-number sign-in, or the relay saying so; OpenRouter and OpenAI everywhere else), each saying what it covers; a subscription you already pay for (ChatGPT, Claude, Kimi, OpenRouter sign in instead of a key); and an invitation. See [Your own key](#your-own-key) below. A phone shows and syncs the signed-in account's conversations only: since 0.1.40 every chat has an owner, synced or not, and a sign-out asks *Keep this account's chats on this device* (off by default: the account's chats, memory, feed, goals, routines and face leave the phone; on: put aside until it returns); deleting the account removes all of it; a key the relay refuses (`401 bad_key`) puts the account's data aside as *Keep* would and the sign-in page says so until the next sign-in; only `401 account_deleted` removes it; signed out, only the chats made while signed out show ([sync.md](sync.md)). |
| **The browser handed over** (`browser/`) | A page that needs you (a login, a verification code, a payment, a CAPTCHA) is handed over instead of described: `browser_use`'s `hand_over` action opens the agent's own tab (same WebView, same session) in the browser sheet with the agent's hold released, a **Your turn** card above the composer says what the page asks of you with *Open the page* and *Done, continue*, the sheet carries the same line and button, and the tool call waits for *Done* (fifteen minutes at most) before the agent goes on from the page as it is. `io.github.nanomuse.browser.BrowserHandOver`. |
| **Connectors** (`connectors/`, `ui/connectors/`) | The services the agent can be let into: the desktop's catalogue of 75 remote MCP servers (Notion, Linear, GitHub, GitLab, Slack, Stripe, Miro, …), shipped as `assets/nanomuse/connectors.json`. The only Settings row for all of this is *Connectors*; the upstream MCP editor (by address, by command, imported JSON) is *Your own servers* at the end of the page. Open servers add with a tap; a key service takes the key; an OAuth service runs the MCP authorization flow (discovery, dynamic client registration, PKCE in a Custom Tab) and the token goes into the entry's `Authorization` header for the in-guest MCP client, refreshed at app start; a service whose authorization server registers no clients (`clientIdRequired`: GitHub, Slack, Discord, HubSpot, Render, Bitrise, PagerDuty, Box) asks for an OAuth client id and secret made at the vendor's developer page with the app's callback address, which the sheet shows and copies. A connected service is an MCP server entry under the same id; *Your own servers* manages it too. What is connected is shared with the account's other devices through the relay profile, the entries (id, label, address, kind of auth, which device, when), never a token, so the page also lists *On your other devices*: a service connected on the desktop shows as *Connected on <device>. Sign in here to use it on this phone*, one tap into the same sheet. |
| **The chat, plain** | While the agent works, the line under its avatar names the step under way (*nanoMuse is using Shell*, *Writing the reply*, *On it: book the table*), never a state of mind. The tool pills, the Computer sheet and the floating step bar are **on by default** since 0.1.37; *Settings → Appearance → Show the agent's steps* turns them off. With them on, a finished step reads *nanoMuse used Shell · Done*, and its sheet closes on ×, swipe or Back. |

The web console of the same account is at the relay (`/app`), the desktop app in
[desktop.md](desktop.md); the phone, the desktop and the web share the design language
described in [brand.md](brand.md).

## Your own key

The vendors the app knows are one file, `assets/nanomuse/providers.json`, a copy of the
runtime's `nanomuse/llm/providers.json` written by `node scripts/providers-json.mjs`, the same
catalogue every client reads ([own-key.md](own-key.md)). Each entry says where the endpoint is,
where a key is made, which sign-ins it has, where it signs people up (`cn`, `global`) and what
its models can do: `chat`, `vision` (the hands' screen), `image` (pictures), `video` (clips).

**The card.** When the allowance is spent or nearly, *Use your own model key* lists the
catalogue, the region's vendors first: Alibaba Cloud Bailian on the mainland (one key covers
all four; it signs up accounts from mainland China only), OpenRouter and OpenAI elsewhere;
then the rest, three at a time behind *More providers*. Every row says what the vendor covers
(*chat · screen · pictures · clips*); *Add* opens the provider form pre-filled with its name,
endpoint and `/v1` setting (`minis://settings/providers/add?preset=<id>`), *Get a key* opens
the vendor's key page. No vendor is recommended. *A subscription you already pay for* lists
the vendors whose plan signs in instead of a key: ChatGPT (OpenAI's Codex OAuth), Claude,
Kimi (a device code) and OpenRouter; *Sign in* opens the same form on the sign-in button
(`?preset=<id>:oauth`), which runs OpenMinis' own `OpenAIOAuthManager`, `ClaudeOAuthManager`,
`KimiOAuthManager` or `OpenRouterOAuthManager`. The ChatGPT row carries the line that
OpenAI's terms cover a ChatGPT plan inside OpenAI's own Codex, that other apps have had this
access cut off before (OpenCode, January 2026), and that an API key works if it stops.
Anthropic and Gemini open on OpenMinis' own provider types for them; everything else on the
OpenAI-compatible form.

**Where the list comes from.** The relay's `/v1/me` carries `spend.guidance` (relay 0.21,
[cloud.md](cloud.md)): the providers for the person's region in the relay's order, each with
what it covers, the plans one can sign in with and which clients they are for, the local
servers, the docs link and the honest line about the ChatGPT sign-in. `cloud/Guidance.kt`
parses it, `Ways.resolve` prefers it, and the card and Settings → nanoMuse Cloud read the
bundled catalogue only when the relay sent none (an older relay), so a vendor added on the
relay shows before the app is updated, and a provider the relay no longer lists is gone the same
day. A `429 allowance_exhausted` carries the same block beside the figures.

**What each covers.** The capabilities decide what the app offers (`cloud/Capabilities.kt`):
pictures (Settings → Models → Making pictures, the avatar studio, `nanomuse-media image`) pick a
provider only among those whose vendor has `image`; clips among those with `video` that the
app can drive (Bailian's video API); the screen's model and its picker list only models of
vendors with `vision`. A ChatGPT plan signed in through Codex is chat and vision (the Codex
backend has no image or video endpoints), so it is never offered for pictures. A vendor the
catalogue does not know (a gateway, a relay of your own, nanoMuse Cloud) is taken at its
models' word, as before. When no configured provider has a capability, the page says so in one
sentence (*Pictures need a provider with image models: Bailian, OpenAI, Gemini or
OpenRouter.*) instead of failing.

**When the provider cannot be reached** (`net/ProviderReach.kt`, `ui/chat/ProviderReachCard.kt`).
A failed turn whose error is the transport's (OkHttp's *failed to connect to chatgpt.com/… (port
443)*, *Unable to resolve host*, a TLS or timeout line) or OpenAI's *region not supported* is
shown as a card instead of the red banner: what happened, what helps (a VPN on this phone, the
proxy under *Settings → Network*, another provider with a key of your own), *Try again*, and the
raw line behind *Details*. The 401/403/429 whose bodies upstream's `mapHttpError` drops are
kept for a few seconds by `ReachSignal` (one `// nanoMuse:` spot in `OpenAIProvider`) and
written into the message as a canonical `nm_reach:` line, so an expired ChatGPT sign-in reads
*Sign in again* and a spent plan window reads *The ChatGPT plan has nothing left for now* with
OpenAI's own sentence and the reset time; a plain *Invalid API key* on a key provider stays
upstream's. The relay's `daily_cap` 429 is now read like `allowance_exhausted` and the card
says today's share is spent and comes back with the day. The allowance card also lists a model
of your own (Ollama, LM Studio, vLLM on a computer you own) and, for a signed-in account, your
computer: *@* and its name in the chat runs the turn there.

**When the relay refuses a turn** (`cloud/RelayRefusal.kt`, `ui/chat/RelayRefusalCard.kt`).
Every refusal nanoMuse Cloud sends is one plain sentence in the phone's language and the button
that fits, never a status code, the relay's JSON or upstream's *Rate limited*. The reply is
read where the body is still whole (`AllowanceSignal.noteHttpError`, through the same
`// nanoMuse:` spot in `OpenAIProvider`), stored in the message as a canonical `nm_relay:` line
and drawn by the card: `413 too_large` (also a proxy's plain 413 from the relay's host) → *That
message is too large for the model. Shorten it, leave out some attachments, or start a new
chat.* with *New chat*; `400 content_rejected` (the provider's content check declined the
words; also a provider's own `DataInspectionFailed` on a key of one's own) → *The model
provider's content check declined this request. Try different words, or start a new chat if it
keeps happening: the words it objects to can be earlier in the conversation.* with *New chat*
and *Try again*, and the compaction's split-retry (`ChatViewModel.shouldSplitOnError`) leaves it
alone, since the same words come back refused however small the request; `401 bad_key` /
`account_deleted` → *Sign in*; `403 not_invited` /
`account_disabled` / `signup_closed` → *Open Settings*; `429 too_many_in_flight` / `locked` /
`rate_limited` and `provider_busy` (with the wait from `retry_after`) → *Try again*; `404
model_not_offered` → *Open Settings*; `503 service_paused`, `sync_paused`, `hub_paused` and any
5xx or empty answer → *Try again*. `429 allowance_exhausted` and `daily_cap` keep the allowance
card; with `paused: true` (relay 0.22) its lead says the allowance is *paused on this relay for
now, not used up* and that what is left stays as it is. The sentences are
`NanoMuseCloud.describe`'s, the same ones the sign-in and account pages use, in all 17 locales
(`nm_cloud_err_*`). Unit tests: `RelayRefusalTest`, `GuidanceTest`.

**Settings → Network** (`net/OwnProviderProxy.kt`, `ui/net/NetworkScreen.kt`,
`minis://settings/network`). The HTTP proxy for own providers: host, port, optional user name
and password, off by default, in `SharedPreferences` on this phone only. It is installed in
`MinisApp.onCreate` as the process's default `ProxySelector`, so every OkHttp client asks it per
request; it answers the proxy for the catalogue's hosts, `chatgpt.com`, `auth.openai.com` and
the custom base URLs of the provider instances (never the relay's, never a LAN address), and the
system's answer for every other host: the hub, the relay, the sandbox mirrors are untouched.
Provider clients carry `OwnProviderProxy.authenticator` for a proxy that asks for a password.
*Test* fetches `https://chatgpt.com/` through the proxy as entered and reports the status and the
milliseconds. Unit tests: `ProviderReachTest`, `OwnProviderProxyTest`.

## Privacy and permissions

The relay keeps an account id, a masked identifier, usage counts and the agent's name and
look (so your devices match), and message content only as conversation sync, on by default
when signed in and switched off under *Data controls* ([privacy.md](privacy.md),
[sync.md](sync.md)); the first conversation says so in its fourth line. On the phone, API keys, the account key and Reach
pairing tokens are in `EncryptedSharedPreferences`; since 0.1.40 the app takes no part in
the device backup at all (`allowBackup="false"`: chats, memory and keys never go to Google,
a reinstall starts empty and the account's chats come back through sync; the two rule files
under `res/xml/` are kept for the day the switch is turned on again). Hands needs the accessibility service
and the overlay permission, both optional and both revocable from the same screen (the first
run's Hands page asks for them under the app's mark, as the sign-in does); a
step that sends something (a tap on *Send*, or Enter in a message field of a messenger)
is approved one at a time, and *for this chat* answers stay bound to the app or address
they were given for. Another device of your account that wants to run, read or write
something on the phone (hub `shell`, `files`, `open`, `screen`…) is approved by the person
holding the phone first: *once* or *always for that device*, revocable under Permissions.
A path another device or the agent names (hub `files`, `nanomuse-media`, `nanomuse-pc put`)
is resolved inside the sandbox only (`io.github.nanomuse.sandbox.SandboxPaths`): `..` and a
symlink out of the rootfs lead nowhere, so the app's own private files stay out of reach.
The app allows plain `http://` only for addresses on your own network (the private ranges
`10/8`, `172.16/12`, `192.168/16`, the carrier-grade `100.64/10` Tailscale hands out, link-local,
IPv6 ULA, `localhost`, a name without a dot, and the suffixes `.local`, `.lan`, `.home`,
`.internal`, `.home.arpa`, `.localdomain`, `.ts.net`), a model server or a computer of your own,
and refuses it at the provider URL field for anything else (`io.github.nanomuse.net.LanOnly`).
The address is parsed before it is judged, so a public name such as `10.foo.example.com` is
not taken for a private address. The same rule decides whether a relay of your own may be
`http://`; nanoMuse Cloud and the hub to it are TLS only. The in-app web view is not exported
to other apps.

Debug builds keep a bounded in-memory LLM request log. Before retaining a request,
the log replaces Authorization, Proxy-Authorization, common API-key headers
(`X-API-Key`, `Api-Key`, `ApiKey`, `X-Goog-Api-Key`), Cookie and Set-Cookie values
with `[REDACTED]`, regardless of header-name case. Raw request and agent-trace
exports use the same masked snapshot; other header names and values remain useful
for diagnostics. Request bodies, response content and URLs can still contain
sensitive data, so inspect these before sharing a debug export.

## Building it yourself

JDK 17 or 21 (CI uses 21) and the Android SDK (Android Studio installs both); `scripts/android/env.sh` sets
the environment on a bare machine. The project is under `android/src/android`:

```bash
bash scripts/android/build-natives.sh   # once: proot, the Alpine rootfs and rclone.aar (NDK r27c, Go 1.25+)
cd android/src/android
./gradlew :app:assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease      # signed with android/keystore.properties when present
```

`rclone.aar` is built with Go, which fetches its modules through Go's default proxy
(`https://proxy.golang.org,direct`); on a mainland-China network set
`export GOPROXY=https://goproxy.cn,direct` first, and `env.sh` keeps whatever you exported.

`scripts/rebrand.py` carries the version (`VERSION_NAME`, `VERSION_CODE`) and applies
nanoMuse's naming to the OpenMinis tree; `android/BUILDING.md` covers the native pieces.
Without a signing key the release build is signed with the debug key, which installs but
cannot update a properly signed build. To sign, create a key once and keep it outside the
repository:

```bash
keytool -genkeypair -keystore ~/.nanomuse-release/nanomuse.jks -alias nanomuse \
        -keyalg RSA -keysize 4096 -validity 10950
```

and put its four values in `android/keystore.properties` (git-ignored: `storeFile`,
`storePassword`, `keyAlias`, `keyPassword`).

## Releases

`.github/workflows/android.yml` builds the debug APK on every change under `android/`
(the "debug APK · arm64-v8a" check). A release is made from a tag by
`scripts/release-apk.sh <version>`: it checks the version against `rebrand.py` and
Gradle, builds `:app:assembleRelease` with the release key, writes
`dist/nanoMuse-<version>-arm64.apk` and its `.sha256`, and with `--publish` tags
`v<version>` and attaches both to the GitHub release with the notes from
`docs/releases/v<version>.md`. A debug-signed APK never goes on a release, because it
could not be updated by a properly signed one. The `versionName` must match the tag,
like the Python package's version does.

## Where things are

| Path (`android/src/android/app/src/main/java/io/github/nanomuse/`) | Does |
| --- | --- |
| `cloud/NanoMuseCloud.kt` | The account: sign-in with a code or a password, the key in the encrypted store, `/v1/me`, usage, sessions, the localized error sentences; the relay's menu and its two defaults (`for: chat` / `for: gui`) |
| `cloud/Region.kt`, `cloud/ProviderCatalogue.kt`, `cloud/Capabilities.kt`, `cloud/OwnKeyPresets.kt`, `cloud/ProfileSync.kt` | Which region's vendors come first (mainland → Bailian, else OpenRouter and OpenAI); the own-key catalogue read from `assets/nanomuse/providers.json` and which vendor a configured provider is; what a provider covers and the one-sentence "unavailable" lines; the pre-filled provider forms (`?preset=<id>[:oauth]`); the relay profile (name, look, connectors) pulled and pushed |
| `sync/` | Conversation sync (contracts C7–C10): `SyncEngine` (what goes up and comes down; `owner` per mapping, the account's only), `ConversationSync` (when; `hidden`: the chats of another account, left out of `ChatRepository.observeSessions()`), `LocalChats`, the Room store `nanomuse_sync.db` (with the `session_owners` table of C12) |
| `account/` | Contract C12 (0.1.40, [sync.md](sync.md)): `AccountScope` (the rules: whose chat a session is, the account's key, what the lists leave out, what a refused key keeps (`keepOnRefusedKey`: everything, unless the relay says `account_deleted`); unit-tested), `AccountData` (applies them: every chat an owner row, `leave` puts the account's chats, memory, feed, goals, routines, face and preferences aside or deletes them, `enter` brings an account's back). The Library tab follows the same rule: it lists the workspaces of the sessions the chat list shows, so another account's files stay off a shared phone (`library/LibraryIndex.shows`, unit-tested) |
| `hub/` | `Hub` (state, device identity, settings), `HubClient` (the socket with backoff; a refused key is retried once a minute and shown as such, a replaced connection waits 30 s, a hub the operator paused waits two minutes and is shown as such), `HubService` (the foreground service), `HubActions` (what other devices may ask this phone; `stop {call | conversation}` ends a task the asking device started here, bookkept in `HubTasks`, unit-tested), `HubErrors` (failures in words) |
| `reach/` | `Computers` (paired computers, tokens in the encrypted store), the offload handler that sends work to a computer |
| `models/` | Settings → Models behind the screens: `ModelSlots` (the four slots, what each is set to and where that is kept, the groups a picker shows, `followPick` for the chat default, `applyProvider` for the *Use it for* card), `SlotOrder` (the resolution order and the catalogue default, pure Kotlin, unit-tested), `PickerList` (the picker's eight-row groups, their order and the search filter, pure Kotlin, unit-tested) |
| `ui/models/` | The Models page, the picker behind each row, the *Use it for* card (`minis://settings/models`) |
| `chat/CloudRetry.kt` | *Use nanoMuse Cloud this time*: whether the card offers it and the one-turn provider `ChatViewModel.retryLast` uses |
| `hands/` | The accessibility service as the hand, the stage and the capsule, the screen reader; `Hands.screenModel` picks the screen's model (chosen → the chat provider's own default when it sees → the Cloud's `qwen3.8-27b` → the same under your own key → a chat model that sees → the Vision Group) |
| `ui/coding/` | The coding agents of your computers |
| `connectors/`, `ui/connectors/` | The connectors catalogue (`ConnectorsCatalogue` reads the asset), MCP authorization discovery + registration (`McpAuthDiscovery`), connecting and token refresh (`Connectors`), what this phone connected as published to the profile and what the other devices did (`SharedConnectors`), the Settings → Connectors page |
| `ui/cloud/` | Sign-in, the Account screen, the Devices section |
| `res/values*/nm_strings.xml` | Every nanoMuse string in the seventeen languages the app has (English, 简体中文, 繁體中文, German, Spanish, Filipino, French, Indonesian, Japanese, Korean, Malay, Polish, Brazilian Portuguese, Romanian, Russian, Thai, Turkish); every file carries the same keys, and a missing one falls back to English |
