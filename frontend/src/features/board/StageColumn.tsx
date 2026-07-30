import type { ReactNode } from 'react'
import type { SlaMix } from './boardRules'

/**
 * One column or lane. Always rendered, even empty — a column that disappears when it has
 * no work makes the pipeline look shorter than it is.
 *
 * **The rail across the top is the board's one instrument.** Each column is capped by a bar
 * split by its cases' SLA mix, so the five columns side by side read as a single line: scan
 * it once and you know not just how much work is in the pipeline but where the risk has
 * collected. RAG is load-bearing in this product (`ui-context.md`), and a count alone cannot
 * say that a quiet column is the one about to breach.
 *
 * The stage number is drawn because the stages really are a sequence and the reader needs to
 * know which end of it they are looking at. Lanes get no number: an exception is not a step.
 */

const BANDS: readonly { key: keyof SlaMix; color: string; label: string }[] = [
  { key: 'overdue', color: 'var(--status-red)', label: 'overdue' },
  { key: 'atRisk', color: 'var(--status-amber)', label: 'at risk' },
  { key: 'onTrack', color: 'var(--status-green)', label: 'on track' },
  { key: 'unknown', color: 'var(--border-default)', label: 'no clock running' },
]

export default function StageColumn({
  label,
  count,
  mix,
  step,
  readOnly = false,
  tone = 'stage',
  children,
}: {
  label: string
  count: number
  mix: SlaMix
  /** Position in the whole pipeline. Omitted for exception lanes, which are not steps. */
  step?: number
  /** This role watches the stage rather than working it — labelled, so the missing
   *  actions read as intentional instead of broken. */
  readOnly?: boolean
  /** Lanes are off the pipeline, and are drawn as held work rather than as a stage. */
  tone?: 'stage' | 'lane'
  children: ReactNode
}) {
  return (
    <section
      className="flex w-72 shrink-0 flex-col overflow-hidden rounded-lg border"
      style={{
        background: tone === 'lane' ? 'var(--bg-surface)' : 'var(--bg-raised)',
        borderColor: 'var(--border-default)',
        boxShadow: 'var(--shadow-card)',
      }}
    >
      <SlaRail mix={mix} count={count} label={label} />

      <header
        className="flex items-baseline justify-between gap-2 border-b px-3 py-2.5"
        style={{ borderColor: 'var(--border-default)' }}
      >
        <h2 className="flex min-w-0 items-baseline gap-1.5 text-sm font-semibold tracking-tight">
          {step !== undefined && (
            <span className="font-mono text-[11px] font-normal" style={{ color: 'var(--text-muted)' }}>
              {step}
            </span>
          )}
          <span className="truncate">{label}</span>
        </h2>
        <span className="flex shrink-0 items-baseline gap-1.5">
          {readOnly && (
            <span
              className="rounded-md px-1.5 py-0.5 text-[10px] font-medium tracking-[0.06em] uppercase"
              style={{ background: 'var(--bg-surface)', color: 'var(--text-muted)' }}
              title="You watch this stage; another role works it"
            >
              watching
            </span>
          )}
          <span className="font-num text-sm font-semibold tabular-nums">{count}</span>
        </span>
      </header>

      <div className="scroll-slim flex min-h-24 flex-col gap-2 overflow-y-auto p-2">
        {count === 0 ? (
          <p className="px-1 py-4 text-xs" style={{ color: 'var(--text-muted)' }}>
            {tone === 'lane' ? 'Nothing held here.' : 'No cases at this stage.'}
          </p>
        ) : (
          children
        )}
      </div>
    </section>
  )
}

/**
 * The column's cases as one 3px bar, red-first so the eye lands on the worst band without
 * hunting. Empty columns keep a hairline so the rail stays continuous across the board.
 */
function SlaRail({ mix, count, label }: { mix: SlaMix; count: number; label: string }) {
  const summary = BANDS.filter((band) => mix[band.key] > 0)
    .map((band) => `${mix[band.key]} ${band.label}`)
    .join(', ')

  return (
    <div
      className="flex h-[3px] w-full"
      style={{ background: 'var(--border-default)' }}
      role="img"
      aria-label={count === 0 ? `${label}: no cases` : `${label}: ${summary}`}
    >
      {BANDS.map((band) => (
        <span key={band.key} style={{ flexGrow: mix[band.key], background: band.color }} />
      ))}
    </div>
  )
}
