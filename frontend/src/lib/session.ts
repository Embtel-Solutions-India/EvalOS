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

/**
 * How a role is written for a human.
 *
 * <p>Lives beside {@link Role} rather than in the nav that first needed it: the board's case card
 * names an owner too (Unit 31), and a second copy of these six strings is how the sidebar and the
 * card come to call the same person different things. `Record<Role, string>`, so a new role is a
 * compile error here rather than a raw enum name leaking onto a screen.
 */
export const ROLE_LABELS: Record<Role, string> = {
  GM: 'General Manager',
  BRAND_MANAGER: 'Brand Manager',
  PROJECT_MANAGER: 'Project Manager',
  PROJECT_COORDINATOR: 'Project Coordinator',
  CASE_MANAGER: 'Case Manager',
  EXPERT_NETWORK_MANAGER: 'Expert Network Manager',
}

/** What `GET /api/me` answers. The client never invents any of it. */
export type StaffIdentity = {
  id: string
  displayName: string
  role: Role
  brandId: string | null
  /**
   * The caller's own brand, resolved server-side because the client cannot look it up:
   * `GET /api/brands` is GM-only. Null for the GM, who is cross-brand and picks a scope with the
   * brand switcher instead.
   */
  brandName: string | null
  teamId: string | null
}

const TOKEN_KEY = 'evalos.token'

let token: string | null = sessionStorage.getItem(TOKEN_KEY)

/**
 * Called when the token is dropped, so React learns about it.
 *
 * A single slot rather than an emitter: there is exactly one `AuthProvider` by
 * construction, and a list of subscribers for one subscriber is machinery nobody reads.
 * Without this, clearing the token only mutated a module variable — the shell went on
 * rendering as signed-in with no token, and every request 401'd silently until a manual
 * reload.
 */
let onCleared: (() => void) | null = null

export function onTokenCleared(handler: (() => void) | null): void {
  onCleared = handler
}

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
  onCleared?.()
}
