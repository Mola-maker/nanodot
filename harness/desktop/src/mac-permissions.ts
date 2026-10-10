// The arrangement follows UI-TARS-desktop (https://github.com/bytedance/UI-TARS-desktop),
// © 2025 Bytedance, Inc. and its affiliates, Apache-2.0: `apps/ui-tars/src/main/utils/systemPermissions.ts`
// — the two native modules, `getAuthStatus` for both permissions, the system prompts at launch.

/**
 * macOS's two permissions for the hands, through the native modules UI-TARS-desktop uses:
 *
 *   • `@computer-use/node-mac-permissions` — `getAuthStatus('screen' | 'accessibility')`
 *     (TCC's own answer: authorized / denied / restricted / not determined),
 *     `askForScreenCaptureAccess()` (`CGRequestScreenCaptureAccess`: the system's dialog,
 *     and the app appears in the Screen Recording pane's list), `askForAccessibilityAccess()`
 *     (`AXIsProcessTrustedWithOptions` with the prompt).
 *   • `@computer-use/mac-screen-capture-permissions` — `hasScreenCapturePermission()`
 *     (`CGPreflightScreenCaptureAccess`, no prompt).
 *
 * Both are optional dependencies with a `.node` inside (asarUnpack covers `@computer-use/**`),
 * loaded with `require` inside a try: a Linux or Windows build, or a Mac build whose addon did
 * not come along, has `loaded === false` and main.ts keeps Electron's own `systemPreferences`
 * probes, as before 0.1.37. Nothing here runs off macOS.
 *
 * Since 0.1.38 the grants belong to the helper app, "Nanodot Computer Use" (mac-helper.ts),
 * when it is running: `useHelper()` puts it first, and `status()`, `askScreen()` and
 * `askAccessibility()` then report and ask for *its* grants — the app's own, read by the
 * modules above, are what the panes show only in a build without the helper, or when it
 * could not start. The helper's status is the last one it gave (it is re-read every two
 * seconds while it runs), because the readers here are synchronous.
 */
import type { MacHelper } from "./mac-helper";

export type AuthStatus = "authorized" | "denied" | "restricted" | "not determined";
export type MacPermissionKind = "accessibility" | "screen";

let helper: MacHelper | null = null;

/** The helper whose grants count (null: the app's own, as before). */
export function useHelper(next: MacHelper | null): void {
  helper = next;
}

/**
 * Whether the helper is the one holding the grants right now — running, or between two
 * processes (a restart for a fresh grant, the first start): in that moment it is still the
 * thing whose rows the panes name, not the app.
 */
export function helperInUse(): boolean {
  return helper?.running() === true || helper?.busy() === true;
}

/** The helper's last status as TCC words (the last one it ever gave while it restarts), or null when it is not in use. */
function helperStatus(kind: MacPermissionKind): AuthStatus | null {
  const status = helper?.cachedStatus() ?? (helper?.busy() ? helper.lastKnownStatus() : null);
  if (!status) return null;
  if (kind === "accessibility") return status.accessibility ? "authorized" : "denied";
  return status.screen === "granted" ? "authorized" : status.screen === "denied" ? "denied" : "not determined";
}

interface NodeMacPermissions {
  getAuthStatus(type: MacPermissionKind): AuthStatus;
  askForAccessibilityAccess(): void;
  askForScreenCaptureAccess(): void;
}

interface ScreenCapturePermissions {
  hasScreenCapturePermission(): boolean;
  hasPromptedForPermission(): boolean;
}

let permissions: NodeMacPermissions | null = null;
let screenCapture: ScreenCapturePermissions | null = null;
let attempted = false;
let loadNote = "";

/** Load the two addons once; the reason when one is missing (for the log). */
function load(): void {
  if (attempted) return;
  attempted = true;
  if (process.platform !== "darwin") {
    loadNote = "not macOS";
    return;
  }
  const notes: string[] = [];
  try {
    // eslint-disable-next-line @typescript-eslint/no-require-imports
    const mod = require("@computer-use/node-mac-permissions") as Partial<NodeMacPermissions>;
    if (typeof mod.getAuthStatus === "function") permissions = mod as NodeMacPermissions;
    else notes.push("node-mac-permissions: no getAuthStatus");
  } catch (exc) {
    notes.push(`node-mac-permissions: ${String((exc as Error).message ?? exc)}`);
  }
  try {
    // eslint-disable-next-line @typescript-eslint/no-require-imports
    const mod = require("@computer-use/mac-screen-capture-permissions") as Partial<ScreenCapturePermissions>;
    if (typeof mod.hasScreenCapturePermission === "function") screenCapture = mod as ScreenCapturePermissions;
    else notes.push("mac-screen-capture-permissions: no hasScreenCapturePermission");
  } catch (exc) {
    notes.push(`mac-screen-capture-permissions: ${String((exc as Error).message ?? exc)}`);
  }
  loadNote = notes.join("; ");
}

/** Whether the native status is available (macOS, and node-mac-permissions loaded). */
export function loaded(): boolean {
  load();
  return permissions !== null;
}

/** Why the native modules are not in use ("" when they are). */
export function loadError(): string {
  load();
  return permissions && screenCapture ? "" : loadNote;
}

/**
 * TCC's answer for one permission, or null when the native module is not there. For the
 * screen, `CGPreflightScreenCaptureAccess` is asked first when it is available: it is the
 * call that reads the live grant, while `getAuthStatus('screen')` is derived from the window
 * list and can lag a moment after the switch flips.
 */
export function status(kind: MacPermissionKind): AuthStatus | null {
  const viaHelper = helperStatus(kind);
  if (viaHelper) return viaHelper;
  load();
  if (!permissions) return null;
  try {
    if (kind === "screen" && screenCapture) {
      try {
        if (screenCapture.hasScreenCapturePermission()) return "authorized";
      } catch {
        /* fall through to getAuthStatus */
      }
    }
    return permissions.getAuthStatus(kind);
  } catch {
    return null;
  }
}

/**
 * The system's own Screen Recording request (`CGRequestScreenCaptureAccess`): shows the
 * dialog the first time and lists the app in the pane; afterwards it only reads the status.
 * True when the request could be made.
 */
export function askScreen(): boolean {
  if (helper?.running()) {
    // the helper's own dialog and pane entry; the status follows on its next reading
    void helper.request("screen").catch(() => undefined);
    return true;
  }
  load();
  if (!permissions) return false;
  try {
    permissions.askForScreenCaptureAccess();
    return true;
  } catch {
    return false;
  }
}

/** `AXIsProcessTrustedWithOptions` with the prompt: the Accessibility dialog. True when asked. */
export function askAccessibility(): boolean {
  if (helper?.running()) {
    void helper.request("accessibility").catch(() => undefined);
    return true;
  }
  load();
  if (!permissions) return false;
  try {
    permissions.askForAccessibilityAccess();
    return true;
  } catch {
    return false;
  }
}
