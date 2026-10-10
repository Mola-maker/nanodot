// The link from the dsh profile's node_modules to the plugin shipped in the app's resources:
// `<home>/profiles/nanomuse/node_modules/dsh-nanomuse` → `<resources>/dsh/node_modules/dsh-nanomuse`.
// A symlink on macOS and Linux, a junction on Windows (no privilege needed, and it points at
// a directory). Rewritten whenever its target is not the one this build ships.
import { lstatSync, readlinkSync, rmdirSync, rmSync, symlinkSync, unlinkSync } from "node:fs";

/** Where `link` points today: the target path, "(not a link)", or undefined when nothing is there. */
export function linkTarget(link: string): string | undefined {
  try {
    return lstatSync(link).isSymbolicLink() ? readlinkSync(link) : "(not a link)";
  } catch {
    return undefined;
  }
}

/**
 * Two paths to the same place, as the file system reports them. `readlink` of a junction comes
 * back as Windows stored it — with a trailing backslash, sometimes behind the `\\?\` prefix —
 * and a comparison that minded those re-created the link on every launch.
 */
export function sameTarget(a: string | undefined, b: string): boolean {
  if (a === undefined) return false;
  const norm = (p: string): string => {
    let s = p.startsWith("\\\\?\\") ? p.slice(4) : p;
    s = s.replace(/[\\/]+$/, "");
    return process.platform === "win32" ? s.toLowerCase() : s;
  };
  return norm(a) === norm(b);
}

/**
 * Remove whatever is at `link` so a new link can be made there. A symlink or junction goes
 * as a link: `unlinkSync` (`rmdirSync` for a junction, which is a directory to Windows) —
 * never through `rmSync`, which follows the link first and, when the target is gone (the
 * install folder of an earlier version, say), sees nothing to remove and leaves the stale
 * link standing; `symlinkSync` then fails with "EEXIST … symlink" and the app does not
 * start (0.1.39 on Windows after an install path had changed). A real directory — someone
 * copied the plugin in — is removed as one.
 */
export function removeLink(link: string): void {
  let isLink: boolean;
  try {
    isLink = lstatSync(link).isSymbolicLink();
  } catch {
    return;
  }
  if (isLink) {
    try {
      unlinkSync(link);
      return;
    } catch {
      // a junction: Windows removes those as directories
    }
    try {
      rmdirSync(link);
      return;
    } catch {
      // fall through to the general case
    }
  }
  rmSync(link, { recursive: true, force: true });
}

/**
 * Make `link` point at `target`; true when the link was (re)made, false when it already did.
 * A link that cannot be made is reported by the thrown error, with what was there before.
 */
export function relink(link: string, target: string): boolean {
  if (sameTarget(linkTarget(link), target)) return false;
  removeLink(link);
  try {
    symlinkSync(target, link, process.platform === "win32" ? "junction" : "dir");
  } catch (exc) {
    // one more try: whatever lstat missed the first time is gone now, or it is not ours to fix
    removeLink(link);
    try {
      symlinkSync(target, link, process.platform === "win32" ? "junction" : "dir");
    } catch {
      const left = linkTarget(link);
      throw new Error(
        `${String((exc as Error).message ?? exc)} (in the way: ${left ?? "nothing"}; remove ${link} by hand and start Nanodot again)`,
      );
    }
  }
  return true;
}
