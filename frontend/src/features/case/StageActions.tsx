import type { Role } from '../../lib/session'
import { actionsFor, type BoardCard, type QuickAction } from '../board/boardRules'
import type { CaseDetail } from './caseApi'

/**
 * The sticky header: what the case is, and the legal actions on it.
 *
 * **It sticks below the top bar, offset by `--header-height`.** Two sticky elements at the same
 * `top` is not a layering problem to solve with z-index — whichever loses is simply hidden. The
 * shell's header is the one pinned to the viewport, so everything that sticks under it takes its
 * height as an offset.
 *
 * **The actions come from `boardRules.actionsFor`, not a second table.** Which transitions
 * are legal for a role at a stage does not depend on which screen you are looking at, and
 * two copies would be two answers — the board offering something the detail page hides is a
 * bug nobody would spot until it was in front of a user. Illegal actions are not rendered at
 * all (spec acceptance criterion 2), and the server still decides.
 */

const SLA_TONE: Record<string, { fg: string; bg: string; label: string }> = {
  ON_TRACK: { fg: 'var(--status-green)', bg: 'var(--status-green-bg)', label: 'On track' },
  AT_RISK: { fg: 'var(--status-amber)', bg: 'var(--status-amber-bg)', label: 'At risk' },
  OVERDUE: { fg: 'var(--status-red)', bg: 'var(--status-red-bg)', label: 'Overdue' },
}

function readable(value: string | null | undefined): string {
  return value ? value.replaceAll('_', ' ').toLowerCase() : '—'
}

export default function StageActions({
  detail,
  role,
  busy,
  error,
  onAction,
}: {
  detail: CaseDetail
  role: Role
  busy: boolean
  error: string | null
  onAction: (action: QuickAction) => void
}) {
  const card = detail.summary as BoardCard
  const actions = actionsFor(card, role)
  const sla = card.slaStatus ? SLA_TONE[card.slaStatus] : null
  const inException = card.exceptionState !== 'NONE'

  return (
    <header
      // **Sticks BELOW the top bar, not at the viewport top.** `sticky top-0` put this header
      // and `TopBar` on the same 0 offset, and the top bar wins on z-index (z-20 over z-10) —
      // so the case code and its SLA badge were painted over. The offset is the top bar's own
      // `--header-height`, so the two cannot drift apart.
      //
      // **`-mt-6` is gone, and it was the half that broke this even unscrolled.** It existed to
      // cancel the content area's top padding so the band could bleed to the edge — but
      // `AppShell`'s main is `padding: 0 gutter gutter`, with NO top padding. So it cancelled
      // nothing and simply pulled the header 24px up into the top bar's space, clipping it on a
      // page that had not been scrolled at all.
      //
      // The horizontal bleed now uses `--shell-gutter` instead of `-mx-6`/`px-6`: the gutter is
      // 1.25rem and `-mx-6` is 1.5rem, so the band overhung the content column by 4px a side.
      className="sticky z-10 mb-4 border-b"
      style={{
        top: 'var(--header-height)',
        background: 'var(--bg-surface)',
        borderColor: 'var(--border-default)',
        marginInline: 'calc(var(--shell-gutter) * -1)',
        paddingInline: 'var(--shell-gutter)',
        paddingBlock: '1rem',
      }}
    >
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <div className="flex items-center gap-2">
            <span className="font-mono text-xs" style={{ color: 'var(--text-muted)' }}>
              {card.caseCode}
            </span>
            {sla && (
              <span
                className="rounded-md px-1.5 py-0.5 text-xs font-semibold"
                style={{ color: sla.fg, background: sla.bg }}
              >
                {sla.label}
              </span>
            )}
            {inException && (
              <span
                className="rounded-md px-1.5 py-0.5 text-xs font-semibold"
                style={{ color: 'var(--status-red)', background: 'var(--status-red-bg)' }}
              >
                {readable(card.exceptionState)}
              </span>
            )}
          </div>

          {/* Withheld and unnamed are different facts and must not share a label: the supply-side
              role may not see the client at all, which is not the same as a case with no contact
              linked to it. `maySeeCaseContent` is the server's own answer to which one this is. */}
          <h1 className="mt-1 text-lg font-semibold tracking-tight">
            {detail.maySeeCaseContent ? (
              (detail.clientName ?? 'Unnamed contact')
            ) : (
              <span style={{ color: 'var(--text-muted)' }}>Client withheld</span>
            )}
          </h1>
          <p className="font-num text-sm tabular-nums" style={{ color: 'var(--text-muted)' }}>
            {readable(card.serviceType)} · {readable(card.currentStage)} · due{' '}
            {card.deadline ? new Date(card.deadline).toLocaleDateString() : 'not set'}
          </p>
        </div>

        <div className="flex flex-wrap justify-end gap-1.5">
          {actions.length === 0 ? (
            <span className="text-xs" style={{ color: 'var(--text-muted)' }}>
              No action is yours at this stage.
            </span>
          ) : (
            actions.map((action) => (
              <button
                key={action.path}
                type="button"
                disabled={busy}
                onClick={() => onAction(action)}
                className="rounded-md px-2.5 py-1.5 text-sm font-medium disabled:opacity-40"
                style={{ background: 'var(--bg-raised)', color: 'var(--accent-primary)' }}
              >
                {action.label}
              </button>
            ))
          )}
        </div>
      </div>

      {/* A refused transition explains itself here rather than vanishing. */}
      {error && (
        <p className="mt-2 text-sm" style={{ color: 'var(--status-red)' }}>
          {error}
        </p>
      )}
    </header>
  )
}
