import type { Role } from '../../lib/session'
import { actionsFor, type BoardCard, type QuickAction } from '../board/boardRules'
import type { CaseDetail } from './caseApi'

/**
 * The sticky header: what the case is, and the legal actions on it.
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
      className="sticky top-0 z-10 -mx-6 -mt-6 mb-4 border-b px-6 py-4"
      style={{ background: 'var(--bg-surface)', borderColor: 'var(--border-default)' }}
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

          <h1 className="mt-1 text-lg font-semibold tracking-tight">
            {detail.clientName ?? 'Unnamed contact'}
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
