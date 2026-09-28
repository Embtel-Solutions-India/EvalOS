import { apiClient, unwrap, type ApiResponse } from '@shared/services/apiClient'
import type {
  ClientCaseSummary,
  ClientDocumentsView,
  ClientDraftVersion,
  ClientDraftView,
  DeliveredFile,
  DraftComment,
} from '@shared/lib/portal'

/**
 * One case and everything on it (Unit 58). **Every route names its case**, and the server checks
 * it against the credential before reading anything — another client's case answers 403.
 */

const base = (caseId: string) => `/client/cases/${caseId}`

type Url = { url: string }

export function listCases(signal?: AbortSignal): Promise<ClientCaseSummary[]> {
  return unwrap(apiClient.get<ApiResponse<ClientCaseSummary[]>>('/client/cases', { signal }))
}

/** The first read of a case stamps its read receipt server-side. */
export function readCase(caseId: string, signal?: AbortSignal): Promise<ClientDraftView> {
  return unwrap(apiClient.get<ApiResponse<ClientDraftView>>(base(caseId), { signal }))
}

export function listDocuments(caseId: string, signal?: AbortSignal): Promise<ClientDocumentsView> {
  return unwrap(apiClient.get<ApiResponse<ClientDocumentsView>>(`${base(caseId)}/documents`, { signal }))
}

/** Never replaces: every upload is a new version. No `Content-Type` — the browser writes the boundary. */
export function uploadDocument(
  caseId: string,
  checklistItemId: string,
  file: File,
  onProgress?: (percent: number) => void,
): Promise<{ id: string; filename: string | null; version: number }> {
  const form = new FormData()
  form.append('file', file)
  return unwrap(
    apiClient.post(`${base(caseId)}/documents`, form, {
      params: { checklistItemId },
      // `total` is absent on some proxies; the file's own size is the honest denominator.
      onUploadProgress: (event) =>
        onProgress?.(Math.min(100, Math.round((event.loaded / (event.total || file.size)) * 100))),
    }),
  )
}

/** Five-minute links, fetched per click and never stored — a stored one is a stored credential. */
export async function documentUrl(caseId: string, documentId: string): Promise<string> {
  return (await unwrap(apiClient.get<ApiResponse<Url>>(`${base(caseId)}/documents/${documentId}/url`))).url
}

export function listDrafts(caseId: string, signal?: AbortSignal): Promise<ClientDraftVersion[]> {
  return unwrap(apiClient.get<ApiResponse<ClientDraftVersion[]>>(`${base(caseId)}/drafts`, { signal }))
}

export async function draftFileUrl(caseId: string, draftId: string, file: 'docx' | 'pdf'): Promise<string> {
  return (await unwrap(apiClient.get<ApiResponse<Url>>(`${base(caseId)}/drafts/${draftId}/files/${file}/url`))).url
}

export function listComments(caseId: string, draftId: string, signal?: AbortSignal): Promise<DraftComment[]> {
  return unwrap(apiClient.get<ApiResponse<DraftComment[]>>(`${base(caseId)}/drafts/${draftId}/comments`, { signal }))
}

/** Only on the version in review; any other answers 409 `DRAFT_NOT_CURRENT`. */
export function addComment(caseId: string, draftId: string, body: string, page?: number): Promise<DraftComment> {
  return unwrap(
    apiClient.post<ApiResponse<DraftComment>>(`${base(caseId)}/drafts/${draftId}/comments`, { body, page: page ?? null }),
  )
}

/** **Sends the letter to an expert to sign** (Handoff B). Names the version, so a stale tab cannot approve a newer one. */
export function approveDraft(caseId: string, draftId: string): Promise<ClientDraftView> {
  return unwrap(apiClient.post<ApiResponse<ClientDraftView>>(`${base(caseId)}/drafts/${draftId}/approve`))
}

export function requestChanges(caseId: string, draftId: string, notes?: string): Promise<ClientDraftView> {
  return unwrap(
    apiClient.post<ApiResponse<ClientDraftView>>(`${base(caseId)}/drafts/${draftId}/request-changes`, {
      notes: notes || null,
    }),
  )
}

/** Refused (404) by the server before delivery, so this is only called once the case is delivered. */
export function listDelivered(caseId: string, signal?: AbortSignal): Promise<DeliveredFile[]> {
  return unwrap(apiClient.get<ApiResponse<DeliveredFile[]>>(`${base(caseId)}/delivered`, { signal }))
}

export async function deliveredUrl(caseId: string, documentId: string): Promise<string> {
  return (await unwrap(apiClient.get<ApiResponse<Url>>(`${base(caseId)}/delivered/${documentId}/url`))).url
}
