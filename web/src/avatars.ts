/** Offline Dot stills, custom studio assets, or a chosen emoji. Legacy built-in IDs wear Dot. */

import { fileUrl } from "./api";

/** The public built-in avatar ID. Paths keep their legacy names for older clients. */
export const DOT = "dot-v1";
/** Kept as a source alias for existing picker imports. */
export const DRAGON = DOT;
export type DragonMood = "idle" | "working" | "waiting" | "happy" | "error";

/**
 * Faces from before 0.1.23 — the red panda drawn live and the six knitted dolls — are gone;
 * a profile still naming one wears Dot.
 */
const RETIRED = new Set(["panda", "sunny", "moss", "sky", "fox", "bolt", "plum"]);

export function isDragon(id: string | undefined | null): boolean {
  return id == null || id === DOT || id === "dragon" || RETIRED.has(id);
}

/**
 * A face from the avatar studio: `profile.avatar` is its id, the stills live in the workspace
 * under `avatar/<id>/` (idle · working · waiting · happy · error, webp) and come through the
 * files API — the same five moods Dot has.
 */
export function studioUrl(id: string, mood: string): string {
  const still: DragonMood = mood === "working" || mood === "waiting" || mood === "happy" || mood === "error" ? mood : "idle";
  return fileUrl(`avatar/${id}/${still}.webp`);
}

export function dragonUrl(mood: string): string {
  const still: DragonMood = mood === "working" || mood === "waiting" || mood === "happy" || mood === "error" ? mood : "idle";
  return `/avatars/dragon-${still}.webp`;
}

/** The moods that move; "error" is a still, on the phone too. */
export type ClipMood = "idle" | "working" | "waiting" | "happy";

function clipMood(mood: string): ClipMood {
  return mood === "working" || mood === "waiting" || mood === "happy" ? mood : "idle";
}

/** Built-in Dot has no video clip; custom studio motion remains available. */
export function dragonClipUrl(mood: string): string | null {
  void mood;
  return null;
}

/**
 * A studio face's clip for a mood, `avatar/<id>/<mood>.mp4` through the files API. The studio
 * makes them after the stills when its endpoint has a video model; a face may have none, in
 * which case the request fails and the still stays (see Avatar).
 */
export function studioClipUrl(id: string, mood: string): string | null {
  if (mood === "error") return null;
  return fileUrl(`avatar/${id}/${clipMood(mood)}.mp4`);
}
