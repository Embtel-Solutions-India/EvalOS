import { createContext, useContext } from 'react'

/**
 * The shell filters context and its hook, split from `FiltersProvider` for the same
 * Fast Refresh reason as `lib/authContext.ts`.
 */

/**
 * The periods with a name, mirroring the server's `DateRange`.
 *
 * The four "this" ranges are **calendar-to-date**: `week` is Monday through today, `month` is the
 * 1st through today, `year` is 1 January through today. They were rolling 7/30/365-day windows
 * until the filter gained `last-month` — at which point two meanings of "month" would have lived in
 * one control, and the labels were the half that was already lying: "This month" answering for 30
 * days spanning two of them says something untrue on the 3rd.
 *
 * `last-month` and `last-year` are whole completed periods, and are the only ones that do not end
 * today.
 */
export type NamedRange = 'today' | 'week' | 'month' | 'year' | 'last-month' | 'last-year'

/**
 * The period the shell is filtered to.
 *
 * **A discriminated union rather than a string plus two loose date fields.** `custom` is
 * meaningless without its edges, so making it a separate member means a range with no dates cannot
 * be constructed at all — the alternative carries `from`/`to` that are undefined for six of the
 * seven cases and relies on every call site remembering which. `rangeParams` below is then total
 * over the union, so no consumer branches on `kind` itself.
 */
export type DateRange = { kind: NamedRange } | { kind: 'custom'; from: string; to: string }

/** The default, and it is `month` for a reason `filters.tsx` states at length. */
export const DEFAULT_RANGE: DateRange = { kind: 'month' }

const NAMED_LABELS: Record<NamedRange, string> = {
  today: 'Today',
  week: 'This week',
  month: 'This month',
  year: 'This year',
  'last-month': 'Last month',
  'last-year': 'Last year',
}

/** What to call this period on screen. One source, so a heading and a control cannot disagree. */
export function rangeLabel(range: DateRange): string {
  return range.kind === 'custom' ? `${range.from} → ${range.to}` : NAMED_LABELS[range.kind]
}

/**
 * The query parameters for this period.
 *
 * **`from`/`to` are sent only for `custom`, and that is required rather than tidy**: the server
 * refuses explicit dates on a named range (400) instead of ignoring them, because a caller who
 * sends both means the explicit window and answering for the named one would be a wrong number
 * wearing a right label. Sending stale dates alongside `month` would therefore break the request
 * outright — which is the failure direction we want, but it is this function's job not to.
 */
export function rangeParams(range: DateRange): Record<string, string> {
  return range.kind === 'custom'
    ? { range: 'custom', from: range.from, to: range.to }
    : { range: range.kind }
}

/** Whether two periods are the same question — used to light up the active control. */
export function sameRange(a: DateRange, b: DateRange): boolean {
  if (a.kind !== b.kind) return false
  return a.kind !== 'custom' || (b.kind === 'custom' && a.from === b.from && a.to === b.to)
}

export type FiltersValue = {
  /** null means "all brands", which only the GM can mean. */
  activeBrandId: string | null
  setActiveBrandId: (brandId: string | null) => void
  dateRange: DateRange
  setDateRange: (range: DateRange) => void
}

export const FiltersContext = createContext<FiltersValue | null>(null)

export function useFilters(): FiltersValue {
  const value = useContext(FiltersContext)
  if (!value) throw new Error('useFilters must be used inside FiltersProvider')
  return value
}
