import axios from 'axios'
import type { ApiResponse } from '../../lib/api'
import type { ClientDraftView } from './portalRules'

/**
 * The client portal's own HTTP client.
 *
 * **Deliberately not `lib/api`.** That instance attaches the staff bearer from `lib/session`, and
 * importing it here would pull the module that reads and writes the staff token into a page whose
 * whole point is that it holds no staff session. This instance sends exactly one credential, in one
 * header, and nothing else — and only a `type` is imported from `lib/api`, which the build erases.
 *
 * The token lives in a module variable and is never persisted. Unit 07 put the staff token in
 * `sessionStorage` on purpose so a reload does not throw somebody back to login; a portal link
 * forwarded to a shared machine is a different risk, and a reload of this page still has the
 * fragment in the address bar.
 */

const PORTAL_HEADER = 'X-Portal-Token'

let token: string | null = null

export function setPortalToken(value: string): void {
  token = value
}

const portal = axios.create({
  baseURL: (import.meta.env.VITE_API_BASE_URL ?? '/api') + '/portal/client',
  headers: { 'Content-Type': 'application/json' },
  timeout: 15_000,
})

portal.interceptors.request.use((config) => {
  if (token) config.headers[PORTAL_HEADER] = token
  return config
})

/** The HTTP status, for `failureMessage` — the server's own words are not written for a client. */
export function statusOf(error: unknown): number | undefined {
  return axios.isAxiosError(error) ? error.response?.status : undefined
}

async function unwrap(request: Promise<{ data: ApiResponse<ClientDraftView> }>): Promise<ClientDraftView> {
  const { data } = await request
  if (!data.success) throw new Error(data.error.message)
  return data.data
}

/** The whitelisted view. The first call also stamps the read receipt, server-side. */
export function fetchDraft(signal?: AbortSignal): Promise<ClientDraftView> {
  return unwrap(portal.get('/case', { signal }))
}

/**
 * Handoff B. Both writes answer the page's new state, so what the client sees afterwards comes from
 * the case rather than from an assumption that the POST worked.
 */
export function approveDraft(): Promise<ClientDraftView> {
  return unwrap(portal.post('/approve'))
}

export function requestRevisions(notes: string): Promise<ClientDraftView> {
  return unwrap(portal.post('/request-revisions', { notes }))
}
