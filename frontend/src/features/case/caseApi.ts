import { api, unwrap } from '../../lib/api'
import type { BoardCard } from '../board/boardRules'
import type { DriveWriteView, ProfileView } from './redactionRules'

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
  /** The client's own document folder. Staff-only — it is never sent to the client portal. */
  driveLink: string | null
  /**
   * The drafted letter (Unit 14). What `DraftPanel` links to, and the only link the client's own
   * portal shows. `driveLink` above is a different thing and is deliberately not a fallback: it is
   * the folder holding this client's passport scans, whose sharing EvalOS does not control.
   */
  draftLink: string | null
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
  /** A document chase went to the client (Unit 10). Nothing about the case changed. */
  | 'CHASED'
  | 'DELETED'
  | 'EXPORTED'
  /** A client (or, from Unit 15, an expert) portal link was minted for the case. */
  | 'PORTAL_LINK_ISSUED'
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

/**
 * The expert profile (Unit 13). Generated on demand and stored nowhere, so these are reads
 * with no cache: the document reflects the roster row as it is now, which is the point.
 */
export async function fetchRedactedProfile(caseId: string, signal?: AbortSignal): Promise<ProfileView> {
  return unwrap<ProfileView>(api.get(`/cases/${caseId}/expert-profile/redacted`, { signal }))
}

/** 409 unless the case is paid — the panel shows the server's own reason. */
export async function fetchFullProfile(caseId: string, signal?: AbortSignal): Promise<ProfileView> {
  return unwrap<ProfileView>(api.get(`/cases/${caseId}/expert-profile/full`, { signal }))
}

/**
 * A POST because it has an outward effect: a document appears in a folder the client can be
 * pointed at, and the server audits it.
 */
export async function fileProfileToDrive(caseId: string): Promise<DriveWriteView> {
  return unwrap<DriveWriteView>(api.post(`/cases/${caseId}/expert-profile/redacted/to-drive`))
}

/**
 * The client portal link (Unit 14).
 *
 * `openedAt` is the token's own last-seen and **moves on every visit** — it answers "when did they
 * last look", which is what support needs. The separate `evalos_case.client_portal_read_at` is the
 * first read and is stamped once; it is not exposed here. Do not read this field as first-open.
 *
 * There is deliberately no read that returns the URL of an existing link: the token exists once, in
 * the response to `mintPortalLink`. Losing it means minting a new one, which revokes the old.
 */
export type PortalLinkStatus = { live: boolean; expiresAt: string | null; openedAt: string | null }

export type MintedLink = { url: string; expiresAt: string }

export async function fetchPortalLink(caseId: string, signal?: AbortSignal): Promise<PortalLinkStatus> {
  return unwrap<PortalLinkStatus>(api.get(`/cases/${caseId}/portal-link`, { signal }))
}

/** Re-minting revokes the previous link immediately, which the panel warns about first. */
export async function mintPortalLink(caseId: string): Promise<MintedLink> {
  return unwrap<MintedLink>(api.post(`/cases/${caseId}/portal-link`))
}
