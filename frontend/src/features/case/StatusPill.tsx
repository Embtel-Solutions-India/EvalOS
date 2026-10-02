import type { ReactNode } from 'react'
import { TONES, type Tone } from './caseUi'

/** A status chip in one of the case screen's five tones (see `caseUi.ts`). */
export function StatusPill({ tone, children, title }: { tone: Tone; children: ReactNode; title?: string }) {
  const { fg, bg } = TONES[tone]
  return (
    <span
      title={title}
      className="inline-flex shrink-0 items-center gap-1 rounded-md px-1.5 py-0.5 text-xs font-semibold whitespace-nowrap [&>svg]:h-3.5 [&>svg]:w-3.5"
      style={{ color: fg, background: bg }}
    >
      {children}
    </span>
  )
}

