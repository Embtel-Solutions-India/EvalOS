import { apiClient, setPortalToken, unwrap, type ApiResponse } from '@shared/services/apiClient'

/**
 * The expert portal's door (Unit 59). Like the client's: a password is a second way to obtain the
 * existing party-scoped expert token, kept for the tab (Unit 75), never a second kind of session.
 */

export type Session = { token: string; expiresAt: string }

/**
 * Sign-up and forgot-password are one act (spec 59 §1.5). **Always resolves** — the server answers
 * 204 whether or not the email is on the panel, so this screen cannot say either.
 */
export async function sendLink(email: string): Promise<void> {
  await apiClient.post('/auth/expert/sign-up', { email })
}

export async function signIn(email: string, password: string): Promise<Session> {
  const session = await unwrap(apiClient.post<ApiResponse<Session>>('/auth/expert/sign-in', { email, password }))
  setPortalToken(session.token)
  return session
}

export async function setPassword(token: string, password: string): Promise<Session> {
  const session = await unwrap(apiClient.post<ApiResponse<Session>>('/auth/expert/set-password', { token, password }))
  setPortalToken(session.token)
  return session
}

/** Every deliberate refusal is one 400; 429 is the limiter; anything else is ours. */
export function authFailureMessage(status: number | undefined, refused: string): string {
  if (status === 429) return 'Too many attempts. Please wait a minute and try again.'
  if (status === 400) return refused
  return 'Something went wrong on our side. Please try again in a moment.'
}
