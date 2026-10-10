// The profile's plugin link (src/profile-link.ts): a dangling link — the earlier version's
// install folder is gone — must be replaced, not left in the way of `symlinkSync` (0.1.39 on
// Windows: "EEXIST: file already exists, symlink …" at launch). Run after `tsc -p tsconfig.json`.
import assert from "node:assert/strict";
import { lstatSync, mkdirSync, mkdtempSync, readlinkSync, rmSync, symlinkSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { after, test } from "node:test";
import { linkTarget, relink, removeLink, sameTarget } from "../out/profile-link.js";

const roots = [];
after(() => {
  for (const dir of roots) rmSync(dir, { recursive: true, force: true });
});

function scratch() {
  const dir = mkdtempSync(join(tmpdir(), "nm-profile-link-"));
  roots.push(dir);
  return dir;
}

test("a fresh link is made and reported", () => {
  const dir = scratch();
  const target = join(dir, "resources", "dsh-nanomuse");
  mkdirSync(target, { recursive: true });
  const link = join(dir, "node_modules", "dsh-nanomuse");
  mkdirSync(join(dir, "node_modules"));
  assert.equal(relink(link, target), true);
  assert.equal(sameTarget(readlinkSync(link), target), true);
  assert.equal(relink(link, target), false, "a link that already points there is left alone");
});

test("a dangling link from an earlier install is replaced", () => {
  const dir = scratch();
  const old = join(dir, "Programs", "Nanodot", "nanomuse-desktop", "dsh-nanomuse");
  mkdirSync(old, { recursive: true });
  const link = join(dir, "node_modules", "dsh-nanomuse");
  mkdirSync(join(dir, "node_modules"));
  symlinkSync(old, link, process.platform === "win32" ? "junction" : "dir");
  rmSync(join(dir, "Programs"), { recursive: true, force: true }); // the old version is gone
  assert.equal(sameTarget(linkTarget(link), old), true, "the stale link is still there");
  const target = join(dir, "Programs", "nanomuse-desktop", "dsh-nanomuse");
  mkdirSync(target, { recursive: true });
  assert.equal(relink(link, target), true);
  assert.equal(sameTarget(readlinkSync(link), target), true);
});

test("a real directory in the link's place is removed, a missing one is fine", () => {
  const dir = scratch();
  const target = join(dir, "resources", "dsh-nanomuse");
  mkdirSync(target, { recursive: true });
  const link = join(dir, "node_modules", "dsh-nanomuse");
  mkdirSync(link, { recursive: true });
  writeFileSync(join(link, "index.js"), "");
  assert.equal(linkTarget(link), "(not a link)");
  assert.equal(relink(link, target), true);
  assert.equal(lstatSync(link).isSymbolicLink(), true);
  removeLink(link);
  removeLink(link); // nothing there: no error
  assert.equal(linkTarget(link), undefined);
});

test("targets compare without a trailing separator or the \\\\?\\ prefix", () => {
  assert.equal(sameTarget("/a/b/", "/a/b"), true);
  assert.equal(sameTarget(undefined, "/a/b"), false);
  assert.equal(sameTarget("/a/c", "/a/b"), false);
  if (process.platform === "win32") assert.equal(sameTarget("\\\\?\\C:\\a\\b\\", "C:\\a\\b"), true);
});
