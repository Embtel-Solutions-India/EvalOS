import { Link } from 'react-router-dom'
import { CalendarDays } from 'lucide-react'
import { STAGE_NEXT_ACTION, STAGE_OWNER, cardDate, type BoardCard, type SlaStatus } from './boardRules'
import { ROLE_LABELS } from '../../lib/session'
import { formatMoney } from '../../lib/money'

/**
 * One case as a card: client, service, deadline with its RAG badge, and who holds it.
 *
 * **It carries no buttons.** Transitions live on the case itself (`CaseHeader`, off
 * `boardRules.actionsFor`) and on the board as a drag (`BoardView`, 2026-10-01): `draggable` is set
 * only when this role has a stage move from here. Buttons on the card were tried twice and spent
 * the board's scarcest resource — vertical room and a still layout.
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
/** The SLA word on white needs deeper shades than the status tokens to reach 4.5:1 at 11px. */
const SLA_TEXT: Record<SlaStatus, string> = { ON_TRACK: '#15803d', AT_RISK: '#b45309', OVERDUE: '#dc2626' }

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
  draggable = false,
}: {
  card: BoardCard
  /** The viewer holds this case in one of its three slots. */
  mine: boolean
  /** This role has a stage move from here, so the board lets the card be dragged. */
  draggable?: boolean
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

  // A white card on the column's tinted panel (2026-10-01). OVERDUE is the one state drawn on the
  // card itself, as a red border, so the stage colours around it cannot drown it out; at-risk gets
  // a badge, not a coloured card.
  const owner = STAGE_OWNER[card.currentStage]

  const due = card.deadline ? cardDate(card.deadline) : null

  // Tinted with the column's colour (`--stage`, set by `StageColumn`), the reference's layout: a white
  // tag pill and the case code, the client and what happens next, then a ruled footer with the due
  // date and the SLA. The SLA is bars plus its word, so red/amber/green is never colour alone.
  return (
    <article
      data-case={card.id}
      draggable={draggable}
      className={`relative p-3 transition-shadow hover:shadow-(--shadow-pop) ${draggable ? 'cursor-grab active:cursor-grabbing' : ''}`}
      style={{
        borderRadius: 'var(--radius-xl)',
        background:
          'linear-gradient(180deg, color-mix(in srgb, var(--stage) 20%, #ffffff), color-mix(in srgb, var(--stage) 7%, #ffffff))',
      }}
    >
      {/* The stretched link. Everything drawn over it is `pointer-events-none`, so the click
          lands here wherever on the card it falls. */}
      {/* Not draggable itself, so a drag picks up the card rather than the link's URL. */}
      <Link
        to={`/cases/${card.id}`}
        draggable={false}
        aria-label={`Open ${card.caseCode}${card.clientName ? ` — ${card.clientName}` : ''}`}
        className="absolute inset-0 rounded-[inherit] focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-(--accent-primary)"
      />

      <div className="pointer-events-none relative flex items-center justify-between gap-2">
        <span className="min-w-0 truncate rounded-full bg-white/85 px-2.5 py-0.5 text-[11px] font-medium" style={{ color: 'var(--text-primary)' }}>
          {serviceLabel(card.serviceType)}
        </span>
        <span className="shrink-0 font-mono text-[11px]" style={{ color: 'var(--card-text-muted)' }}>
          {card.caseCode}
        </span>
      </div>

      <p className="pointer-events-none relative mt-2 truncate text-[15px] leading-snug font-semibold" style={{ color: 'var(--text-primary)' }}>
        {card.clientName ?? 'Unnamed contact'}
      </p>
      {/*
        **Who has it, and what they must do** (Unit 31): a fact of the stage, read from one table,
        never inferred from sub-status columns.
      */}
      <p className="pointer-events-none relative truncate text-xs" style={{ color: 'var(--card-text-muted)' }}>
        {owner ? ROLE_LABELS[owner] : 'Nobody'} · {STAGE_NEXT_ACTION[card.currentStage]}
      </p>

      {/* Null for every role the server does not project it to, so absent means not allowed. */}
      {(card.dealValue !== null || chips.length > 0) && (
        <div className="pointer-events-none relative mt-2 flex flex-wrap items-center gap-1.5">
          {card.dealValue !== null && (
            <span className="font-num text-[13px] font-semibold tabular-nums">{formatMoney(card.dealValue)}</span>
          )}
          {chips.map((chip) => (
            <Tag key={chip.key} accent={chip.accent}>
              {chip.label}
            </Tag>
          ))}
        </div>
      )}

      <div
        className="pointer-events-none relative mt-2.5 flex items-center justify-between gap-2 border-t pt-2.5"
        style={{ borderColor: 'color-mix(in srgb, var(--stage) 18%, var(--border-default))' }}
      >
        <span className="font-num flex min-w-0 items-center gap-1.5 text-xs tabular-nums" style={{ color: 'var(--text-primary)' }}>
          <CalendarDays className="h-3.5 w-3.5 shrink-0" style={{ color: 'var(--card-text-muted)' }} aria-hidden />
          <span className="truncate">{due ? `Due ${due}` : 'No due date'}</span>
        </span>
        {sla && (
          <span className="flex shrink-0 items-center gap-1.5 rounded-md bg-white px-2 py-1 shadow-(--shadow-card)">
            <SlaBars status={card.slaStatus!} />
            <span className="text-[11px] font-medium" style={{ color: SLA_TEXT[card.slaStatus!] }}>
              {sla.label}
            </span>
          </span>
        )}
      </div>
    </article>
  )
}

/** Words the service names spell as acronyms: `RFE_RESPONSE` is "RFE response", not "Rfe response". */
const ACRONYMS = new Set(['rfe', 'perm', 'eb1a', 'eb2', 'niw', 'o1', 'h1b', 'uscis'])

/** `EXPERT_OPINION_LETTER` → "Expert opinion letter". */
function serviceLabel(serviceType: string | null): string {
  if (!serviceType) return 'Service not set'
  return serviceType
    .toLowerCase()
    .split('_')
    .map((word, index) => (ACRONYMS.has(word) ? word.toUpperCase() : index === 0 ? word[0].toUpperCase() + word.slice(1) : word))
    .join(' ')
}

/** Three rising bars, filled by urgency and coloured by the SLA: the reference's priority glyph. */
function SlaBars({ status }: { status: SlaStatus }) {
  const filled = status === 'OVERDUE' ? 3 : status === 'AT_RISK' ? 2 : 1
  const color = SLA_TOKEN[status].fg
  return (
    <span
      aria-hidden
      className="flex h-3 items-end gap-[2px]"
    >
      {[4, 7, 10].map((height, index) => (
        <span
          key={height}
          className="w-[3px] rounded-sm"
          style={{ height, background: index < filled ? color : 'var(--border-default)' }}
        />
      ))}
    </span>
  )
}

/** A small white tag on the tinted card. `accent` fills it: "Yours". */
function Tag({ children, accent = false, color }: { children: React.ReactNode; accent?: boolean; color?: string }) {
  return (
    <span
      className="border px-2 py-0.5 text-[11px] font-medium"
      style={
        accent
          ? { background: 'var(--accent-primary)', borderColor: 'var(--accent-primary)', color: '#ffffff', borderRadius: 'var(--radius-md)' }
          : { background: '#ffffff', borderColor: 'transparent', color: color ?? 'var(--text-primary)', borderRadius: 'var(--radius-md)' }
      }
    >
      {children}
    </span>
  )
}
