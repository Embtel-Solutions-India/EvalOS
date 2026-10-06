import type { Deal } from './opportunityApi'

// Its own module, importing only a type, so the pure board logic and its tests never load the HTTP
// client (which reads browser storage on import).

/**
 * Whether a deal is still being worked. A null or blank status is EvalOS's own deal that GHL has not
 * answered for yet: open. One definition, because the server's totals, a column's header and the
 * dashboards' "no movement" all have to agree on it.
 */
export function isOpenDeal(deal: Pick<Deal, 'status'>): boolean {
  return !deal.status || deal.status.toLowerCase() === 'open'
}

/** A column's open deals, and what they are worth — what its header shows (cards still list every status). */
export function openSummary(deals: readonly Deal[]): { count: number; value: number } {
  const open = deals.filter(isOpenDeal)
  return { count: open.length, value: open.reduce((total, deal) => total + (deal.amount ?? 0), 0) }
}
