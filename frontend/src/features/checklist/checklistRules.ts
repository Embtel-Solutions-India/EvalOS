import type { ServiceType, SlaStatus } from '../board/boardRules'

/**
 * The checklist screen's vocabulary and the two judgements it makes on its own: how long a
 * case has been waiting, and whether it is worth chasing again.
 *
 * Pure data and pure functions, importing nothing but types — the same arrangement as
 * `boardRules.ts`, and for the same reason: `checklistRules.test.ts` can exercise it without
 * a DOM or a server, and these are the two places an off-by-one silently hides work.
 *
 * **Aging is not the SLA.** `SlaCalculator` measures business hours against a stage budget
 * and answers a RAG status; this measures wall-clock hours since the case entered the stage,
 * which is what the spec's 24h/48h bands mean and what a client experiences. A case can be
 * inside its business-hour budget and still have had the client sitting on it all weekend.
 */

export type ChecklistItemStatus = 'REQUIRED' | 'UPLOADED' | 'APPROVED' | 'MISSING' | 'INCORRECT'

export type ChecklistItem = {
  id: string
  label: string
  status: ChecklistItemStatus
  updatedAt: string | null
}

export type ChecklistView = {
  caseId: string
  driveLink: string | null
  items: ChecklistItem[]
  total: number
  complete: number
  /**
   * Whether every document is in. Deliberately not "may mark complete" — the transition also
   * requires the case to be paid and to have a PM, and the server answers 409 with whichever
   * reason applies rather than this client re-deriving the rule.
   */
  checklistSatisfied: boolean
  /** The trail's answer, not the browser's clock — see `needsChase`, which reads it. */
  lastChasedAt: string | null
}

export type ChecklistCard = {
  id: string
  caseCode: string
  clientName: string | null
  serviceType: ServiceType | null
  deadline: string | null
  slaStatus: SlaStatus | null
  exceptionState: string
  stageEnteredAt: string | null
  assignedCoordinator: string | null
  paid: boolean
  total: number
  complete: number
  checklistSatisfied: boolean
  lastChasedAt: string | null
}

/**
 * What each status means to the person reading the row, and which token draws it.
 *
 * `REQUIRED` is deliberately neutral rather than amber: it is the state every document
 * starts in, so colouring it as a problem would paint a brand-new case entirely red before
 * anybody has done anything wrong. Amber is for a document that came and was not good
 * enough; red is for one the client has told us is not coming.
 */
export const STATUS_META: Record<ChecklistItemStatus, { label: string; fg: string; bg: string }> = {
  REQUIRED: { label: 'Awaited', fg: 'var(--text-muted)', bg: 'var(--bg-raised)' },
  UPLOADED: { label: 'Uploaded', fg: 'var(--accent-primary)', bg: 'var(--bg-raised)' },
  APPROVED: { label: 'Approved', fg: 'var(--status-green)', bg: 'var(--status-green-bg)' },
  INCORRECT: { label: 'Incorrect', fg: 'var(--status-amber)', bg: 'var(--status-amber-bg)' },
  MISSING: { label: 'Missing', fg: 'var(--status-red)', bg: 'var(--status-red-bg)' },
}

export const ITEM_STATUSES = Object.keys(STATUS_META) as ChecklistItemStatus[]

const HOUR = 60 * 60 * 1000

/** Wall-clock hours since the case entered Document Collection, or null if it never was stamped. */
export function agingHours(stageEnteredAt: string | null, now: Date = new Date()): number | null {
  if (!stageEnteredAt) return null
  const entered = new Date(stageEnteredAt).getTime()
  if (Number.isNaN(entered)) return null
  return Math.max(0, (now.getTime() - entered) / HOUR)
}

export type AgingBand = 'fresh' | 'warn' | 'late' | 'unknown'

/**
 * The spec's bands: amber past 24 hours, red past 48.
 *
 * An unstamped case is `unknown`, not `fresh`. The same reasoning `slaMix` gives for keeping
 * a separate band — a case we cannot time is not a case that is doing well, and drawing it
 * green would report a stalled row as healthy.
 */
export function agingBand(hours: number | null): AgingBand {
  if (hours === null) return 'unknown'
  if (hours > 48) return 'late'
  if (hours > 24) return 'warn'
  return 'fresh'
}

export const AGING_TOKEN: Record<AgingBand, { fg: string; bg: string }> = {
  fresh: { fg: 'var(--status-green)', bg: 'var(--status-green-bg)' },
  warn: { fg: 'var(--status-amber)', bg: 'var(--status-amber-bg)' },
  late: { fg: 'var(--status-red)', bg: 'var(--status-red-bg)' },
  unknown: { fg: 'var(--text-muted)', bg: 'var(--bg-raised)' },
}

/** "31h" up to two days, then whole days — nobody triages on the hour after that. */
export function agingLabel(hours: number | null): string {
  if (hours === null) return 'not started'
  if (hours < 48) return `${Math.floor(hours)}h`
  return `${Math.floor(hours / 24)}d`
}

/**
 * Whether this case belongs in the pending-docs queue (spec deliverable 5).
 *
 * Three conditions, all of them necessary:
 * - the documents are not all in — a satisfied case needs pushing to the PM, not chasing;
 * - it has been waiting more than 24 hours, so a case that arrived this morning is left alone;
 * - and nothing has been sent in the last 24 hours, so the queue empties when the Coordinator
 *   works it instead of nagging them about a client who was contacted an hour ago.
 *
 * The last one is why "never chased" and "chased two days ago" are the same answer here: what
 * matters is whether a chase is *due*, not whether one has ever happened.
 */
export function needsChase(card: ChecklistCard, now: Date = new Date()): boolean {
  if (card.checklistSatisfied) return false
  const waiting = agingHours(card.stageEnteredAt, now)
  if (waiting === null || waiting <= 24) return false
  const sinceChase = agingHours(card.lastChasedAt, now)
  return sinceChase === null || sinceChase > 24
}

/**
 * The queue first, everything else after, each half keeping the server's order.
 *
 * The server sorts by longest wait; this only splits, so a case never jumps the queue by
 * being re-sorted twice on two different keys.
 */
export function splitByChase(
  cards: readonly ChecklistCard[],
  now: Date = new Date(),
): { chase: ChecklistCard[]; rest: ChecklistCard[] } {
  const chase: ChecklistCard[] = []
  const rest: ChecklistCard[] = []
  for (const card of cards) (needsChase(card, now) ? chase : rest).push(card)
  return { chase, rest }
}

/** How full the completeness bar is drawn, 0–100. An empty checklist is 0, never 100. */
export function completionPercent(complete: number, total: number): number {
  if (total <= 0) return 0
  return Math.round((complete / total) * 100)
}

/**
 * The board's copy of a case, brought back in line with a checklist the panel just re-read.
 *
 * The board draws four things from its own copy rather than from the open panel — the
 * completeness bar, the "all documents in" chip, the header counts, and `needsChase` — so a
 * write that refreshes only the panel leaves all four stale. Every write answers the whole
 * refreshed checklist, which is what makes this a copy rather than a recomputation.
 *
 * Applied to *every* write, not just the chase: patching one of the three left a status change
 * showing the old fraction and a finished case still sitting under "Due a chase" until the next
 * full reload.
 */
export function applyChecklistToCard(card: ChecklistCard, view: ChecklistView): ChecklistCard {
  return {
    ...card,
    total: view.total,
    complete: view.complete,
    checklistSatisfied: view.checklistSatisfied,
    lastChasedAt: view.lastChasedAt,
  }
}
