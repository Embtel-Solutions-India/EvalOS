import { apiClient, unwrap, type ApiResponse } from '@shared/services/apiClient'
import type { RequestAnswers } from '@/types/intake'

/**
 * The client's own requests (Unit 43).
 *
 * **The draft lives on the server, not in `localStorage`.** That is the one substantive change
 * from the funnel deleted in `f9f1165`: a client who abandons on the questionnaire has by then
 * chosen a service and typed their history, and a browser-local draft loses all of it when they
 * come back on their phone. Every step autosaves against a `client_application` row.
 *
 * **Starting a request also opens the GHL opportunity** — the server does that, not this file,
 * and it happens at *start* rather than at *submit* so that a lead who stops halfway is still a
 * lead a salesperson can ring. EvalOS sends no stage and no assignee: GHL's automation places the
 * deal and decides whose it is.
 */

/** The server's word for where a request stands. This app derives no status of its own. */
export type ApplicationStatus = 'DRAFT' | 'SUBMITTED'

/**
 * One answered question, stored with the label **as it was asked**.
 *
 * **The label travels with the answer and that is the whole design.** The question text lives in
 * this app's catalog, and the staff app — which is a separate build — cannot import it, so a
 * bare `{ questionId: value }` map would leave a salesperson holding `degreeCountry: "Nigeria"`
 * with no question to put beside it. Storing the wording used also survives the catalog being
 * reworded later, which a lookup would not.
 */
export interface AnsweredQuestion {
  id: string
  label: string
  value: string
}

export interface ClientApplication {
  id: string
  serviceId: string
  serviceName: string
  purpose: string | null
  status: ApplicationStatus
  /** The whole questionnaire as a JSON string: an array of {@link AnsweredQuestion}. */
  answers: string
  createdAt: string
  updatedAt: string
  submittedAt: string | null
}

export async function listApplications(signal?: AbortSignal): Promise<ClientApplication[]> {
  return unwrap(apiClient.get<ApiResponse<ClientApplication[]>>('/applications', { signal }))
}

/**
 * Begin a request for one service.
 *
 * `serviceName` is sent as well as the id because it is what the GHL opportunity is called and
 * what Sales reads weeks later — the name **as the client saw it**, which must not change under
 * them because the catalog was edited.
 */
export async function startApplication(
  serviceId: string,
  serviceName: string,
  purpose?: string,
): Promise<ClientApplication> {
  return unwrap(
    apiClient.post<ApiResponse<ClientApplication>>('/applications', { serviceId, serviceName, purpose }),
  )
}

export async function saveApplication(
  id: string,
  answers: AnsweredQuestion[],
  purpose?: string,
): Promise<ClientApplication> {
  return unwrap(
    apiClient.put<ApiResponse<ClientApplication>>(`/applications/${id}`, {
      answers: JSON.stringify(answers),
      purpose,
    }),
  )
}

export async function submitApplication(id: string): Promise<ClientApplication> {
  return unwrap(apiClient.post<ApiResponse<ClientApplication>>(`/applications/${id}/submit`, {}))
}

/**
 * One document already attached to a request — Unit 53 (D33).
 *
 * **No object key.** That is an internal S3 address and never leaves the server.
 */
export interface RequestDocument {
  id: string
  filename: string
  contentType: string | null
  sizeBytes: number | null
  uploadedAt: string
  carriedToCase: boolean
}

/**
 * Attach a document to the request.
 *
 * **`FormData`, with no `Content-Type` set here on purpose.** The browser has to write the
 * multipart boundary itself, and naming the type by hand produces a body the server cannot parse.
 * `apiClient` deliberately sets no default type for exactly this reason.
 *
 * **Sending documents is never required to submit** (`43` §5): a missing document is something
 * Sales asks about, not a wall in front of a lead.
 */
export async function attachDocument(
  applicationId: string,
  file: File,
): Promise<RequestDocument> {
  const body = new FormData()
  body.append('file', file)
  return unwrap(
    apiClient.post<ApiResponse<RequestDocument>>(`/applications/${applicationId}/documents`, body),
  )
}

export async function listDocuments(
  applicationId: string,
  signal?: AbortSignal,
): Promise<RequestDocument[]> {
  return unwrap(
    apiClient.get<ApiResponse<RequestDocument[]>>(`/applications/${applicationId}/documents`, {
      signal,
    }),
  )
}

/** Only while the request is a draft — after sending, the server refuses and says why. */
export async function removeDocument(applicationId: string, documentId: string): Promise<void> {
  await apiClient.delete(`/applications/${applicationId}/documents/${documentId}`)
}

/**
 * The stored answers, back as the flat `{ id: value }` map the form and the conditional engine
 * work in. The labels are dropped on the way in because the catalog supplies them live while the
 * client is still typing; they matter on the way out, for readers who have no catalog.
 *
 * **Tolerant of anything that is not the expected shape**, because the alternative is a white
 * screen: this string round-trips through a database column, and a client mid-questionnaire is
 * the worst possible person to show a parse error to. An unreadable draft starts empty, which
 * they can refill; a crash loses the whole screen.
 */
export function parseAnswers(answers: string | null | undefined): RequestAnswers {
  if (!answers) return {}
  try {
    const parsed: unknown = JSON.parse(answers)
    if (!Array.isArray(parsed)) return {}
    const map: RequestAnswers = {}
    for (const entry of parsed) {
      if (entry && typeof entry === 'object' && typeof (entry as AnsweredQuestion).id === 'string') {
        map[(entry as AnsweredQuestion).id] = String((entry as AnsweredQuestion).value ?? '')
      }
    }
    return map
  } catch {
    return {}
  }
}
