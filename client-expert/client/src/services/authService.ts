import { apiClient, setPortalToken, unwrap, type ApiResponse } from '@shared/services/apiClient'

/**
 * The client portal's front door (Unit 42) — identify, sign in, forgot-password, set-password.
 *
 * **One credential, still.** Signing in does not create a second kind of session alongside the
 * forwarded-link one: `signIn` and `setPassword` both mint the same portal token `documentService`
 * and `invoiceService` already read, through the same `setPortalToken`. A password is a second way
 * to *obtain* that token, not a second thing to hold.
 */

/**
 * The three answers the sign-in screen branches on. The server's vocabulary, unmapped — the same
 * rule `CHECKLIST_STATUS` and `APPROVAL_STATUS` already follow, so a fourth value fails loudly
 * rather than falling into a default branch.
 */
export type IdentifyState = 'PASSWORD_SET' | 'NO_PASSWORD' | 'UNKNOWN'

export type Session = { token: string; expiresAt: string }

export async function identify(email: string): Promise<IdentifyState> {
  const { state } = await unwrap(
    apiClient.post<ApiResponse<{ state: IdentifyState }>>('/auth/identify', { email }),
  )
  return state
}

/** On success the token goes straight into the API client's memory — never localStorage. */
export async function signIn(email: string, password: string): Promise<Session> {
  const session = await unwrap(apiClient.post<ApiResponse<Session>>('/auth/sign-in', { email, password }))
  setPortalToken(session.token)
  return session
}

/** Always resolves. The server answers 204 whether or not the address is known, by design. */
export async function forgotPassword(email: string): Promise<void> {
  await apiClient.post('/auth/forgot-password', { email })
}

export async function setPassword(token: string, password: string): Promise<Session> {
  const session = await unwrap(
    apiClient.post<ApiResponse<Session>>('/auth/set-password', { token, password }),
  )
  setPortalToken(session.token)
  return session
}
