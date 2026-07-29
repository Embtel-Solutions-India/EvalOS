import { Link } from 'react-router-dom'
import type { BoardCard, QuickAction, SlaStatus } from './boardRules'

/**
 * One case as a card: client, service, deadline with its RAG badge, who holds it, and the
 * legal quick actions for the viewer's role.
 *
 * Overdue cards are tinted with the `*-bg` status token rather than shouted at with a
 * heavier border — the badge already carries the state, and a board where three columns
 * are red stops meaning anything.
 */

const SLA_TOKEN: Record<SlaStatus, { fg: string; bg: string; label: string }> = {
  ON_TRACK: { fg: 'var(--status-green)', bg: 'var(--status-green-bg)', label: 'On track' },
  AT_RISK: { fg: 'var(--status-amber)', bg: 'var(--status-amber-bg)', label: 'At risk' },
  OVERDUE: { fg: 'var(--status-red)', bg: 'var(--status-red-bg)', label: 'Overdue' },
}

/** The draft sub-status `ui-context.md` asks the Draft / Report column to show. */
function draftChip(card: BoardCard): string | null {
  if (card.currentStage !== 'DRAFT_GENERATION') return null
  if (card.clientApprovalStatus === 'PENDING') return 'Client review'
  if (card.clientApprovalStatus === 'REVISION_REQUESTED') return 'Revisions asked'
  if (card.pmApprovalStatus === 'PENDING') return 'PM review'
  if (card.pmApprovalStatus === 'RETURNED') return 'Returned to CM'
  return 'Draft in progress'
}

export default function CaseCard({
  card,
  actions,
  busy,
  error,
  onAction,
}: {
  card: BoardCard
  actions: readonly QuickAction[]
  busy: boolean
  error: string | null
  onAction: (action: QuickAction) => void
}) {
  const sla = card.slaStatus ? SLA_TOKEN[card.slaStatus] : null
  const chip = draftChip(card)

  return (
    <article
      className="rounded-lg border p-3"
      style={{
        // Only OVERDUE tints. At-risk gets a badge, not a whole coloured card.
        background: card.slaStatus === 'OVERDUE' ? 'var(--status-red-bg)' : 'var(--bg-surface)',
        borderColor: 'var(--border-default)',
        opacity: busy ? 0.55 : 1,
      }}
    >
      <div className="flex items-baseline justify-between gap-2">
        {/* A real link, not an onClick: middle-click and "open in new tab" are how people
            actually work a board, and a div with a handler breaks both. */}
        <Link
          to={`/cases/${card.id}`}
          className="font-mono text-xs underline-offset-2 hover:underline"
          style={{ color: 'var(--text-muted)' }}
        >
          {card.caseCode}
        </Link>
        {sla && (
          <span
            className="rounded-md px-1.5 py-0.5 text-xs font-semibold"
            style={{ color: sla.fg, background: sla.bg }}
          >
            {sla.label}
          </span>
        )}
      </div>

      <Link to={`/cases/${card.id}`} className="mt-1.5 block">
        <p className="text-sm font-semibold" style={{ color: 'var(--text-primary)' }}>
          {card.clientName ?? 'Unnamed contact'}
        </p>
      </Link>
      <p className="text-xs" style={{ color: 'var(--text-muted)' }}>
        {card.serviceType?.replaceAll('_', ' ').toLowerCase() ?? 'service not set'}
      </p>

      <dl className="font-num mt-2 space-y-0.5 text-xs tabular-nums" style={{ color: 'var(--text-muted)' }}>
        <div className="flex justify-between gap-2">
          <dt>Due</dt>
          <dd>{card.deadline ? new Date(card.deadline).toLocaleDateString() : '—'}</dd>
        </div>
        {/* Null for every role the server does not project it to, so absent means not allowed. */}
        {card.dealValue !== null && (
          <div className="flex justify-between gap-2">
            <dt>Value</dt>
            <dd>{card.dealValue}</dd>
          </div>
        )}
      </dl>

      <div className="mt-2 flex flex-wrap gap-1">
        {chip && <Chip>{chip}</Chip>}
        {card.currentStage === 'EXPERT_SIGNING' && card.expertSignStatus && (
          <Chip>Sign: {card.expertSignStatus.toLowerCase()}</Chip>
        )}
        {card.poolStatus === 'IN_POOL' && <Chip>Unassigned</Chip>}
      </div>

      {error && (
        <p className="mt-2 text-xs" style={{ color: 'var(--status-red)' }}>
          {error}
        </p>
      )}

      {actions.length > 0 && (
        <div className="mt-2 flex flex-wrap gap-1.5 border-t pt-2" style={{ borderColor: 'var(--border-default)' }}>
          {actions.map((action) => (
            <button
              key={action.path}
              type="button"
              disabled={busy}
              onClick={() => onAction(action)}
              className="rounded-md px-2 py-1 text-xs font-medium disabled:opacity-40"
              style={{ background: 'var(--bg-raised)', color: 'var(--accent-primary)' }}
            >
              {action.label}
            </button>
          ))}
        </div>
      )}
    </article>
  )
}

function Chip({ children }: { children: React.ReactNode }) {
  return (
    <span
      className="rounded-md px-1.5 py-0.5 text-xs"
      style={{ background: 'var(--bg-raised)', color: 'var(--text-muted)' }}
    >
      {children}
    </span>
  )
}
