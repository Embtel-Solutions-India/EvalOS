import { apiClient, unwrap, type ApiResponse } from '@shared/services/apiClient'
import type { ExpertCaseView, SignedLetterView } from '@/lib/expertCase'

/**
 * The expert's case, against EvalOS's real portal API (Unit 15, wired in 34e).
 *
 * **The first real module in this app**, and the only one — everything else here is still a
 * `localStorage` mock behind `mockDelay`.
 *
 * **No call names a case.** The portal token names exactly one, server-side; a caller-supplied
 * case id is how one expert would reach another's assignment. That is also why there is no list
 * function here: one token, one case, and whether a credential should name a *party* instead is
 * Unit 34's decision D1, which is not taken.
 */

/** The whitelisted view. The first call also stamps the read receipt the case manager reads. */
export async function getCase(): Promise<ExpertCaseView> {
  return unwrap(apiClient.get<ApiResponse<ExpertCaseView>>('/expert/case'))
}

/**
 * "I will sign this."
 *
 * Safe to send twice: the server answers 200 with the state as it stands when the offer is already
 * accepted, and 409 only when the offer is genuinely over. So a double click is not an error to
 * handle here.
 */
export async function accept(): Promise<ExpertCaseView> {
  return unwrap(apiClient.post<ApiResponse<ExpertCaseView>>('/expert/accept'))
}

/**
 * "Not until the client sends this."
 *
 * The description becomes a **required checklist item** on the case, which is what puts it on the
 * Coordinator's board. The case is held until they resume it, so signing is refused in between —
 * which is the point rather than a side effect.
 */
export async function requestEvidence(missing: string): Promise<ExpertCaseView> {
  return unwrap(apiClient.post<ApiResponse<ExpertCaseView>>('/expert/request-evidence', { missing }))
}

/** "I will not take this." The reason is required — it is what the rematch works from. */
export async function decline(reason: string): Promise<ExpertCaseView> {
  return unwrap(apiClient.post<ApiResponse<ExpertCaseView>>('/expert/decline', { reason }))
}

/**
 * Where the letter is.
 *
 * A link rather than a download: the draft is a document EvalOS holds the address of, not the
 * bytes. Fetched on the click and never stored.
 */
export async function letterLink(): Promise<string> {
  const { url } = await unwrap(apiClient.get<ApiResponse<{ url: string }>>('/expert/letter'))
  return url
}

export interface UploadProgressHandler {
  (percent: number): void
}

/**
 * The signature: the signed PDF, with the attestation that makes it evidence.
 *
 * **The attestation is sent because the API requires it**, not because the UI collected it. The
 * server refuses an upload without one, and refuses one whose wording is not the sentence it
 * composed — so `attestation` must be the string that came back on the view, unedited.
 *
 * **The name is not sent.** It is the case's own expert, read server-side: a name this app supplied
 * would only prove that this app can spell its own claim twice, and the stored row is the evidence.
 */
export async function uploadSignedLetter(
  file: File,
  attestation: string,
  onProgress?: UploadProgressHandler,
): Promise<SignedLetterView> {
  const form = new FormData()
  form.append('file', file)
  form.append('attestation', attestation)

  return unwrap(
    apiClient.post<ApiResponse<SignedLetterView>>('/expert/signed-letter', form, {
      // `total` is absent on some proxies; the file's own size is the honest denominator.
      onUploadProgress: (event) =>
        onProgress?.(Math.min(100, Math.round((event.loaded / (event.total || file.size)) * 100))),
    }),
  )
}
