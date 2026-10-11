/** The offline Nanodot mark, matching assets/brand/nanodot-mark.svg. */
import { createElement as h, type ReactNode } from 'react'

export function BrandMark({ size = 72, className }: { size?: number; className?: string }): ReactNode {
  return h('svg', {
    width: size, height: size, viewBox: '0 0 100 100',
    className: `nm-brand-mark${className ? ` ${className}` : ''}`,
    role: 'img', 'aria-label': 'Nanodot', focusable: false,
  },
    h('circle', { cx: 50, cy: 50, r: 37, fill: 'var(--nm-accent)' }),
    h('circle', { cx: 39, cy: 46, r: 3, fill: 'var(--nm-on-primary)' }),
    h('circle', { cx: 61, cy: 46, r: 3, fill: 'var(--nm-on-primary)' }),
    h('path', { d: 'M42 61C46 66 54 66 58 61', fill: 'none', stroke: 'var(--nm-on-primary)', strokeWidth: 2.7, strokeLinecap: 'round' }))
}
