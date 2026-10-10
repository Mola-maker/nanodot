/**
 * "Share my avatar", the phone's sheet (android …/ui/avatar/AvatarShareSheet.kt) on a
 * desktop: two cards drawn on a canvas — the face with its name and a line about
 * Nanodot, and the face alone — saved as a PNG or copied to the clipboard, since
 * a computer has no system share sheet to hand them to.
 */
import { createElement as h, useEffect, useRef, useState, type ReactNode } from 'react'
import type { Translate } from './api.ts'
import { stillUrl } from './Avatar.tsx'
import { useLive, type LiveProfile } from './live.ts'
import { Sheet } from './ui.tsx'

type Card = 'bubble' | 'face'

const SIZE = 1080

export function AvatarShareSheet({ t, name, onClose }: { t: Translate; name: string; onClose(): void }): ReactNode {
  const live = useLive()
  const [card, setCard] = useState<Card>('bubble')
  const [state, setState] = useState<'idle' | 'busy' | 'saved' | 'copied' | 'failed'>('idle')
  const canvas = useRef<HTMLCanvasElement>(null)
  useEffect(() => {
    let alive = true
    const el = canvas.current
    if (!el) return
    void draw(el, card, live.profile, name, t).then(() => { if (alive) setState('idle') }).catch(() => { if (alive) setState('failed') })
    return () => { alive = false }
  }, [card, live.profile, name, t])

  const blob = (): Promise<Blob> => new Promise((ok, no) => canvas.current?.toBlob((b) => (b ? ok(b) : no(new Error('no picture'))), 'image/png'))
  const save = async () => {
    setState('busy')
    try {
      const url = URL.createObjectURL(await blob())
      const a = document.createElement('a')
      a.href = url
      a.download = `${name || 'Dot'}-avatar.png`
      a.click()
      window.setTimeout(() => URL.revokeObjectURL(url), 5000)
      setState('saved')
    } catch {
      setState('failed')
    }
  }
  const copy = async () => {
    setState('busy')
    try {
      const item = new ClipboardItem({ 'image/png': await blob() })
      await navigator.clipboard.write([item])
      setState('copied')
    } catch {
      setState('failed')
    }
  }
  return h(Sheet, { title: t('acShareTitle'), onClose, closeLabel: t('close'), wide: false,
    footer: h('div', { className: 'nm-ac-actions' },
      h('button', { type: 'button', className: 'nm-pill nm-pill-sm', disabled: state === 'busy', onClick: () => void save() }, t('acShareSave')),
      h('button', { type: 'button', className: 'nm-pill nm-pill-ghost nm-pill-sm', disabled: state === 'busy', onClick: () => void copy() }, t('acShareCopy')),
      h('span', { className: 'nm-fine', 'aria-live': 'polite' }, state === 'saved' ? t('acShareSaved') : state === 'copied' ? t('acShareCopied') : state === 'failed' ? t('acShareFailed') : '')) },
    h('p', { className: 'nm-fine' }, t('acShareSubtitle')),
    h('div', { className: 'nm-seg', role: 'radiogroup' },
      h('button', { type: 'button', role: 'radio', 'aria-checked': card === 'bubble', className: `nm-seg-btn nm-seg-text${card === 'bubble' ? ' nm-active' : ''}`, onClick: () => setCard('bubble') }, t('acShareCardBubble')),
      h('button', { type: 'button', role: 'radio', 'aria-checked': card === 'face', className: `nm-seg-btn nm-seg-text${card === 'face' ? ' nm-active' : ''}`, onClick: () => setCard('face') }, t('acShareCardFace'))),
    h('canvas', { ref: canvas, width: SIZE, height: SIZE, className: 'nm-ac-share-canvas', 'aria-label': t('acShareTitle') }))
}

async function faceImage(profile: LiveProfile): Promise<HTMLImageElement | null> {
  if (profile.avatar === 'emoji') return null
  const img = new Image()
  img.src = stillUrl(profile, 'happy')
  try {
    await img.decode()
    return img
  } catch {
    return null
  }
}

async function draw(canvas: HTMLCanvasElement, card: Card, profile: LiveProfile, name: string, t: Translate): Promise<void> {
  const ctx = canvas.getContext('2d')
  if (!ctx) throw new Error('no canvas')
  const g = ctx.createLinearGradient(0, 0, SIZE, SIZE)
  g.addColorStop(0, profile.avatar === 'emoji' && profile.color ? profile.color : '#1d6fe0')
  g.addColorStop(1, '#8a4ee8')
  ctx.fillStyle = g
  ctx.fillRect(0, 0, SIZE, SIZE)
  const img = await faceImage(profile)
  const faceSize = card === 'face' ? 720 : 520
  const faceY = card === 'face' ? (SIZE - faceSize) / 2 : 150
  const cx = SIZE / 2
  // a soft white disc behind the face
  ctx.fillStyle = 'rgba(255,255,255,0.92)'
  ctx.beginPath()
  ctx.arc(cx, faceY + faceSize / 2, faceSize / 2 + 24, 0, Math.PI * 2)
  ctx.fill()
  if (img) {
    ctx.save()
    ctx.beginPath()
    ctx.arc(cx, faceY + faceSize / 2, faceSize / 2, 0, Math.PI * 2)
    ctx.clip()
    ctx.drawImage(img, cx - faceSize / 2, faceY, faceSize, faceSize)
    ctx.restore()
  } else {
    ctx.font = `${Math.round(faceSize * 0.6)}px sans-serif`
    ctx.textAlign = 'center'
    ctx.textBaseline = 'middle'
    ctx.fillStyle = '#000'
    ctx.fillText(profile.emoji || '✨', cx, faceY + faceSize / 2 + 10)
  }
  ctx.textAlign = 'center'
  ctx.fillStyle = '#fff'
  if (card === 'bubble') {
    wrap(ctx, t('acShareBubble', { name }), cx, faceY + faceSize + 110, SIZE - 200, 'bold 44px sans-serif', 58)
    ctx.font = '30px sans-serif'
    ctx.globalAlpha = 0.85
    ctx.fillText(t('acShareTagline'), cx, SIZE - 80)
    ctx.globalAlpha = 1
  } else {
    ctx.font = 'bold 52px sans-serif'
    ctx.fillText(name, cx, SIZE - 120)
    ctx.font = '28px sans-serif'
    ctx.globalAlpha = 0.85
    ctx.fillText(t('acShareTagline'), cx, SIZE - 60)
    ctx.globalAlpha = 1
  }
}

function wrap(ctx: CanvasRenderingContext2D, text: string, x: number, y: number, maxWidth: number, font: string, lineHeight: number): void {
  ctx.font = font
  const words = /[\u3400-\u9fff]/.test(text) ? [...text] : text.split(' ')
  const joiner = /[\u3400-\u9fff]/.test(text) ? '' : ' '
  let line = ''
  let yy = y
  for (const word of words) {
    const test = line ? line + joiner + word : word
    if (ctx.measureText(test).width > maxWidth && line) {
      ctx.fillText(line, x, yy)
      line = word
      yy += lineHeight
    } else {
      line = test
    }
  }
  if (line) ctx.fillText(line, x, yy)
}
