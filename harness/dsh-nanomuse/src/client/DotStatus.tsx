import { createElement as h, type ReactNode } from 'react'

/** The owner supplies state and localized text; announcements never require motion. */
export function DotStatus({ state, message, onRetry, retryLabel = '↻' }: { state: 'loading' | 'empty' | 'error' | 'ready'; message: string; onRetry?: (() => void) | undefined; retryLabel?: string | undefined }): ReactNode {
  return h('div', { className: `nm-dot-status nm-dot-${state}`, role: state === 'error' ? 'alert' : 'status', 'aria-live': state === 'error' ? 'assertive' : 'polite', 'aria-busy': state === 'loading' },
    h('span', { 'aria-hidden': true }, state === 'error' ? '!' : state === 'ready' ? '✓' : '●'),
    h('span', null, message),
    onRetry ? h('button', { type: 'button', className: 'nm-pill nm-pill-ghost', onClick: onRetry }, retryLabel) : null)
}
