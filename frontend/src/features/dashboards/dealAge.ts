import type { Deal, OpportunityBoard } from '../opportunities/opportunityApi'

/**
 * How long a deal may sit untouched before it is a problem.
 *
 * Seven days, and it is a business judgement rather than a constant worth configuring. A deal
 * with no contact for a working week has gone quiet; shorter would flag deals somebody is
 * actively working and teach people to ignore the tile.
 *
 * **It lives here, in its own module, rather than in either screen that reads it.** Two
 * dashboards report on it — Sales/Marketing's own board summary and the GM's overview — and a
 * threshold copied into both drifts the first time one of them is tuned. (It also keeps
 * `PipelineDashboard` a file that exports only components, which is what fast refresh needs.)
 */
export const STALE_DAYS = 7

/** Every deal on the board, flattened out of its stage. */
export function allDeals(board: OpportunityBoard | null): readonly Deal[] {
  return (board?.columns ?? []).flatMap((column) => column.deals)
}

/**
 * Deals with a known date older than the threshold, oldest first.
 *
 * **A null `updatedAt` is excluded here and counted separately.** Treating "no date" as stale
 * would inflate the queue with rows nobody can act on differently; treating it as fresh would
 * hide them. It is a third state and is shown as one.
 */
export function staleDeals(board: OpportunityBoard | null): readonly Deal[] {
  const cutoff = Date.now() - STALE_DAYS * 24 * 60 * 60 * 1000
  return allDeals(board)
    .filter((deal) => deal.updatedAt !== null && Date.parse(deal.updatedAt) < cutoff)
    .sort((a, b) => Date.parse(a.updatedAt ?? '') - Date.parse(b.updatedAt ?? ''))
}

export function countUndated(board: OpportunityBoard | null): number {
  return allDeals(board).filter((deal) => deal.updatedAt === null).length
}

export function daysSince(iso: string | null): number {
  if (iso === null) return 0
  return Math.floor((Date.now() - Date.parse(iso)) / (24 * 60 * 60 * 1000))
}
