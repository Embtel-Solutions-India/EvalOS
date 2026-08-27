/**
 * The payout feature's types and its pure decisions.
 *
 * No React and no axios in here on purpose: this is the part `npm run test` covers, and the
 * money arithmetic is exactly the part worth covering.
 *
 * The types mirror `PayoutService`'s records member for member. The server refuses a payment
 * whose amount is not the exact sum of the drafts it settles, so anything here that computes
 * a total has to agree with `BigDecimal` arithmetic on the other side — see {@link sumSelected}.
 */

export type PayoutStatus = 'PENDING' | 'PAID' | 'CONFIRMED' | 'VOIDED'

export type LedgerRow = {
  id: string
  caseId: string
  caseCode: string
  expertId: string
  expertName: string
  /** Null means nobody has decided yet. It is not zero, and it cannot be settled. */
  amount: number | null
  currency: string
  status: PayoutStatus
  dueDate: string
  /** Derived server-side as PENDING past its due date. Never a status value. */
  overdue: boolean
  paymentId: string | null
}

export type ExpertGroup = {
  expertId: string
  expertName: string
  drafts: LedgerRow[]
  subtotal: number
  currency: string
}

export type BatchView = {
  weekStart: string
  weekEnd: string
  groups: ExpertGroup[]
  due: number
  paid: number
  overdue: number
  /** One currency per window — the server refuses a mixed one rather than adding them up. */
  currency: string
}

export type PaymentRow = {
  id: string
  expertId: string
  expertName: string
  amount: number
  currency: string
  method: string
  reference: string
  paidDate: string
  draftCount: number
  confirmed: boolean
}

export type PaymentDetailView = {
  payment: PaymentRow
  notes: string | null
  recordedByName: string
  drafts: LedgerRow[]
}

export type SettleRequest = {
  expertId: string
  payoutIds: string[]
  amount: number
  method: string
  reference: string
  paidDate: string
  notes: string
}

export type PaymentEditRequest = {
  method: string
  reference: string
  notes: string
}

/**
 * What the ticked drafts come to.
 *
 * Summed in cents and divided back, not added as floats. The server compares the posted
 * amount against its own `BigDecimal` sum and refuses anything inexact, so a
 * `0.30000000000000004` here is a settlement the user cannot complete and cannot fix from
 * the screen.
 */
export function sumSelected(drafts: LedgerRow[], selectedIds: Set<string>): number {
  const cents = drafts
    .filter((d) => selectedIds.has(d.id))
    .reduce((total, d) => total + Math.round((d.amount ?? 0) * 100), 0)
  return cents / 100
}

/**
 * Why this selection cannot be settled yet, or null when it can.
 *
 * Mirrors the server's refusals so the button explains itself before the request rather
 * than after it. It is not the gate — `PayoutService.settle` re-checks every one of these,
 * and this only saves a round trip.
 */
export function settleBlocker(drafts: LedgerRow[], selectedIds: Set<string>): string | null {
  const selected = drafts.filter((d) => selectedIds.has(d.id))
  if (selected.length === 0) return 'Tick at least one draft to record a payment.'

  const undecided = selected.find((d) => d.amount === null)
  if (undecided) return `${undecided.caseCode} has no amount yet. Set one before settling it.`

  const notPending = selected.find((d) => d.status !== 'PENDING')
  if (notPending) return `${notPending.caseCode} is already ${notPending.status.toLowerCase()}.`

  const expertIds = new Set(selected.map((d) => d.expertId))
  if (expertIds.size > 1) return 'One transfer pays one expert. Settle each separately.'

  const currencies = new Set(selected.map((d) => d.currency))
  if (currencies.size > 1) return 'These drafts are in different currencies.'

  return null
}

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec']

/**
 * "Aug 24 – Aug 30, 2026".
 *
 * Both arguments are plain ISO days from the server, already resolved in the business's own
 * zone, so nothing here parses a `Date` — constructing one would re-interpret the day in the
 * viewer's timezone and could shift the label by one.
 */
export function weekLabel(weekStart: string, weekEnd: string): string {
  const [, sm, sd] = weekStart.split('-')
  const [ey, em, ed] = weekEnd.split('-')
  return `${MONTHS[Number(sm) - 1]} ${Number(sd)} – ${MONTHS[Number(em) - 1]} ${Number(ed)}, ${ey}`
}

/** The Monday of the week an ISO day falls in, as the server's `weekStart` would compute it. */
export function mondayOf(isoDay: string): string {
  const [y, m, d] = isoDay.split('-').map(Number)
  const utc = new Date(Date.UTC(y, m - 1, d))
  // getUTCDay: 0 = Sunday. Monday-start means Sunday counts back six days, not none.
  const back = (utc.getUTCDay() + 6) % 7
  utc.setUTCDate(utc.getUTCDate() - back)
  return utc.toISOString().slice(0, 10)
}
