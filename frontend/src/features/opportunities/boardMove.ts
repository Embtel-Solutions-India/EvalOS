import type { BoardColumn, Deal } from './opportunityApi'

/**
 * The board with one deal placed in `toStageId`, optionally with fields the server sent back.
 *
 * **Only the columns that change are new objects.** Every other column, and every other deal,
 * keeps its identity — which is what lets the memoised columns and cards skip rendering, so a
 * drop re-renders two columns and one card rather than the board.
 *
 * Returns `columns` itself when there is nothing to do: the deal is unknown (removed by a
 * refresh meanwhile), the target stage is not on the board (a card must never vanish), or the
 * deal is already there with the same fields.
 */
export function moveDeal(
  columns: readonly BoardColumn[],
  opportunityId: string,
  toStageId: string,
  patch?: Partial<Pick<Deal, 'name' | 'status' | 'amount'>>,
): readonly BoardColumn[] {
  const from = columns.find((column) => column.deals.some((deal) => deal.opportunityId === opportunityId))
  if (!from || !columns.some((column) => column.stageId === toStageId)) return columns
  const deal = from.deals.find((d) => d.opportunityId === opportunityId)!
  const next = patch ? { ...deal, ...patch } : deal
  const unchanged = (Object.keys(next) as (keyof Deal)[]).every((key) => next[key] === deal[key])
  if (from.stageId === toStageId && unchanged) return columns

  return columns.map((column) => {
    if (column === from && column.stageId === toStageId) {
      return { ...column, deals: column.deals.map((d) => (d === deal ? next : d)), total: sumOf(column.deals, deal, next) }
    }
    if (column === from) {
      return { ...column, deals: column.deals.filter((d) => d !== deal), total: column.total - (deal.amount ?? 0) }
    }
    // Prepended: the card you just dropped is the one you are looking for.
    if (column.stageId === toStageId) {
      return { ...column, deals: [next, ...column.deals], total: column.total + (next.amount ?? 0) }
    }
    return column
  })
}

function sumOf(deals: readonly Deal[], replaced: Deal, next: Deal): number {
  return deals.reduce((total, d) => total + ((d === replaced ? next : d).amount ?? 0), 0)
}
