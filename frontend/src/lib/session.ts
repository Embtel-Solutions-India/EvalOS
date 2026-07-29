/**
 * Where the staff token lives, and the shape of who is holding it.
 *
 * The token is kept in a module variable and mirrored into sessionStorage so a page
 * reload does not throw the user back to login. sessionStorage, not localStorage:
 * the session dies with the tab, which is the closest thing to the 8h server TTL
 * without a refresh endpoint to talk to.
 *
 * There is no refresh strategy because there is no refresh route — the JWT is issued
 * once for 8 hours and the 401 interceptor in `api.ts` is the whole expiry story.
 */

export type Role =
  | 'GM'
  | 'BRAND_MANAGER'
  | 'PROJECT_MANAGER'
  | 'PROJECT_COORDINATOR'
  | 'CASE_MANAGER'
  | 'EXPERT_NETWORK_MANAGER'

/** What `GET /api/me` answers. The client never invents any of it. */
export type StaffIdentity = {
  id: string
  displayName: string
  role: Role
  brandId: string | null
  teamId: string | null
}

const TOKEN_KEY = 'evalos.token'

let token: string | null = sessionStorage.getItem(TOKEN_KEY)

export function getToken(): string | null {
  return token
}

export function setToken(value: string): void {
  token = value
  sessionStorage.setItem(TOKEN_KEY, value)
}

export function clearToken(): void {
  token = null
  sessionStorage.removeItem(TOKEN_KEY)
}
