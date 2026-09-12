import { apiClient, unwrap, type ApiResponse } from '@shared/services/apiClient'
import type { ClientDocumentsView } from '@shared/lib/portal'

/**
 * The client's documents, against EvalOS's real portal API (Unit 34c).
 *
 * **This is the first module in this app that is not a mock.** Everything it touches is already
 * built: the checklist and upload list, the S3-backed upload, and the presigned read.
 */

/** What must be sent, and what has been. */
export async function listDocuments(): Promise<ClientDocumentsView> {
  return unwrap(apiClient.get<ApiResponse<ClientDocumentsView>>('/client/documents'))
}

export interface UploadProgressHandler {
  (percent: number): void
}

/** What comes back from an upload. Deliberately not the object key — that is an internal address. */
export interface UploadedRef {
  id: string
  filename: string | null
  version: number
}

/**
 * Streams one file to S3 through EvalOS, against one checklist item.
 *
 * **The case is never named here and must not be.** It comes off the portal token server-side; a
 * caller-supplied case id is how one client writes into another's case. The same is true of the
 * client's own id, which EvalOS derives from the case's contact.
 *
 * **This never replaces anything.** Every upload mints a new document and a new key, so sending a
 * corrected transcript adds a version rather than destroying the one that was rejected — which is
 * the evidence of why it was rejected.
 */
export async function uploadDocument(
  checklistItemId: string,
  file: File,
  onProgress?: UploadProgressHandler,
): Promise<UploadedRef> {
  const form = new FormData()
  form.append('checklistItemId', checklistItemId)
  form.append('file', file)

  return unwrap(
    apiClient.post<ApiResponse<UploadedRef>>('/client/documents', form, {
      // `total` is absent on some proxies; the file's own size is the honest denominator.
      onUploadProgress: (event) =>
        onProgress?.(Math.min(100, Math.round((event.loaded / (event.total || file.size)) * 100))),
    }),
  )
}

/**
 * A five-minute URL for one of the client's own documents.
 *
 * **Fetched per click, never stored.** A presigned URL kept anywhere is a credential kept there,
 * and this one is minted only after the server has matched the document against the token's case.
 */
export async function documentUrl(documentId: string): Promise<string> {
  const { url } = await unwrap(
    apiClient.get<ApiResponse<{ url: string }>>(`/client/documents/${documentId}/url`),
  )
  return url
}
