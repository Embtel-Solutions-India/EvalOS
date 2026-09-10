import { api, unwrap } from '../../lib/api'

/**
 * `OpportunityBoardService.Deal` — one card on the board.
 *
 * `amount` is null when GHL holds no value for the opportunity, **not** zero: "nobody has priced
 * it" and "it is worth nothing" are different claims, and the second one loses deals.
 */
export type Deal = {
  opportunityId: string
  name: string | null
  contactId: string
  status: string
  amount: number | null
}

/** One stage of the pipeline, named by GHL and ordered by GHL's own `position`. */
export type BoardColumn = {
  stageId: string
  stageName: string
  position: number
  deals: readonly Deal[]
  total: number
}

/**
 * The whole board.
 *
 * `readAt` and `stale` are on the payload because a cached screen the reader cannot date is one
 * they have to trust blindly — the same reasoning the funnel screens' age stamp carries.
 */
export type OpportunityBoard = {
  columns: readonly BoardColumn[]
  totalDeals: number
  totalValue: number
  readAt: string
  stale: boolean
}

/**
 * The caller's board.
 *
 * **There is no argument, and there must never be one.** What a caller sees is decided entirely
 * by their own principal: a `SALES` or `MARKETING` member gets the one pipeline their row names,
 * and the GM gets the union of the selling brand's. A `pipelineId` parameter here would make the
 * whole access model advisory — the server takes none, and the day one is added the server's own
 * test fails first.
 */
export function fetchOpportunityBoard(signal?: AbortSignal): Promise<OpportunityBoard> {
  return unwrap<OpportunityBoard>(api.get('/opportunities/board', { signal }))
}
