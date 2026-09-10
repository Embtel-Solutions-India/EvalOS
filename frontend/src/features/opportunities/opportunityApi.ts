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

// --- Unit 39: the marketing desk --------------------------------------------

/** What GHL returned after a lead was opened or matched. */
export type Lead = {
  contactId: string
  opportunityId: string
  name: string | null
  monetaryValue: number | null
  /**
   * Whether anything was actually created.
   *
   * GHL has no idempotency key, so creates go through upsert and this flag is how the desk can
   * say "opened" rather than "already open" — a repeat submission is one deal, and the marketer
   * should be told which happened instead of guessing.
   */
  created: boolean
}

export type Note = {
  id: string
  body: string
  authorId: string
  createdAt: string
}

export type NewLead = {
  firstName?: string
  lastName?: string
  email?: string
  phone?: string
  name?: string
  monetaryValue?: number
}

/**
 * Opens a lead on the caller's own pipeline.
 *
 * **No pipeline argument, deliberately** — the server takes the caller's from their token. An
 * email or a phone is required, because GHL matches an existing contact on those and a lead with
 * neither makes every save create another contact. The server enforces it; the form asks for it.
 */
export function openLead(lead: NewLead): Promise<Lead> {
  return unwrap<Lead>(api.post('/marketing/leads', lead))
}

/** Records the valuation on an opportunity the caller owns. */
export function valueLead(
  opportunityId: string,
  update: { name?: string; monetaryValue?: number },
): Promise<Lead> {
  return unwrap<Lead>(api.put(`/marketing/leads/${opportunityId}`, update))
}

export function fetchNotes(opportunityId: string, signal?: AbortSignal): Promise<readonly Note[]> {
  return unwrap<readonly Note[]>(api.get(`/marketing/leads/${opportunityId}/notes`, { signal }))
}

/**
 * Adds a note. There is no edit and no delete, here or on the server or in the database.
 *
 * This is the client conversation rather than a record of it, so a correction is a new note.
 */
export function addNote(opportunityId: string, body: string): Promise<Note> {
  return unwrap<Note>(api.post(`/marketing/leads/${opportunityId}/notes`, { body }))
}
