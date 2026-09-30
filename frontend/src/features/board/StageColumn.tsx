import type { ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { CheckCheck, CircleDashed, PauseCircle, Plus, RotateCcw, XCircle } from 'lucide-react'
import type { SlaMix } from './boardRules'

/**
 * One column or lane. Always rendered, even empty — a column that disappears when it has
 * no work makes the pipeline look shorter than it is.
 *
 * **Each stage has its own colour** (`stageColors.ts`, 2026-10-01): an outlined header bar with the
 * stage's icon, name and count in it, and every card in the column tinted with it (`--stage`), so
 * the strip reads as distinct stages at a glance.
 *
 * **The SLA rail is still the board's one instrument.** Under each header runs a bar split by its
 * cases' SLA mix, so the five columns side by side read as a single line: scan
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
  { key: 'unknown', color: 'var(--rail-unknown)', label: 'no clock running' },
]

export default function StageColumn({
  label,
  count,
  mix,
  step,
  readOnly = false,
  tone = 'stage',
  color,
  addHref,
  emptyText,
  subtitle,
  children,
}: {
  label: string
  count: number
  /** Omitted by the opportunity board: a deal has no stage SLA, so it gets no rail. */
  mix?: SlaMix
  /** Position in the whole pipeline. Omitted for exception lanes, which are not steps. */
  step?: number
  /** This role watches the stage rather than working it — labelled, so the missing
   *  actions read as intentional instead of broken. */
  readOnly?: boolean
  /** Lanes are off the pipeline, and are drawn as held work rather than as a stage. */
  tone?: 'stage' | 'lane'
  /** The stage's colour (`stageColors.ts`): the header's outline, icon and text, and — as the
   *  `--stage` custom property — the tint of every card in the column. */
  color: string
  /** Where the header's "+" goes; omitted where the viewer cannot create anything here. */
  addHref?: string
  /** Overrides the case wording when the column holds something other than cases. */
  emptyText?: string
  /** One line under the header — the opportunity board's stage value. */
  subtitle?: ReactNode
  children: ReactNode
}) {
  const Icon = iconFor(label, tone)
  // No panel behind the cards: the header is an outlined bar in the stage's colour and the cards
  // carry the colour as a tint (`--stage`), so each stage reads as its own colour top to bottom.
  return (
    <section
      className="flex w-64 shrink-0 flex-col gap-2"
      style={{ '--stage': color } as React.CSSProperties}
    >
      <header
        className="flex items-center gap-2 border bg-(--bg-surface) px-3 py-2"
        style={{ borderColor: color, color, borderRadius: 'var(--radius-lg)' }}
      >
        <Icon className="h-4 w-4 shrink-0" aria-hidden />
        <h2
          className="min-w-0 truncate text-sm font-semibold tracking-tight"
          title={step !== undefined ? `Stage ${step}: ${label}` : label}
        >
          {label}
        </h2>
        <span className="font-num shrink-0 text-xs font-semibold tabular-nums opacity-80">{count}</span>
        <span className="ml-auto flex shrink-0 items-center gap-2">
          {readOnly && (
            <span
              className="text-[10px] font-medium tracking-[0.06em] uppercase opacity-80"
              title="You watch this stage; another role works it"
            >
              watching
            </span>
          )}
          {addHref && (
            <Link to={addHref} aria-label={`Add to ${label}`} className="grid h-5 w-5 place-items-center rounded hover:bg-(--bg-raised)">
              <Plus className="h-4 w-4" aria-hidden />
            </Link>
          )}
        </span>
      </header>

      {/* The SLA mix stays: RAG is load-bearing here (`ui-context.md`), and a count alone cannot say
          that a quiet column is the one about to breach. */}
      {mix && <SlaRail mix={mix} count={count} label={label} />}
      {subtitle}

      {/* The column's own scroller. Bounded by `--board-column-max` so the header and the SLA
          rail stay pinned while the cards move under them — with the strip scrolling sideways,
          that is the board's second axis. */}
      <div
        className="scroll-slim flex min-h-16 flex-col gap-2.5 overflow-y-auto"
        style={{ maxHeight: 'var(--board-column-max)' }}
      >
        {count === 0 ? (
          <p
            className="border border-dashed px-1 py-5 text-center text-xs"
            style={{ color: 'var(--text-muted)', borderColor: 'var(--border-default)', borderRadius: 'var(--radius-lg)' }}
          >
            {emptyText ?? (tone === 'lane' ? 'Nothing held here.' : 'No cases at this stage.')}
          </p>
        ) : (
          children
        )}
      </div>
    </section>
  )
}

/** Outcome stages get their own glyph; every other stage the same open circle, like the reference. */
function iconFor(label: string, tone: 'stage' | 'lane') {
  if (tone === 'lane') return PauseCircle
  const name = label.trim().toLowerCase()
  if (name === 'won' || name === 'delivered' || name === 'closed') return CheckCheck
  if (name === 'lost' || name === 'disqualified' || name === 'dropped') return XCircle
  if (name.startsWith('refund')) return RotateCcw
  return CircleDashed
}

/**
 * The column's cases as one thin bar, red-first so the eye lands on the worst band without
 * hunting. Empty columns keep a hairline so the rail stays continuous across the board.
 */
function SlaRail({ mix, count, label }: { mix: SlaMix; count: number; label: string }) {
  const summary = BANDS.filter((band) => mix[band.key] > 0)
    .map((band) => `${mix[band.key]} ${band.label}`)
    .join(', ')

  return (
    <div
      className="mx-1 flex h-[3px] overflow-hidden"
      style={{ background: 'var(--rail-unknown)', borderRadius: '999px' }}
      role="img"
      aria-label={count === 0 ? `${label}: no cases` : `${label}: ${summary}`}
    >
      {BANDS.map((band) => (
        <span key={band.key} style={{ flexGrow: mix[band.key], background: band.color }} />
      ))}
    </div>
  )
}
