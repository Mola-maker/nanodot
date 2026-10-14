/** Dot uses bundled mood stills. A custom face keeps its own pictures and motion clips. */
import { createElement as h, useEffect, useRef, useState, type CSSProperties, type ReactElement } from 'react'
import { useLive, type Live, type LiveMotion, type LiveProfile } from './live.ts'
import type { Translate } from './api.ts'

export type Mood = 'idle' | 'working' | 'waiting' | 'happy' | 'error'

/** Below this the clip is not worth the bytes: the still shows. */
const CLIP_MIN_PX = 44
/** Clips that did not load: the still, and no second request. */
const noClip = new Set<string>()
const reducedMotion = typeof window !== 'undefined' && typeof window.matchMedia === 'function' && window.matchMedia('(prefers-reduced-motion: reduce)').matches

/** Document-relative, so it resolves under whatever mount served the page. */
export function stillUrl(profile: LiveProfile | undefined, mood: Mood = 'idle'): string {
  if (profile?.avatar === 'face' && profile.faceId) return `nanomuse/assets/face/${profile.faceId}/${mood}.webp`
  return `nanomuse/assets/dragon-${mood}.webp`
}

/**
 * The clip for a mood: none for the error pose (a still says it better) or the emoji. A
 * drawn face has one only when this computer drew it (`motion`), and the URL carries the
 * clip's mtime so a redrawn clip is fetched anew.
 */
export function clipUrl(profile: LiveProfile | undefined, mood: Mood = 'idle', motion?: LiveMotion): string | null {
  if (mood === 'error' || profile?.avatar === 'emoji') return null
  if (profile?.avatar === 'face' && profile.faceId) {
    if (!motion || motion.faceId !== profile.faceId) return null
    const clip = motion.clips[mood]
    return clip ? `nanomuse/avatar/motion/${mood}.mp4?v=${clip.v}` : null
  }
  return null
}

/**
 * One line for the header while the face's clips are drawn — "Animating 2/4…" — or null.
 * With `t`, the locale's wording (`moAnimating`).
 */
export function motionStatus(live: Pick<Live, 'motion'>, t?: Translate): string | null {
  const p = live.motion.progress
  if (!p || !p.running) return null
  const n = Math.min(p.done + 1, p.total)
  return t ? t('moAnimating', { n, total: p.total }) : `Animating ${n}/${p.total}…`
}

/** Whether the document is visible; a hidden window pauses the clips. */
function useVisible(): boolean {
  const [visible, setVisible] = useState(() => typeof document === 'undefined' || document.visibilityState !== 'hidden')
  useEffect(() => {
    if (typeof document === 'undefined') return
    const on = () => setVisible(document.visibilityState !== 'hidden')
    document.addEventListener('visibilitychange', on)
    return () => document.removeEventListener('visibilitychange', on)
  }, [])
  return visible
}

export interface AvatarProps {
  size: number
  profile?: LiveProfile | undefined
  mood?: Mood
  className?: string | undefined
  title?: string
  /** No clip and no motion: lists, pickers, anything that should hold still. */
  still?: boolean
}

/** A round face of the agent at the requested size, moving when it may; an emoji face is drawn, not loaded. */
export function Avatar({ size, profile, mood = 'idle', className, title, still = false }: AvatarProps): ReactElement {
  const name = title ?? profile?.name ?? 'Dot'
  const [, bump] = useState(0)
  const live = useLive()
  const visible = useVisible()
  const video = useRef<HTMLVideoElement | null>(null)
  const candidate = still || reducedMotion || size < CLIP_MIN_PX ? null : clipUrl(profile, mood, live.motion)
  const clip = candidate && !noClip.has(candidate) ? candidate : null
  // the whole face moves when nothing inside it does
  const motion = still || clip || reducedMotion ? '' : mood === 'working' ? ' nm-avatar-working' : mood === 'waiting' ? ' nm-avatar-waiting' : mood === 'idle' ? ' nm-avatar-idle' : ''

  useEffect(() => {
    const el = video.current
    if (!el) return
    if (visible) void el.play().catch(() => undefined)
    else el.pause()
  }, [visible, clip])

  const box: CSSProperties = { width: size, height: size }
  const emoji = profile?.avatar === 'emoji'
  const inner = emoji
    ? h('span', { className: 'nm-avatar-emoji', style: { background: profile.color || 'var(--nm-accent)', fontSize: Math.round(size * 0.58), lineHeight: `${size}px` } }, profile.emoji || '✨')
    : clip
      ? h('video', {
          key: clip,
          ref: video,
          src: clip,
          poster: stillUrl(profile, mood),
          autoPlay: visible,
          loop: true,
          muted: true,
          playsInline: true,
          disablePictureInPicture: true,
          'aria-hidden': true,
          onError: () => {
            noClip.add(clip)
            bump((n) => n + 1)
          },
        })
      : h('img', { src: stillUrl(profile, mood), onError: (event: { currentTarget: HTMLImageElement }) => {
          const fallback = stillUrl(undefined, mood)
          if (event.currentTarget.getAttribute('src') !== fallback) event.currentTarget.src = fallback
        }, alt: '', width: size, height: size, draggable: false })

  return h(
    'span',
    {
      role: 'img',
      'aria-label': name,
      title,
      className: `nm-avatar${motion}${className ? ` ${className}` : ''}`,
      style: box,
    },
    inner,
  )
}

/** The wordmark beside the mark; plain text in the sidebar's own type. */
export function BrandName({ text }: { text: string }): ReactElement {
  return h('span', { style: { fontWeight: 600, letterSpacing: '-0.01em' } }, text)
}
