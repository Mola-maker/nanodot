// The two overlay pages (resources/glow.html, resources/capsule.html) are checked as text:
// the lights breathe at the phone's rhythm and nothing runs round the rim or sweeps across
// the screen; under prefers-reduced-motion they are steady; the IPC words the shell sends
// (src/main.ts) are the ones the pages read.
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import { fileURLToPath } from "node:url";

const page = (name) => readFileSync(fileURLToPath(new URL(`../resources/${name}.html`, import.meta.url)), "utf8");
const glow = page("glow");
const capsule = page("capsule");
const main = readFileSync(fileURLToPath(new URL("../src/main.ts", import.meta.url)), "utf8");

test("the glow breathes along the four edges; nothing flows round the rim", () => {
  assert.match(glow, /\.on \.glow \{[^\n]*animation: breathe 4\.8s ease-in-out infinite/);
  assert.match(glow, /@keyframes breathe \{ 0%, 100% \{ transform: scale\(1\)/);
  assert.match(glow, /50% \{ transform: scale\(1\.05\)/, "the phone's 1 → 1.05");
  for (const side of ["t", "b", "l", "r"]) assert.match(glow, new RegExp(`\\.band\\.${side} \\{`), `band ${side}`);
  // the 0.1.34–0.1.39 running border and the face at the pointer are gone
  assert.doesNotMatch(glow, /conic-gradient/);
  assert.doesNotMatch(glow, /@keyframes flow\b/);
  assert.doesNotMatch(glow, /background-position/);
  assert.doesNotMatch(glow, /class="face"/);
  // the hues: Muse's action blue while working, amber while the hands wait for the person
  assert.match(glow, /--accent: #0a66e4/);
  assert.match(glow, /\.held \{ --glow: rgba\(255,176,0/);
});

test("the marker is the phone's: halo, ring, turning cyan arc, dot, the label pill, lock-on and two ripples, a drag's dashed path", () => {
  for (const part of ['class="halo"', 'class="arc"', 'class="ring"', 'class="dot"', 'class="chip" id="markChip"', 'id="trail"', 'id="ripple1"', 'id="ripple2"']) assert.ok(glow.includes(part), part);
  assert.match(glow, /@keyframes lock \{ from \{ transform: scale\(1\.8\); opacity: 0; \}/);
  assert.match(glow, /\.mark\.lock \{ animation: lock 220ms/);
  assert.match(glow, /\.mark \.arc \{[^\n]*animation: turn 520ms linear infinite/);
  assert.match(glow, /\.ripple\.go \{ animation: ripple 520ms/);
  assert.match(glow, /\.ripple\.go\.second \{ animation-delay: 110ms; \}/);
  assert.match(glow, /\.trail line \{[^\n]*stroke-dasharray/);
  assert.match(glow, /<polygon id="trailHead"/);
  // a word with no place goes where the last ring was
  assert.match(glow, /const sayWord = \(text\)/);
});

test("the capsule is the phone's pill: the face in a three-hue ring, four breathing bars, Step N, Stop and I'll take it; the cards under it", () => {
  assert.match(capsule, /\.ring \{[^\n]*conic-gradient\(var\(--accent\), var\(--violet\), var\(--cyan\), var\(--accent\)\)[^\n]*animation: breathe 4\.8s ease-in-out infinite/);
  assert.match(capsule, /<span class="bars"><i><\/i><i><\/i><i><\/i><i><\/i><\/span>/);
  assert.match(capsule, /\.bars i \{[^\n]*animation: bar 4\.8s ease-in-out infinite/);
  assert.match(capsule, /max-width: 420px/);
  assert.match(capsule, /--ink: rgba\(17,18,24,0\.82\)/);
  assert.match(capsule, /button\.stop \{ background: #9b3b3b; \}/);
  assert.match(capsule, /data-card="hands" data-action="take"/);
  assert.match(capsule, /data-card="hands" data-action="stop"/);
  assert.match(capsule, /@keyframes slide \{ from \{ opacity: 0; transform: translateY\(-12px\); \}/);
  assert.match(capsule, /\.pill, \.card \{[^\n]*animation: slide 320ms/);
  // the ring is never spun; the only keyframes are the breathing, the bars and the slide-in
  const keyframes = [...capsule.matchAll(/@keyframes (\w+)/g)].map((m) => m[1]).sort();
  assert.deepEqual(keyframes, ["bar", "breathe", "slide"]);
  assert.doesNotMatch(capsule, /rotate\(/);
});

test("under prefers-reduced-motion both pages are steady lights", () => {
  assert.match(glow, /@media \(prefers-reduced-motion: reduce\) \{\r?\n\s*\.on \.glow, \.on \.hair, \.mark \.arc, \.mark\.lock, \.chip \{ animation: none; \}/);
  assert.match(glow, /if \(reduced\.matches\) \{ place\(mark, fx2, fy2\)/, "a drag's ring goes straight to the far end");
  assert.match(capsule, /@media \(prefers-reduced-motion: reduce\) \{ \.pill, \.card, \.ring, \.bars i \{ animation: none; \} \}/);
});

test("the shell and the pages agree on the words over IPC", () => {
  // the hands' state the shell keeps, in the order the capsule reads it
  for (const key of ["active", "held", "x", "y", "kind", "step", "title", "text", "face", "stop", "take"]) assert.match(main, new RegExp(`^  ${key}: `, "m"), `OverlayHands.${key}`);
  assert.match(main, /sendOverlay\(capsuleWindow, \{ hands, cards \}\)/);
  assert.match(capsule, /const hands = s\.hands && \(s\.hands\.active \|\| s\.hands\.held\) \? s\.hands : null/);
  // the marker's fields the glow draws
  assert.match(main, /marker: marker \? \{ x: marker\.fx, y: marker\.fy, x2: marker\.fx2 \?\? -1, y2: marker\.fy2 \?\? -1, kind: marker\.kind, text: marker\.text, at: marker\.at \} : null/);
  assert.match(glow, /if \(kind === 'drag' && fraction\(m\.x2\) && fraction\(m\.y2\)\) drag\(m\.x, m\.y, m\.x2, m\.y2, text\)/);
  // the capsule sits top-centre at the phone's width and dodges the hands
  assert.match(main, /const CAPSULE_WIDTH = 420;/);
  assert.match(main, /async function capsuleDodge\(marker: Marker\)/);
  assert.match(main, /await capsuleDodge\(marker\);/);
});
