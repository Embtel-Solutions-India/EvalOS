import { Link } from 'react-router-dom'
import { STAGE_NEXT_ACTION, STAGE_OWNER, type BoardCard, type SlaStatus } from './boardRules'
import { ROLE_LABELS } from '../../lib/session'
import { formatMoney } from '../../lib/money'

/**
 * One case as a card: client, service, deadline with its RAG badge, and who holds it.
 *
 * **It does not act.** The card is six pieces of data and a link, and the transitions live on
 * the case itself (`StageActions`, off `boardRules.actionsFor` — the same table) plus the draft
 * and delivery queues. Quick actions on the card were tried twice, in flow and then as a hover
 * overlay, and both spent the board's scarcest resource — vertical room and a still layout — on
 * controls that are one click away on a screen with space for them.
 *
 * Overdue cards are tinted with the `*-bg` status token rather than shouted at with a
 * heavier border — the badge already carries the state, and a board where three columns
 * are red stops meaning anything.
 *
 * Reading order is deliberate: the client's name is the largest thing on the card because
 * that is what somebody says out loud on a call, the case code sits above it in mono as the
 * thing you paste into a search, and the two figures below it are the only numbers, set in
 * tabular figures so a column of cards lines up down the page.
 *
 * **The whole card is the link.** A stretched `<Link>` covers the card and everything drawn
 * over it is inert to the pointer, so there is no hunting for the one underlined word — and
 * because it is a real anchor, middle-click and "open in new tab" still work. Due and value
 * share one line with their labels inline: the same data as before in about half the height,
 * which is what buys the extra rows of cases on screen.
 */

const SLA_TOKEN: Record<SlaStatus, { fg: string; bg: string; label: string }> = {
  ON_TRACK: { fg: 'var(--status-green)', bg: 'var(--status-green-bg)', label: 'On track' },
  AT_RISK: { fg: 'var(--status-amber)', bg: 'var(--status-amber-bg)', label: 'At risk' },
  OVERDUE: { fg: 'var(--status-red)', bg: 'var(--status-red-bg)', label: 'Overdue' },
}

/** The draft sub-status `ui-context.md` asks the Draft / Report column to show. */
function draftChip(card: BoardCard): string | null {
  if (card.currentStage !== 'DRAFT_IN_PROGRESS') return null
  if (card.clientApprovalStatus === 'PENDING') return 'Client review'
  if (card.clientApprovalStatus === 'REVISION_REQUESTED') return 'Revisions asked'
  if (card.pmApprovalStatus === 'PENDING') return 'PM review'
  if (card.pmApprovalStatus === 'RETURNED') return 'Returned to CM'
  return 'Draft in progress'
}

export default function CaseCard({
  card,
  mine,
}: {
  card: BoardCard
  /** The viewer holds this case in one of its three slots. */
  mine: boolean
}) {
  const sla = card.slaStatus ? SLA_TOKEN[card.slaStatus] : null
  const draft = draftChip(card)
  const chips = [
    mine ? { key: 'mine', label: 'Yours', accent: true } : null,
    draft ? { key: 'draft', label: draft, accent: false } : null,
    card.currentStage === 'EXPERT_SIGNING' && card.expertSignStatus
      ? { key: 'sign', label: `Sign: ${card.expertSignStatus.toLowerCase()}`, accent: false }
      : null,
    card.poolStatus === 'IN_POOL' ? { key: 'pool', label: 'Unassigned', accent: false } : null,
  ].filter((chip) => chip !== null)

  // The column is now a white card, so a white card inside it would disappear: the rest state
  // is the tinted canvas colour, lifting to white on hover. Only OVERDUE tints with a status
  // colour — at-risk gets a badge, not a whole coloured card.
  const owner = STAGE_OWNER[card.currentStage]

  return (
    <article
      // Background in classes, not inline: an inline `background` outranks every class rule,
      // so the hover lift had been silently dead. Overdue keeps its tint through the hover.
      className={`relative p-2.5 transition-all hover:shadow-(--shadow-card) ${
        card.slaStatus === 'OVERDUE' ? 'bg-(--status-red-bg)' : 'bg-(--bg-base) hover:bg-(--bg-surface)'
      }`}
      style={{ borderRadius: 'var(--radius-lg)' }}
    >
      {/* The stretched link. Everything drawn over it is `pointer-events-none`, so the click
          lands here wherever on the card it falls. */}
      <Link
        to={`/cases/${card.id}`}
        aria-label={`Open ${card.caseCode}${card.clientName ? ` — ${card.clientName}` : ''}`}
        className="absolute inset-0 rounded-[inherit] focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-(--accent-primary)"
      />

      <div className="pointer-events-none relative flex items-center justify-between gap-2">
        <span className="truncate font-mono text-[11px]" style={{ color: 'var(--text-muted)' }}>
          {card.caseCode}
        </span>
        {sla && (
          <span
            className="shrink-0 px-1.5 py-px text-[10px] font-medium"
            style={{ color: sla.fg, background: sla.bg, borderRadius: 'var(--radius-md)' }}
          >
            {sla.label}
          </span>
        )}
      </div>

      <p
        className="pointer-events-none relative mt-0.5 truncate text-[14px] leading-snug font-semibold"
        style={{ color: 'var(--text-primary)' }}
      >
        {card.clientName ?? 'Unnamed contact'}
      </p>
      <p
        className="pointer-events-none relative truncate text-[10px] font-medium tracking-[0.06em] uppercase"
        style={{ color: 'var(--text-muted)' }}
      >
        {card.serviceType?.replaceAll('_', ' ') ?? 'service not set'}
      </p>

      {/* One line with the labels inline. The same two figures as before, three rows shorter. */}
      <p className="font-num pointer-events-none relative mt-1 flex items-baseline gap-3 text-[11px] tabular-nums">
        <span>
          <span style={{ color: 'var(--text-muted)' }}>Due </span>
          <span className="font-medium">
            {card.deadline
              ? new Date(card.deadline).toLocaleDateString('en-US', { month: 'short', day: 'numeric' })
              : '—'}
          </span>
        </span>
        {/* Null for every role the server does not project it to, so absent means not allowed. */}
        {card.dealValue !== null && <span className="font-medium">{formatMoney(card.dealValue)}</span>}
      </p>

      {/*
        **Who has it, and what they must do.** This is the point of Unit 31: before it, the owner
        had to be inferred from a stage plus two nullable sub-status columns, and the inference was
        wrong whenever they disagreed. It is a fact of the stage now, read from one table.

        Two lines, not four. The workflow also asked for "last event" and "last updated" on the
        card; both are on the case page, and the board's scarcest resource at 1366px is vertical
        room — the density pass shortened this card deliberately.
      */}
      <p
        className="pointer-events-none relative mt-1 truncate text-[11px]"
        style={{ color: 'var(--text-muted)' }}
      >
        <span className="font-medium" style={{ color: 'var(--text-primary)' }}>
          {owner ? ROLE_LABELS[owner] : 'Nobody'}
        </span>
        {' · '}
        {STAGE_NEXT_ACTION[card.currentStage]}
      </p>

      {/* Only drawn when there is something to draw — an always-present row cost every card
          a blank line of height. */}
      {chips.length > 0 && (
        <div className="pointer-events-none relative mt-1.5 flex flex-wrap gap-1">
          {chips.map((chip) => (
            <Chip key={chip.key} accent={chip.accent}>
              {chip.label}
            </Chip>
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
          ? 'rounded bg-(--accent-primary) px-1.5 py-px text-[10px] font-medium text-white'
          : 'rounded bg-(--bg-raised) px-1.5 py-px text-[10px] text-(--text-muted)'
      }
    >
      {children}
    </span>
  )
}
