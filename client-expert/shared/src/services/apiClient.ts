import axios from 'axios'

/**
 * The portal's HTTP client — one credential, in one header, and nothing else.
 *
 * **The credential is `X-Portal-Token`, not a cookie.** EvalOS's portal filter chain sets
 * `allowCredentials(false)` on purpose: a wildcard-adjacent origin mistake on a cookie-bearing
 * API is a session-riding hole, and a header the browser never attaches on its own has no such
 * failure mode. `withCredentials: true` was on this instance and is gone — with it set, every
 * cross-origin call is refused at the preflight.
 *
 * **The token is held in a module variable and never persisted.** The rest of this app keeps its
 * mock session in `localStorage`; this is a forwarded-link credential and a shared machine is the
 * risk. A reload still has the fragment in the address bar.
 *
 * **The chain accepts `GET`, `POST` and `OPTIONS` only**, and the only request headers it allows
 * are `Content-Type` and `X-Portal-Token`. There is deliberately no default `Content-Type` set
 * here: axios adds `application/json` when the body is an object, and leaves a `FormData` body
 * alone so the browser writes the multipart boundary itself.
 *
 * **One client, two audiences (Unit 34e).** The base is `/api/portal` and each service names its
 * own half — `/client/...` or `/expert/...`. The token decides which one it is admitted to, and a
 * token minted for the other audience is refused by the server whatever path is asked for, so the
 * path is addressing rather than authorization.
 */

const PORTAL_HEADER = 'X-Portal-Token'

let token: string | null = null

export function setPortalToken(value: string): void {
  token = value
}

export function hasPortalToken(): boolean {
  return token !== null
}

const base = (import.meta.env.VITE_API_URL || '') as string

export const apiClient = axios.create({
  baseURL: `${base}/api/portal`,
  timeout: 30_000,
})

apiClient.interceptors.request.use((config) => {
  if (token) config.headers[PORTAL_HEADER] = token
  return config
})

/** The HTTP status, for `failureMessage` — the server's own words are not written for a client. */
export function statusOf(error: unknown): number | undefined {
  return axios.isAxiosError(error) ? error.response?.status : undefined
}

/** EvalOS's one response envelope: `{ success, data, error }`. No endpoint invents its own. */
export interface ApiResponse<T> {
  success: boolean
  data: T
  error?: { code: string; message: string }
}

export async function unwrap<T>(request: Promise<{ data: ApiResponse<T> }>): Promise<T> {
  const { data } = await request
  if (!data.success) throw new Error(data.error?.message ?? 'Request failed')
  return data.data
}
