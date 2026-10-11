import { createElement as h, type ReactNode } from 'react'
import type { Translate } from './api.ts'

/** Optional project connection displayed after provider facts have loaded. */
export function NanodotConnection({ t, children }: { t: Translate; children: ReactNode }): ReactNode {
  return h('div', { 'data-testid': 'nm-gateway-connection' },
    h('div', { className: 'nm-way-sub nm-wrap' }, t('gatewayProjectKey')), children)
}
