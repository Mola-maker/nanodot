import { useEffect, useState } from "react";
import { dragonClipUrl, dragonUrl, isDragon, studioClipUrl, studioUrl } from "../avatars";
import { useMood } from "../mood";
import type { Profile, Status } from "../types";
import { cx } from "../util";

/** Dot uses offline mood stills. A studio face may play its own clips; emoji preferences are retained. */

/** Below this the clip is not worth the bytes: the still shows. */
const CLIP_MIN_PX = 44;
/** Clips that did not load (a studio face without them): the still, and no second request. */
const noClip = new Set<string>();
const reducedMotion = typeof window !== "undefined" && typeof window.matchMedia === "function" && window.matchMedia("(prefers-reduced-motion: reduce)").matches;

export function Avatar({
  profile,
  status,
  size = 40,
  onClick,
  still = false,
  className,
}: {
  profile: Profile | null;
  status?: Status;
  size?: number;
  onClick?: () => void;
  /** no idle animation (lists, pickers) */
  still?: boolean;
  className?: string;
}) {
  const [wiggle, setWiggle] = useState(false);
  const [, bump] = useState(0);
  useEffect(() => {
    if (!wiggle) return;
    const id = window.setTimeout(() => setWiggle(false), 900);
    return () => window.clearTimeout(id);
  }, [wiggle]);

  const mood = useMood(status);
  const state = status?.state ?? "idle";
  const color = profile?.color ?? "var(--om-accent)";
  // the dragon is the default: no profile yet, or a profile that names it (or a retired face)
  const dragon = !profile || isDragon(profile.avatar);
  // a face from the studio: anything else that is not the emoji
  const studio = !dragon && profile?.avatar ? profile.avatar : null;
  const pose = wiggle ? "happy" : still ? "idle" : mood;
  // the clip for this pose, when one may exist and is wanted here
  const clipCandidate = still || reducedMotion || size < CLIP_MIN_PX ? null : dragon ? dragonClipUrl(pose) : studio ? studioClipUrl(studio, pose) : null;
  const clip = clipCandidate && !noClip.has(clipCandidate) ? clipCandidate : null;
  const motion = wiggle
    ? "avatar-wiggle"
    : still || clip
      ? ""
      : state === "working"
        ? "avatar-working"
        : state === "waiting"
          ? "avatar-waiting"
          : "avatar-idle";
  const stillUrl = dragon ? dragonUrl(pose) : studio ? studioUrl(studio, pose) : "";

  const label = `${profile?.name ?? "Dot"} avatar`;
  const box = cx("relative inline-block shrink-0 select-none rounded-full", className);
  const face = (
    <>
      <span
        className={cx("block h-full w-full overflow-hidden rounded-full will-change-transform", motion)}
        style={dragon || studio ? { background: "#f1efeb" } : { background: `linear-gradient(135deg, ${color}, color-mix(in srgb, ${color} 60%, #ffffff))` }}
      >
        {clip ? (
          <video
            key={clip}
            src={clip}
            poster={stillUrl}
            autoPlay
            loop
            muted
            playsInline
            disablePictureInPicture
            onError={() => {
              noClip.add(clip);
              bump((n) => n + 1);
            }}
            className="h-full w-full object-cover"
          />
        ) : dragon || studio ? (
          <img src={stillUrl} onError={(event) => {
            const fallback = dragonUrl(pose);
            if (event.currentTarget.getAttribute("src") !== fallback) event.currentTarget.src = fallback;
          }} alt="" draggable={false} className="h-full w-full object-cover" />
        ) : (
          <span className="flex h-full w-full items-center justify-center leading-none drop-shadow-sm" style={{ fontSize: size * 0.5 }}>
            {profile?.emoji ?? "✨"}
          </span>
        )}
      </span>
      {status && state !== "idle" && (
        <span
          className={cx(
            "absolute -bottom-0.5 -right-0.5 rounded-full border-2 border-bg",
            state === "working" ? "bg-amber-400" : "bg-rose-500",
          )}
          style={{ width: Math.max(10, size * 0.28), height: Math.max(10, size * 0.28) }}
        />
      )}
    </>
  );

  // A button of its own only when it does something; inside another button (the chat header)
  // it is plain markup that still wiggles when tapped.
  if (onClick) {
    return (
      <button
        type="button"
        onClick={() => {
          setWiggle(true);
          onClick();
        }}
        aria-label={label}
        className={cx(box, "transition active:scale-95")}
        style={{ width: size, height: size }}
      >
        {face}
      </button>
    );
  }
  return (
    <span role="img" aria-label={label} onClick={() => setWiggle(true)} className={box} style={{ width: size, height: size }}>
      {face}
    </span>
  );
}
