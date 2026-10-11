/**
 * The small things Muse puts around a message: a microphone at the right of
 * the composer, and a "reply with a quote" action beside Copy on the agent's
 * answers. The microphone dictates with the browser's speech recognition
 * where one is available (Chrome has it; the Electron shell has none) and
 * otherwise opens the Dictation page, which says so plainly. Quoting puts
 * the answer's first lines into the composer as a quote block — the harness
 * owns the send, so the quote travels as part of the message text, not as a
 * separate chip.
 */
import { createElement as h, useEffect, useRef, useState, type ReactNode } from 'react'
import type { Translate } from './api.ts'
import { prefillComposer } from './composer.ts'
import { IconMic, IconReply } from './icons.tsx'

interface Recognition extends EventTarget {
  lang: string
  interimResults: boolean
  continuous: boolean
  start(): void
  stop(): void
  abort(): void
  onresult: ((event: { results: ArrayLike<ArrayLike<{ transcript: string }> & { isFinal: boolean }> }) => void) | null
  onerror: ((event: { error: string }) => void) | null
  onend: (() => void) | null
}

function recognitionClass(): (new () => Recognition) | undefined {
  const w = window as unknown as { SpeechRecognition?: new () => Recognition; webkitSpeechRecognition?: new () => Recognition }
  return w.SpeechRecognition ?? w.webkitSpeechRecognition
}

export function makeMicButton(t: Translate, openDictation: () => void) {
  return function MicButton(): ReactNode {
    const [listening, setListening] = useState(false)
    const rec = useRef<Recognition | null>(null)
    useEffect(() => () => rec.current?.abort(), [])
    const stop = () => {
      rec.current?.stop()
      rec.current = null
      setListening(false)
    }
    const start = () => {
      const Ctor = recognitionClass()
      if (!Ctor) { openDictation(); return }
      let heard = false
      const r = new Ctor()
      r.lang = document.documentElement.lang || navigator.language
      r.interimResults = true
      r.continuous = true
      r.onresult = (event) => {
        let text = ''
        for (let i = 0; i < event.results.length; i++) text += event.results[i]![0]!.transcript
        if (text.trim()) { heard = true; prefillComposer(text.trim()) }
      }
      r.onerror = (event) => {
        // no speech service behind the API (Electron): say so in Dictation settings
        if (!heard && (event.error === 'network' || event.error === 'service-not-allowed' || event.error === 'not-allowed')) openDictation()
        stop()
      }
      r.onend = () => { if (rec.current === r) setListening(false) }
      rec.current = r
      try {
        r.start()
        setListening(true)
      } catch {
        openDictation()
      }
    }
    return h('button', {
      type: 'button',
      'aria-live': 'polite',
      className: `nm-mic${listening ? ' nm-live' : ''}`,
      'aria-label': listening ? t('micStop') : t('micStart'),
      title: listening ? t('micStop') : t('micStart'),
      'aria-pressed': listening,
      onMouseDown: (e: { preventDefault(): void }) => e.preventDefault(),
      onClick: () => (listening ? stop() : start()),
    }, h(IconMic, { size: 17 }))
  }
}

const QUOTE_MAX = 280

/** The answer the tail belongs to: the assistant steps between it and the turn before. */
function answerText(from: Element): string {
  const node = from.closest('[data-chat-flow-kind]')
  if (!node) return ''
  const parts: string[] = []
  for (let el = node.previousElementSibling; el; el = el.previousElementSibling) {
    const kind = el.getAttribute('data-chat-flow-kind') ?? ''
    if (kind === 'user' || kind === 'context') break
    if (kind.startsWith('assistant')) parts.unshift((el.textContent ?? '').trim())
  }
  return parts.filter(Boolean).join('\n\n')
}

export function makeQuoteAction(t: Translate) {
  return function QuoteAction(): ReactNode {
    const self = useRef<HTMLButtonElement | null>(null)
    const quote = () => {
      const text = self.current ? answerText(self.current) : ''
      if (!text) return
      const cut = text.length > QUOTE_MAX ? `${text.slice(0, QUOTE_MAX).trimEnd()}…` : text
      prefillComposer(`${cut.split('\n').map((line) => `> ${line}`).join('\n')}\n\n`)
    }
    return h('button', { ref: self, type: 'button', className: 'nm-quote-act', 'aria-label': t('quoteReply'), title: t('quoteReply'), onClick: quote }, h(IconReply, { size: 14 }))
  }
}
