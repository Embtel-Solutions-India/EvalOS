import { apiClient, unwrap, type ApiResponse } from '@shared/services/apiClient'

/**
 * The client's own requests (Unit 43).
 *
 * **The draft lives on the server, not in `localStorage`**: choosing a service opens a
 * `client_application` row, and the documents attach to it, so a client who comes back on their
 * phone finds the request where they left it. There is no questionnaire (Unit 55).
 *
 * **Submitting opens the GHL opportunity** (D10) — the server does that, not this file. EvalOS
 * sends no stage and no assignee: GHL's automation places the deal and decides whose it is.
 */

/** The server's word for where a request stands. This app derives no status of its own. */
export type ApplicationStatus = 'DRAFT' | 'SUBMITTED'

export interface ClientApplication {
  id: string
  serviceId: string
  serviceName: string
  purpose: string | null
  status: ApplicationStatus
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
