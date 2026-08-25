import type { BoardCard, BoardData, DeadlineRisk } from '../board/boardRules'

/**
 * What the two PM queues select out of the board's data, as pure functions.
 *
 * **No new endpoint.** Both screens are different questions asked of `/api/cases/board`, which
 * already returns every card the caller may read with its stage, owner, deadline risk and draft
 * sub-status on it. Adding a `/inbox` and a `/drafts` read would mean two more scope predicates
 * that could drift from the board's — the duplication this codebase has deleted twice.
 *
 * Imports nothing but a type, so the selection rules are testable without a DOM or a server.
 */

/** The inbox's filter presets. `all` is the unfiltered queue, not a missing value. */
export type InboxView = 'all' | 'unassigned' | 'at-risk' | 'overdue' | 'today' | 'week' | 'future'

export const INBOX_VIEWS: readonly { view: InboxView; label: string }[] = [
  { view: 'all', label: 'All open' },
  { view: 'unassigned', label: 'Unassigned' },
  { view: 'at-risk', label: 'At risk' },
  { view: 'overdue', label: 'Overdue' },
  { view: 'today', label: 'Due today' },
  { view: 'week', label: 'Due this week' },
  { view: 'future', label: 'Later' },
]

export function isInboxView(value: string | null): value is InboxView {
  return INBOX_VIEWS.some((entry) => entry.view === value)
}

/** Every card on the board, exception lanes included — a case on hold is still the PM's problem. */
export function allCards(data: BoardData): BoardCard[] {
  return [...Object.values(data.stages).flat(), ...Object.values(data.exceptions).flat()]
}

/**
 * The inbox queue for one preset, always ordered by deadline with undated cases last.
 *
 * The date groupings are computed from `deadline` rather than from {@link DeadlineRisk}, and
 * that is deliberate: "due today" is a calendar question and the risk bands are a business-hours
 * one. A Friday case can be due today and still be red, or due today and amber — folding the two
 * would make the group counts disagree with the tile above them.
 */
export function inboxQueue(data: BoardData, view: InboxView, now: Date = new Date()): BoardCard[] {
  return byDeadline(allCards(data).filter((card) => matches(card, view, now)))
}

function matches(card: BoardCard, view: InboxView, now: Date): boolean {
  switch (view) {
    case 'all':
      return true
    case 'unassigned':
      return card.poolStatus === 'IN_POOL'
    case 'at-risk':
      return card.deadlineRisk === 'OVERDUE' || card.deadlineRisk === 'AT_RISK'
    case 'overdue':
      return card.deadline !== null && new Date(card.deadline) < now
    case 'today':
      return withinDays(card.deadline, now, 0)
    case 'week':
      return withinDays(card.deadline, now, 7)
    case 'future':
      return card.deadline !== null && !withinDays(card.deadline, now, 7)
  }
}

/**
 * Due on or before `days` from the end of today, and not already past.
 *
 * **`setHours` is deliberate and local.** A deadline is a UTC instant, but "due today" is a
 * question about the operator's own day — the same reading `dueBeforeFor` takes for the shell's
 * date filter, so the two controls cannot disagree about where a day ends.
 */
function withinDays(deadline: string | null, now: Date, days: number): boolean {
  if (deadline === null) return false
  const due = new Date(deadline)
  if (due < now) return false
  const edge = new Date(now)
  edge.setHours(23, 59, 59, 999)
  edge.setDate(edge.getDate() + days)
  return due <= edge
}

/**
 * Drafts waiting on this PM, **oldest first** — the opposite of the board's ordering, and the
 * point of the screen. A review queue sorted by deadline lets an old draft with a distant
 * deadline sit forever.
 */
export function draftReviewQueue(data: BoardData): BoardCard[] {
  return data.stages.DRAFT_GENERATION.filter((card) => card.pmApprovalStatus === 'PENDING')
}

function byDeadline(cards: BoardCard[]): BoardCard[] {
  return [...cards].sort((left, right) => {
    if (left.deadline === right.deadline) return 0
    if (left.deadline === null) return 1
    if (right.deadline === null) return -1
    return left.deadline < right.deadline ? -1 : 1
  })
}

/** The RAG token for a deadline band. Null takes the muted rail colour, never green. */
export function riskColor(risk: DeadlineRisk | null): string {
  if (risk === 'OVERDUE') return 'var(--status-red)'
  if (risk === 'AT_RISK') return 'var(--status-amber)'
  if (risk === 'ON_TRACK') return 'var(--status-green)'
  return 'var(--rail-unknown)'
}

/** Status must never be carried by colour alone, so every band has words too. */
export function riskLabel(risk: DeadlineRisk | null): string {
  if (risk === 'OVERDUE') return 'Deadline critical'
  if (risk === 'AT_RISK') return 'Deadline at risk'
  if (risk === 'ON_TRACK') return 'Deadline on track'
  return 'No deadline clock'
}

/**
 * Cases QC has passed, oldest first — the Coordinator's delivery queue.
 *
 * Ordered by deadline like the inbox rather than by age: everything here is ready, so the only
 * question left is which client was promised theirs soonest.
 */
export function deliveryQueue(data: BoardData): BoardCard[] {
  return data.stages.FINAL_DELIVERY
}
