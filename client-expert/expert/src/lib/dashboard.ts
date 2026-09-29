/**
 * The expert dashboard's and payouts page's figures, from the two lists the portal already reads
 * (`/expert/cases`, `/expert/payouts`). Pure functions, no DOM — `dashboard.test.ts` covers them.
 *
 * **Every figure is derived from a field the server sent; nothing is estimated.** A case is dated by
 * `signedAt` (its signed letter), a payout by `settledOn` (paid) or `dueDate` (still owed). Money is
 * shown in one currency — the one most of this expert's payouts are in — because currencies do not
 * add up; the rest are counted and named, never converted.
 */
import type { ExpertCaseSummary, ExpertPayoutRow } from './expertCase'

// --- date ranges -------------------------------------------------------------------------------

export type RangeKey = 'this-month' | 'last-month' | 'last-3-months' | 'this-year' | 'all'

export const RANGES: { key: RangeKey; label: string }[] = [
  { key: 'this-month', label: 'This month' },
  { key: 'last-month', label: 'Last month' },
  { key: 'last-3-months', label: 'Last 3 months' },
  { key: 'this-year', label: 'This year' },
  { key: 'all', label: 'All time' },
]

/** `[from, to)` in local time; both null for "all time". */
export type Range = { from: Date | null; to: Date | null }

export function rangeOf(key: RangeKey, now: Date = new Date()): Range {
  const y = now.getFullYear()
  const m = now.getMonth()
  switch (key) {
    case 'this-month':
      return { from: new Date(y, m, 1), to: new Date(y, m + 1, 1) }
    case 'last-month':
      return { from: new Date(y, m - 1, 1), to: new Date(y, m, 1) }
    case 'last-3-months':
      return { from: new Date(y, m - 2, 1), to: new Date(y, m + 1, 1) }
    case 'this-year':
      return { from: new Date(y, 0, 1), to: new Date(y + 1, 0, 1) }
    case 'all':
      return { from: null, to: null }
  }
}

/** The same number of calendar months just before `range`, for "from last month". Null for all time. */
export function previousRange(range: Range): Range | null {
  if (!range.from || !range.to) return null
  const months = (range.to.getFullYear() - range.from.getFullYear()) * 12 + range.to.getMonth() - range.from.getMonth()
  return { from: new Date(range.from.getFullYear(), range.from.getMonth() - months, 1), to: range.from }
}

export function inRange(iso: string | null, range: Range): boolean {
  if (!iso) return false
  const t = new Date(iso).getTime()
  return (!range.from || t >= range.from.getTime()) && (!range.to || t < range.to.getTime())
}

/** "Sep 1, 2026 – Sep 30, 2026", or "All time". */
export function rangeLabel(range: Range): string {
  if (!range.from || !range.to) return 'All time'
  const last = new Date(range.to.getTime() - 1)
  const f = (d: Date) => d.toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric' })
  return `${f(range.from)} – ${f(last)}`
}

// --- cases -------------------------------------------------------------------------------------

export type CaseBucket = 'COMPLETED' | 'IN_PROGRESS' | 'NOT_STARTED'

/**
 * Where a case sits for this expert. Signed is completed; an open offer they have not answered is
 * not started; anything else they hold is in progress. A reassigned case is no longer theirs and
 * counts nowhere.
 */
export function bucketOf(c: ExpertCaseSummary): CaseBucket | null {
  if (c.signStatus === 'REASSIGNED') return null
  if (c.signStatus === 'SIGNED' || c.signedAt) return 'COMPLETED'
  if (c.offered) return 'NOT_STARTED'
  return 'IN_PROGRESS'
}

export function caseCounts(cases: readonly ExpertCaseSummary[]): Record<CaseBucket, number> & { total: number } {
  const counts = { COMPLETED: 0, IN_PROGRESS: 0, NOT_STARTED: 0, total: 0 }
  for (const c of cases) {
    const bucket = bucketOf(c)
    if (!bucket) continue
    counts[bucket]++
    counts.total++
  }
  return counts
}

// --- payouts -----------------------------------------------------------------------------------

/** The business's words (Unit 63): recorded-and-sent is Processing until the expert confirms it. */
export type PayoutState = 'PAID' | 'PENDING' | 'PROCESSING'

export function stateOf(row: ExpertPayoutRow): PayoutState | null {
  if (row.status === 'CONFIRMED') return 'PAID'
  if (row.status === 'PAID') return 'PROCESSING'
  if (row.status === 'PENDING') return 'PENDING'
  return null // VOIDED counts nowhere.
}

/** A payout's date: when it was sent, else when it falls due. */
export function payoutDate(row: ExpertPayoutRow): string | null {
  return row.settledOn ?? row.dueDate
}

/** The currency most of these payouts are in; USD when there are none. */
export function primaryCurrency(rows: readonly ExpertPayoutRow[]): string {
  const counts = new Map<string, number>()
  for (const r of rows) if (stateOf(r)) counts.set(r.currency, (counts.get(r.currency) ?? 0) + 1)
  return [...counts].sort((a, b) => b[1] - a[1])[0]?.[0] ?? 'USD'
}

export type PayoutSums = Record<PayoutState, { amount: number; count: number }> & { total: { amount: number; count: number } }

/** Sums in one currency. Voided rows and other currencies are left out. */
export function payoutSums(rows: readonly ExpertPayoutRow[], currency: string): PayoutSums {
  const sums: PayoutSums = {
    PAID: { amount: 0, count: 0 },
    PENDING: { amount: 0, count: 0 },
    PROCESSING: { amount: 0, count: 0 },
    total: { amount: 0, count: 0 },
  }
  for (const r of rows) {
    const s = stateOf(r)
    if (!s || r.currency !== currency) continue
    sums[s].amount += r.amount
    sums[s].count++
    sums.total.amount += r.amount
    sums.total.count++
  }
  return sums
}

/** Change in percent against the previous period; null when there is nothing to compare with. */
export function deltaPct(current: number, previous: number): number | null {
  if (previous === 0) return null
  return Math.round(((current - previous) / previous) * 100)
}

// --- months ------------------------------------------------------------------------------------

export type Month = { key: string; label: string; from: Date; to: Date }

/** The last `n` calendar months ending with the current one, oldest first. */
export function lastMonths(n: number, now: Date = new Date()): Month[] {
  return Array.from({ length: n }, (_, i) => {
    const from = new Date(now.getFullYear(), now.getMonth() - (n - 1 - i), 1)
    const to = new Date(from.getFullYear(), from.getMonth() + 1, 1)
    return { key: `${from.getFullYear()}-${from.getMonth() + 1}`, label: from.toLocaleDateString(undefined, { month: 'short' }), from, to }
  })
}

/** Sum `value` of each item into the month its `date` falls in. Undated items are skipped. */
export function perMonth<T>(items: readonly T[], months: Month[], date: (t: T) => string | null, value: (t: T) => number): number[] {
  return months.map((m) => items.reduce((sum, t) => (inRange(date(t), m) ? sum + value(t) : sum), 0))
}

// --- recent activity ---------------------------------------------------------------------------

export type Activity = {
  key: string
  reference: string
  label: string
  kind: 'paid' | 'processing' | 'pending' | 'signed'
  at: string
  amount: { value: number; currency: string } | null
  to: string
}

/** Payments and signed letters, newest first. Every entry is dated by a field the server sent. */
export function recentActivity(cases: readonly ExpertCaseSummary[], payouts: readonly ExpertPayoutRow[]): Activity[] {
  const items: Activity[] = []
  payouts.forEach((r, i) => {
    const s = stateOf(r)
    const at = payoutDate(r)
    if (!s || !at) return
    items.push({
      key: `p-${i}`,
      reference: r.caseReference ?? 'Case',
      label: s === 'PAID' ? 'Payment received' : s === 'PROCESSING' ? 'Payment sent — confirm receipt' : 'Payment due',
      kind: s === 'PAID' ? 'paid' : s === 'PROCESSING' ? 'processing' : 'pending',
      at,
      amount: { value: r.amount, currency: r.currency },
      to: '/payouts',
    })
  })
  for (const c of cases) {
    if (!c.signedAt) continue
    items.push({
      key: `c-${c.caseId}`,
      reference: c.caseReference ?? 'Case',
      label: 'Signed letter sent',
      kind: 'signed',
      at: c.signedAt,
      amount: null,
      to: `/case?caseId=${c.caseId}`,
    })
  }
  return items.sort((a, b) => b.at.localeCompare(a.at))
}

/** "Good morning" / "Good afternoon" / "Good evening" for the local hour. */
export function greeting(now: Date = new Date()): string {
  const h = now.getHours()
  return h < 12 ? 'Good morning' : h < 18 ? 'Good afternoon' : 'Good evening'
}

export const money = (amount: number, currency: string) =>
  new Intl.NumberFormat(undefined, { style: 'currency', currency }).format(amount)

/**
 * The dashboard's and payouts page's colours, by state: green done, blue in progress, amber waiting,
 * slate neutral (not started / processing). Theme tokens, so dark mode follows; the slate is darker
 * than the portal's muted grey so the ring clears 3:1 against the card.
 */
export const COLORS = { done: 'hsl(var(--success))', active: 'hsl(var(--info))', waiting: 'hsl(var(--warning))', neutral: '#64748b' }
