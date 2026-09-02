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
  /** The client's own document folder. Staff-only — it is never sent to the client portal. */
  /**
   * The drafted letter (Unit 14). What `DraftPanel` links to, and the only link the client's own
   * portal shows. It is deliberately not a fallback for the client's own documents, which are
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
  /**
   * Why this expert was chosen (Unit 32).
   *
   * **A different field from `pmStrategyNotes` and a different audience**: the ENM and the Brand
   * Manager read this and not the notes, the Case Manager reads the notes and not this. Null when
   * withheld and null when unwritten, which is why the flag below is stated rather than inferred.
   */
  expertSelectionRationale: string | null
  maySeeExpertRationale: boolean
  /**
   * Whether the server sent `clientName` and `draftLink` at all.
   *
   * Stated for the same reason `maySeeStrategyNotes` is, and here it is load-bearing rather
   * than tidy: `clientName` is null both when withheld and when no contact is linked to the
   * case. Without this flag the header's `?? 'Unnamed contact'` fallback would assert something
   * false about a client the caller simply may not see.
   */
  maySeeCaseContent: boolean
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
  /** A Case Manager escalated a blocked case to its PM (Unit 22). Nothing about the case changed. */
  | 'FLAGGED'
  /** An ENM recorded a performance concern — written against the expert, never a case. */
  | 'PERFORMANCE_FLAGGED'
  /**
   * The client asked for changes to a draft. Its own action as of Unit 22 so the Case Manager's
   * client-revision rate has something to count; before that it shared `UPDATED` with half the
   * draft loop, and rows written then stay `UPDATED`.
   */
  | 'CLIENT_REVISION_REQUESTED'
  /**
   * Somebody on the case wrote a note for whoever works it next (Unit 23). Nothing about the
   * case changed — the text in `note` is the entire entry, which is why `Timeline` draws these
   * differently from the transitions around them.
   */
  | 'NOTE_ADDED'
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

/**
 * Append a note to the case's trail.
 *
 * POST, and there is deliberately no edit or delete beside it: the trail is append-only, so a
 * note is permanent once written. The response is the refreshed summary, but the caller reloads
 * the timeline anyway — the new row is the point.
 *
 * No role check on the client either. The server's gate is the case scope, so a button rendered
 * for somebody who may not write would be refused by the same 403 that refuses reading it.
 */
export async function postNote(caseId: string, note: string): Promise<void> {
  await unwrap(api.post(`/cases/${caseId}/notes`, { note }))
}

/** PATCH, not POST: this changes a field, not the case's state. */
export async function saveStrategyNotes(caseId: string, pmStrategyNotes: string): Promise<CaseDetail> {
  return unwrap<CaseDetail>(api.patch(`/cases/${caseId}/strategy-notes`, { pmStrategyNotes }))
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

/** One version on the draft history (Unit 32). */
export type DraftVersion = {
  id: string
  version: number
  status: string
  /** Null for a client or expert upload, which have no staff row — not an error. */
  uploadedByName: string | null
  uploadedAt: string
  notes: string | null
  /** The reviewer's comment on THIS version, stamped by the transition that ruled on it. */
  reviewComment: string | null
  /** As uploaded. Null for a draft submitted before Unit 30, which carried a link and no file. */
  filename: string | null
}

export async function fetchDraftVersions(caseId: string, signal?: AbortSignal): Promise<DraftVersion[]> {
  return fetchCaseDocuments(caseId, 'DRAFT', signal)
}

/** One kind's versions, newest first. `DraftVersion` is the row shape for every kind. */
export async function fetchCaseDocuments(
  caseId: string,
  kind: 'DRAFT' | 'CLIENT_UPLOAD' | 'SIGNED_LETTER',
  signal?: AbortSignal,
): Promise<DraftVersion[]> {
  return unwrap<DraftVersion[]>(api.get(`/cases/${caseId}/documents`, { params: { kind }, signal }))
}

/**
 * A 5-minute URL for one document (Unit 30).
 *
 * **Fetched at the moment of the click, never stored.** A presigned URL held in component state
 * would expire while the page sits open, and a user clicking a dead link cannot tell that from a
 * missing document. Ask, then open.
 */
export async function fetchDocumentUrl(caseId: string, documentId: string): Promise<string> {
  const { url } = await unwrap<{ url: string }>(api.get(`/cases/${caseId}/documents/${documentId}/url`))
  return url
}
