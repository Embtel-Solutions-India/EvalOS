import { apiClient, unwrap, type ApiResponse } from '@shared/services/apiClient'
import type { ClientCaseSummary, ClientDraftView } from '@shared/lib/portal'

/**
 * The draft the client reviews, and the two answers they can give (34b).
 *
 * **These three endpoints have existed since Unit 14 and nothing called them.** That is what
 * made this the highest-value slice left in the portal: the backend could already accept an
 * approval and the app had no way to send one.
 *
 * **Every function takes a case id except `readOne`'s fallback.** Unit 35 made a credential able
 * to name a *party*, and a party may hold several cases — so a client has to be able to say
 * which one they mean. The tokenless variants remain for a case-scoped link, which has no id to
 * pass and must not be made to invent one.
 */

/** Every case behind a party link. Refused (403) for a case-scoped one, which names only itself. */
export function listCases(signal?: AbortSignal): Promise<ClientCaseSummary[]> {
  return unwrap(apiClient.get<ApiResponse<ClientCaseSummary[]>>('/client/cases', { signal }))
}

/** The draft on a case-scoped link. Answers 409 SAY_WHICH_CASE on a party link with several. */
export function readDraft(signal?: AbortSignal): Promise<ClientDraftView> {
  return unwrap(apiClient.get<ApiResponse<ClientDraftView>>('/client/case', { signal }))
}

export function readDraftFor(caseId: string, signal?: AbortSignal): Promise<ClientDraftView> {
  return unwrap(apiClient.get<ApiResponse<ClientDraftView>>(`/client/cases/${caseId}`, { signal }))
}

/**
 * Approves the draft — **this is what sends the letter to an expert to sign** (Handoff B).
 *
 * There is no undo that reaches the client, which is why the server refuses to guess which case
 * a party link means and why this takes an explicit id.
 */
export function approve(caseId: string): Promise<ClientDraftView> {
  return unwrap(apiClient.post<ApiResponse<ClientDraftView>>(`/client/cases/${caseId}/approve`))
}

/** Revisions carry the client's own words: the Case Manager works from them. */
export function requestRevisions(caseId: string, notes: string): Promise<ClientDraftView> {
  return unwrap(
    apiClient.post<ApiResponse<ClientDraftView>>(`/client/cases/${caseId}/request-revisions`, {
      notes,
    }),
  )
}
