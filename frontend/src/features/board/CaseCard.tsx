import { Link } from 'react-router-dom'
import type { BoardCard, QuickAction, SlaStatus } from './boardRules'
import { formatMoney } from '../../lib/money'

/**
 * One case as a card: client, service, deadline with its RAG badge, who holds it, and the
 * legal quick actions for the viewer's role.
 *
 * Overdue cards are tinted with the `*-bg` status token rather than shouted at with a
 * heavier border — the badge already carries the state, and a board where three columns
 * are red stops meaning anything.
 *
 * Reading order is deliberate: the client's name is the largest thing on the card because
 * that is what somebody says out loud on a call, the case code sits above it in mono as the
 * thing you paste into a search, and the two figures below it are the only numbers, set in
 * tabular figures so a column of cards lines up down the page.
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
  mine,
  error,
  onAction,
}: {
  card: BoardCard
  actions: readonly QuickAction[]
  busy: boolean
  /** The viewer holds this case in one of its three slots. */
  mine: boolean
  error: string | null
  onAction: (action: QuickAction) => void
}) {
  const sla = card.slaStatus ? SLA_TOKEN[card.slaStatus] : null
  const chip = draftChip(card)

  // The column is now a white card, so a white card inside it would disappear: the rest state
  // is the tinted canvas colour, lifting to white on hover. Only OVERDUE tints with a status
  // colour — at-risk gets a badge, not a whole coloured card.
  return (
    <article
      className="p-3 transition-all hover:shadow-(--shadow-card) hover:bg-(--bg-surface)"
      style={{
        background: card.slaStatus === 'OVERDUE' ? 'var(--status-red-bg)' : 'var(--bg-base)',
        borderRadius: 'var(--radius-lg)',
        opacity: busy ? 0.55 : 1,
      }}
    >
      <div className="flex items-center justify-between gap-2">
        {/* A real link, not an onClick: middle-click and "open in new tab" are how people
            actually work a board, and a div with a handler breaks both. */}
        <Link
          to={`/cases/${card.id}`}
          className="truncate font-mono text-[11px] underline-offset-2 hover:underline"
          style={{ color: 'var(--text-muted)' }}
        >
          {card.caseCode}
        </Link>
        {sla && (
          <span
            className="shrink-0 px-2 py-0.5 text-[11px] font-medium"
            style={{ color: sla.fg, background: sla.bg, borderRadius: 'var(--radius-md)' }}
          >
            {sla.label}
          </span>
        )}
      </div>

      <Link to={`/cases/${card.id}`} className="mt-1 block">
        <p className="text-[15px] leading-snug font-semibold" style={{ color: 'var(--text-primary)' }}>
          {card.clientName ?? 'Unnamed contact'}
        </p>
      </Link>
      <p
        className="mt-0.5 text-[10px] font-medium tracking-[0.06em] uppercase"
        style={{ color: 'var(--text-muted)' }}
      >
        {card.serviceType?.replaceAll('_', ' ') ?? 'service not set'}
      </p>

      <dl className="font-num mt-2 flex items-baseline gap-4 text-xs tabular-nums">
        <div>
          <dt className="text-[10px] tracking-wide uppercase" style={{ color: 'var(--text-muted)' }}>
            Due
          </dt>
          <dd className="mt-0.5 font-medium">
            {card.deadline ? new Date(card.deadline).toLocaleDateString('en-US', { month: 'short', day: 'numeric' }) : '—'}
          </dd>
        </div>
        {/* Null for every role the server does not project it to, so absent means not allowed. */}
        {card.dealValue !== null && (
          <div>
            <dt className="text-[10px] tracking-wide uppercase" style={{ color: 'var(--text-muted)' }}>
              Value
            </dt>
            <dd className="mt-0.5 font-medium">{formatMoney(card.dealValue)}</dd>
          </div>
        )}
      </dl>

      <div className="mt-2 flex flex-wrap gap-1">
        {mine && <Chip accent>Yours</Chip>}
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
        <div
          className="mt-2.5 flex flex-wrap gap-1.5 border-t pt-2.5"
          style={{ borderColor: 'var(--border-default)' }}
        >
          {actions.map((action) => (
            <button
              key={action.path}
              type="button"
              disabled={busy}
              onClick={() => onAction(action)}
              // Classes, not inline style: an inline `background` would win over the hover
              // rule and the button would never light up.
              className="rounded-md bg-(--accent-soft) px-3 py-1.5 text-xs font-medium text-(--accent-primary) transition-colors enabled:hover:bg-(--accent-primary) enabled:hover:text-white disabled:opacity-40"
            >
              {action.label}
            </button>
          ))}
        </div>
      )}
    </article>
  )
}

function Chip({ children, accent = false }: { children: React.ReactNode; accent?: boolean }) {
  return (
    <span
      className={
        accent
          ? 'rounded-md bg-(--accent-primary) px-2.5 py-0.5 text-[11px] font-medium text-white'
          : 'rounded-md bg-(--bg-raised) px-2.5 py-0.5 text-[11px] text-(--text-muted)'
      }
    >
      {children}
    </span>
  )
}
