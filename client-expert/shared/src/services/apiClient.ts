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
 * **The chain accepts `GET`, `POST`, `PUT`, `DELETE` and `OPTIONS`**, and the only request headers
 * it allows are `Content-Type` and `X-Portal-Token`. A verb outside that list fails its preflight
 * with a bare 403 the client cannot report. PUT is chat's reaction toggle (Unit 57). There is
 * deliberately no default `Content-Type` set
 * here: axios adds `application/json` when the body is an object, and leaves a `FormData` body
 * alone so the browser writes the multipart boundary itself.
 *
 * **One client, two audiences (Unit 34e).** The base is `/api/portal` and each service names its
 * own half — `/client/...` or `/expert/...`. The token decides which one it is admitted to, and a
 * token minted for the other audience is refused by the server whatever path is asked for, so the
 * path is addressing rather than authorization.
 */

const PORTAL_HEADER = 'X-Portal-Token'

/**
 * **The sign-in lives in `sessionStorage` (Unit 75, D73)**: a reload keeps it, closing the tab ends
 * it, and a new tab starts signed out. The client and expert portals are separate origins, so one
 * key cannot collide. A browser that refuses storage falls back to memory only, as before.
 */
const STORAGE_KEY = 'evalos.portal.token'
const ENDED_KEY = 'evalos.portal.sessionEnded'

function stored(key: string): string | null {
  try {
    return sessionStorage.getItem(key)
  } catch {
    return null
  }
}

function store(key: string, value: string | null): void {
  try {
    if (value === null) sessionStorage.removeItem(key)
    else sessionStorage.setItem(key, value)
  } catch {
    // Storage refused (private mode, blocked site data): the in-memory token still works.
  }
}

let token: string | null = stored(STORAGE_KEY)

export function setPortalToken(value: string): void {
  token = value
  store(STORAGE_KEY, value)
}

export function hasPortalToken(): boolean {
  return token !== null
}

function clearPortalToken(): void {
  token = null
  store(STORAGE_KEY, null)
}

/**
 * Whether the last session was ended by the server (a 401 while signed in), read once: the sign-in
 * screen shows "Your session has ended" and the flag is gone.
 */
let sessionEnded: boolean | null = null

export function takeSessionEnded(): boolean {
  // Cached for the page load, so StrictMode's double render reads the same answer.
  if (sessionEnded === null) {
    sessionEnded = stored(ENDED_KEY) !== null
    store(ENDED_KEY, null)
  }
  return sessionEnded
}

/**
 * Sign out: the server revokes the token (it would otherwise live seven days), then the browser
 * forgets it and goes to `signInPath`. A full navigation, so every cache and the chat client go too.
 * A failed revoke still signs this browser out.
 */
export async function signOut(signInPath: string): Promise<void> {
  try {
    if (token) await apiClient.post('/sign-out')
  } catch {
    // The token is dropped below either way.
  }
  clearPortalToken()
  window.location.assign(signInPath)
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

// A 401 while signed in means the server no longer honours this token (expired or revoked):
// forget it and reload, and the layout's guard sends the user to sign-in, which says why. The
// auth routes are excluded, so a wrong password shows its own message rather than reloading.
apiClient.interceptors.response.use(undefined, (error: unknown) => {
  const status = axios.isAxiosError(error) ? error.response?.status : undefined
  const url = axios.isAxiosError(error) ? (error.config?.url ?? '') : ''
  if (status === 401 && token && !url.startsWith('/auth/') && url !== '/sign-out') {
    clearPortalToken()
    store(ENDED_KEY, '1')
    window.location.reload()
  }
  return Promise.reject(error)
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
