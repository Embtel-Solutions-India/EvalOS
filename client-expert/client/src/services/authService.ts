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
 * The four answers the sign-in screen branches on. The server's vocabulary, unmapped — the same
 * rule `CHECKLIST_STATUS` and `APPROVAL_STATUS` already follow, so a fifth value fails loudly
 * rather than falling into a default branch.
 *
 * **`MAIL_UNAVAILABLE` was missing here while the server already sent it**, which is the failure
 * that rule is supposed to prevent and did not, because a union is only as loud as the switch
 * reading it. The screen had branches for the other three and gated its submit button on
 * `PASSWORD_SET`, so this state rendered an email box with no message and no button — a dead end,
 * and not a rare one: `evalos.mail.from` is blank by default and `ClientMailer` degrades rather
 * than failing, so every seeded client in a mail-less environment lands exactly here.
 */
/**
 * Where a client is sent when the portal cannot help them itself.
 *
 * **It exists because "please contact us" did not resolve to anything.** Two screens said it —
 * `SignIn` and `SignUp`, both on `MAIL_UNAVAILABLE`, which is the one state where the portal has
 * no action left to offer — and neither gave an address, a number or a link. A dead end that
 * *sounds* like a way forward is worse than one that admits it: the client believes they have
 * somewhere to go and finds out they do not.
 *
 * One constant, because the day this address changes it must not be a search for prose.
 */
export const SUPPORT_EMAIL = 'support@internationalevaluations.com'

/**
 * How long a set-password or reset link lives, as the client is told it.
 *
 * Mirrors `evalos.portal.credential-ttl` (30m locally and in prod). **Stated on screen rather
 * than left to the mail**, because the client's question after "check your inbox" is always
 * "how long do I have" — and the one who reads the mail an hour later needs to know the link is
 * dead rather than believe the portal is broken.
 */
export const LINK_LIFETIME = '30 minutes'

export type IdentifyState = 'PASSWORD_SET' | 'NO_PASSWORD' | 'MAIL_UNAVAILABLE' | 'UNKNOWN'

export type Session = { token: string; expiresAt: string }

/**
 * What a new client sends. Only the email is required — GHL's contact upsert matches on email
 * then phone, and the server refuses a submission it cannot match on.
 */
export type SignUpDetails = {
  email: string
  firstName?: string
  lastName?: string
  phone?: string
}

/**
 * Create the account, or recognise the client who already had one.
 *
 * **Returns an `IdentifyState`, not a `Session`, and that is deliberate.** Signing up does not
 * sign you in: the address may be one GHL already holds, so a token here would be account
 * takeover by typing a stranger's email. Every path ends at the emailed set-password link, which
 * is the same door a seeded client comes through. `UNKNOWN` is the one answer this cannot give.
 */
export async function signUp(details: SignUpDetails): Promise<IdentifyState> {
  const { state } = await unwrap(
    apiClient.post<ApiResponse<{ state: IdentifyState }>>('/auth/sign-up', details),
  )
  return state
}

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

/**
 * What to tell somebody whose **sign-in** did not work.
 *
 * **A sibling of `failureMessage`, not a replacement, and the split is load-bearing.** That one is
 * written for a reader who arrived by opening a link, so every branch of it talks about links and
 * documents — and `portal.test.ts` pins it to never say "password" or "log in", because the client
 * it was written for had no account to log into. Unit 42 gave them one and the auth screens reused
 * it anyway, so a wrong password answered:
 *
 * > *"We could not load your documents. Please try again in a moment, or contact whoever sent you
 * > this link."*
 *
 * Wrong subject, and it sends someone who is typing a password off to find a link that does not
 * exist. Widening `failureMessage` to cover both readers is what that test exists to prevent, so
 * the auth screens get their own.
 *
 * **`refused` is the only thing that varies, so it is the only parameter.** The server answers
 * every deliberate refusal on these three routes with 400 — a wrong password, an account with no
 * password, an unknown email, a spent link, a link for another brand — and what to say about it is
 * the one thing that differs per screen. Everything else is the same sentence wherever you are.
 *
 * @param refused what a 400 means on this screen, in the client's words
 */
export function authFailureMessage(status: number | undefined, refused: string): string {
  // The per-IP limiter on /api/portal/** (PortalTokenFilter), which is also what contains the
  // enumeration `identify` deliberately allows. Worth its own words: it is the one failure here
  // that a client fixes by waiting rather than by retyping.
  if (status === 429) {
    return 'Too many attempts. Please wait a minute and try again.'
  }
  if (status === 400) {
    return refused
  }
  // 401 and 403 are not reachable: all four auth routes are permitAll. Anything else is ours, and
  // saying so beats implying the client mistyped something.
  return 'Something went wrong on our side. Please try again in a moment.'
}
