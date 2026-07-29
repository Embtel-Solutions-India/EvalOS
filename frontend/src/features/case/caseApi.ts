import { api, unwrap } from '../../lib/api'
import type { BoardCard } from '../board/boardRules'

/**
 * The case detail reads and its one write.
 *
 * The summary is the same shape the board draws, so `actionsFor` and `STAGE_ACCESS` from
 * `boardRules` decide the stage-action header too — the legal actions for a case do not
 * depend on which screen you are looking at, and two tables would be two answers.
 */

/** The case list/board projection, plus the fields only the detail page asks for. */
export type CaseDetail = {
  summary: BoardCard & {
    stageEnteredAt: string | null
    deliveryDate: string | null
    caseClosedDate: string | null
    expertId: string | null
    draftVersionCount: number
    paid: boolean
    paidAt: string | null
    revenueRecognized: boolean
  }
  clientName: string | null
  driveLink: string | null
  expertName: string | null
  expertTier: string | null
  checklistTotal: number
  checklistComplete: number
  /** Null for every role outside GM / PM / CM — the server omits it rather than the client. */
  pmStrategyNotes: string | null
  /**
   * Whether this caller may READ the notes. Stated by the server because it cannot be derived:
   * `pmStrategyNotes` is null both when withheld and when simply unwritten, and read access is
   * not the same set as write access (the Case Manager reads without writing).
   */
  maySeeStrategyNotes: boolean
  /** The server's own answer, so the client does not re-derive the write rule. */
  mayEditStrategyNotes: boolean
}

export type AuditAction =
  | 'CREATED'
  | 'UPDATED'
  | 'ASSIGNED'
  | 'STAGE_CHANGED'
  | 'DELETED'
  | 'EXPORTED'
  | 'LOGIN'

export type TimelineEntry = {
  at: string
  actorName: string
  action: AuditAction
  /** Null on a row whose stored snapshot predates the current shape — see the service. */
  stage: string | null
  exceptionState: string | null
  note: string | null
}

export async function fetchCase(caseId: string, signal?: AbortSignal): Promise<CaseDetail> {
  return unwrap<CaseDetail>(api.get(`/cases/${caseId}`, { signal }))
}

export async function fetchTimeline(caseId: string, signal?: AbortSignal): Promise<TimelineEntry[]> {
  return unwrap<TimelineEntry[]>(api.get(`/cases/${caseId}/timeline`, { signal }))
}

/** PATCH, not POST: this changes a field, not the case's state. */
export async function saveStrategyNotes(caseId: string, pmStrategyNotes: string): Promise<CaseDetail> {
  return unwrap<CaseDetail>(api.patch(`/cases/${caseId}/strategy-notes`, { pmStrategyNotes }))
}
