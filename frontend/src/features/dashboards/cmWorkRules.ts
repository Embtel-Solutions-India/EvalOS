import { formatPayout } from '../../lib/money'
import type { ChecklistCaseRow, OfferOutcome } from './pmMetricsApi'

export type Segment = { key: string; label: string; color: string; count: number; pct: number }

/** In bar order. Words as well as colour: status is never carried by colour alone. */
const SEGMENTS = [
  { key: 'approved', label: 'Approved', color: 'var(--status-green)' },
  { key: 'uploaded', label: 'Awaiting approval', color: 'var(--accent-primary)' },
  { key: 'required', label: 'Still required', color: 'var(--text-muted)' },
  { key: 'missing', label: 'Missing', color: 'var(--status-red)' },
  { key: 'incorrect', label: 'Incorrect', color: 'var(--status-amber)' },
] as const

/** Non-empty segments of one case's checklist as whole percentages that always total 100. */
export function segmentPercents(row: ChecklistCaseRow): Segment[] {
  if (row.total <= 0) return []
  const live = SEGMENTS.map((s) => ({ ...s, count: row[s.key] })).filter((s) => s.count > 0)
  let used = 0
  return live.map((s, i) => {
    // The last segment takes the remainder, so rounding can never leave the bar short or long.
    const pct = i === live.length - 1 ? 100 - used : Math.round((s.count / row.total) * 100)
    used += pct
    return { ...s, pct }
  })
}

export const OFFER_LABEL: Record<OfferOutcome, string> = {
  OFFERED: 'Waiting for expert',
  ACCEPTED: 'Accepted',
  DECLINED: 'Declined',
  TIMED_OUT: 'Timed out',
  SUPERSEDED: 'Replaced',
}

/** An unpriced offer is "—", never 0. A brand with no currency shows the bare number, as ExpertBalances does. */
export function feeText(fee: number | null, currency: string | null): string {
  if (fee === null) return '—'
  return currency ? formatPayout(fee, currency) : String(fee)
}
