import { type FormEvent, type ReactNode, useState } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { Button } from '@shared/components/ui/button'
import { Card } from '@shared/components/ui/card'
import { FormField } from '@shared/components/common/FormField'
import { Input } from '@shared/components/ui/input'
import { Logo } from '@shared/components/common/Logo'
import { statusOf } from '@shared/services/apiClient'
import {
  authFailureMessage,
  forgotPassword,
  identify,
  LINK_LIFETIME,
  signIn,
  SUPPORT_EMAIL,
  type IdentifyState,
} from '@/services/authService'

/**
 * One email field that decides what the rest of this screen looks like (Unit 42).
 *
 * **The password field is revealed in place, never a navigation.** `identify` is the server
 * answering what this address can do next; routing to a second URL for "you have a password"
 * would be this screen forming a second opinion about that answer.
 *
 * **Editing the email after an answer clears it.** The four branches below are about the address
 * currently in the box — keeping `NO_PASSWORD` on screen while someone types a different address
 * would be a stale answer wearing a live-looking form.
 *
 * **Every `IdentifyState` must be accounted for, and `stateBlock` is what enforces it.** A state
 * nothing handles is not a missing sentence — it is a screen with no message and no way forward.
 * `MAIL_UNAVAILABLE` was exactly that until review found it, past a comment saying a new state
 * would "fail loudly". A comment cannot fail; a `Record<IdentifyState, …>` can, so the rule is a
 * type.
 *
 * <h2>The UX pass of 2026-09-17: four defects, not four opinions</h2>
 *
 * **1. There was no way to register from this screen.** "Create an account" appeared only once
 * the server had answered `UNKNOWN` — so a client who had never registered had to type an
 * address, submit it, and be told we had never heard of them BEFORE being shown the thing they
 * came for. Discovering the front door by failing at the back one is not a flow. It is a
 * permanent line under the form now, which is where thirty years of auth screens have trained
 * people to look.
 *
 * **2. `NO_PASSWORD` and `MAIL_UNAVAILABLE` rendered a sentence and no control.** The submit
 * button renders only for `null` and `PASSWORD_SET`, so both states left a filled-in form with
 * nothing clickable and the browser Back button as the only exit. Each ends in an action now.
 *
 * **3. "Please contact us" named no way to contact us.** See {@link SUPPORT_EMAIL}.
 *
 * **4. This was the only auth screen with no {@link Logo}.** `Welcome` and `SignUp` both carry
 * one, so the most-visited of the three was the one that looked like it belonged to nobody.
 */
export default function SignIn() {
  const navigate = useNavigate()

  // `SignUp` forwards the address somebody has already typed — on `PASSWORD_SET` ("you already
  // have an account"), and on the two links back. The round trip between these two screens is
  // the most common thing a confused client does, and making them retype it each way is the
  // small insult at the end of being sent somewhere else.
  const forwarded = (useLocation().state as { email?: string } | null)?.email ?? ''

  const [email, setEmail] = useState(forwarded)
  const [state, setState] = useState<IdentifyState | null>(null)
  const [identifying, setIdentifying] = useState(false)
  const [identifyError, setIdentifyError] = useState<string | undefined>()

  const [password, setPassword] = useState('')
  const [signingIn, setSigningIn] = useState(false)
  const [signInError, setSignInError] = useState<string | undefined>()

  const [sendingReset, setSendingReset] = useState(false)
  const [resetSent, setResetSent] = useState(false)

  function onEmailChange(value: string) {
    setEmail(value)
    // A changed address invalidates whatever the last identify() answered.
    setState(null)
    setIdentifyError(undefined)
    setResetSent(false)
    // And a password typed against one address must not survive into another: without this, a
    // wrong-password error (or the password itself) from a prior PASSWORD_SET reappears the
    // moment a new identify() lands on PASSWORD_SET again — reporting a failed attempt that
    // never happened, against whatever address is now in the box.
    setSignInError(undefined)
    setPassword('')
  }

  /** Puts the cursor back on the only decision a dead-ended state leaves: which address. */
  function startOver() {
    onEmailChange('')
    document.getElementById('email')?.focus()
  }

  async function onIdentify(event: FormEvent) {
    event.preventDefault()
    setIdentifying(true)
    setIdentifyError(undefined)
    try {
      setState(await identify(email.trim()))
    } catch (error) {
      // A 400 here is the request being rejected, not the address being unknown — an unknown
      // address is a 200 answering UNKNOWN, which is the whole point of asking first.
      setIdentifyError(authFailureMessage(statusOf(error), 'Please enter a valid email address.'))
    } finally {
      setIdentifying(false)
    }
  }

  async function onSignIn(event: FormEvent) {
    event.preventDefault()
    setSigningIn(true)
    setSignInError(undefined)
    try {
      await signIn(email.trim(), password)
      navigate('/dashboard')
    } catch (error) {
      // **One message, because the server sends one refusal.** A wrong password, an account with
      // no password and an unknown email are deliberately indistinguishable here (`identify` is
      // where the difference is told, once, under the limiter) — so this must not guess which it
      // was, and must not send the client off to look for a link.
      setSignInError(
        authFailureMessage(statusOf(error), "That email and password don't match. Please try again."),
      )
    } finally {
      setSigningIn(false)
    }
  }

  async function onForgotPassword() {
    setSendingReset(true)
    try {
      await forgotPassword(email.trim())
    } catch {
      // Deliberately swallowed, not just unhandled: the message must not vary between a known
      // and an unknown email, and that includes not varying when the request itself fails — a
      // visible error here would be the one signal an enumeration attempt is looking for.
    } finally {
      setSendingReset(false)
      setResetSent(true)
    }
  }

  /**
   * What each answer looks like on screen. **Total over `IdentifyState` on purpose** — a fifth
   * state will not compile until somebody decides what it shows.
   *
   * Built inside the component rather than at module scope because three of the four now name the
   * address the client typed. **That is not an enumeration leak**: it is the value in the box in
   * front of them, echoed back. What distinguishes a known address from an unknown one is the
   * state itself, and `identify` discloses that deliberately, contained by the per-IP limiter.
   * `forgotPassword` is the route that must not differentiate, and its copy below still does not.
   */
  const stateBlock: Record<IdentifyState, ReactNode> = {
    // Renders the password field instead; see the form below.
    PASSWORD_SET: null,

    NO_PASSWORD: (
      <div className="space-y-3 rounded-md bg-muted/50 p-3">
        <p className="text-sm text-foreground">
          You&rsquo;re in our system, but haven&rsquo;t set a password yet. We&rsquo;ve emailed a
          link to <span className="font-medium">{email.trim()}</span> — open it to set one.
        </p>
        <p className="text-xs text-muted-foreground">
          It expires in {LINK_LIFETIME}. If it hasn&rsquo;t arrived, check your spam folder.
        </p>
        <Button type="button" variant="outline" className="w-full" onClick={startOver}>
          Use a different email
        </Button>
      </div>
    ),

    // **Known client, nothing sent, nothing minted — so no link is coming.** Deliberately not the
    // same words as NO_PASSWORD: the two differ in what the client should do next, and "wait for
    // an inbox" is the one answer that never recovers on its own. Waiting for a mail nobody sent
    // is the worst outcome this screen can produce, so this state says so in as many words.
    MAIL_UNAVAILABLE: (
      <div className="space-y-3 rounded-md bg-muted/50 p-3">
        <p className="text-sm text-foreground">
          You&rsquo;re in our system, but we can&rsquo;t email you a set-password link right now.{' '}
          <span className="font-medium">No email has been sent</span>, so there&rsquo;s nothing to
          wait for — get in touch and we&rsquo;ll get you in.
        </p>
        <Button asChild variant="outline" className="w-full">
          <a
            href={`mailto:${SUPPORT_EMAIL}?subject=${encodeURIComponent('Help signing in to the client portal')}`}
          >
            Email {SUPPORT_EMAIL}
          </a>
        </Button>
      </div>
    ),

    UNKNOWN: (
      <div className="space-y-3 rounded-md bg-muted/50 p-3">
        <p className="text-sm text-foreground">
          We couldn&rsquo;t find <span className="font-medium">{email.trim()}</span> in our system.
          Check it for typos, or create an account with it.
        </p>
        <Button
          type="button"
          variant="outline"
          className="w-full"
          onClick={() => navigate('/signup', { state: { email: email.trim() } })}
        >
          Create an account
        </Button>
      </div>
    ),
  }

  return (
    <div className="flex min-h-dvh flex-col items-center justify-center gap-8 px-4 py-12">
      <Logo size="lg" showTagline />

      <Card className="w-full max-w-sm space-y-5 p-6">
        <div>
          <h1 className="text-xl font-semibold tracking-tight text-foreground">Sign in</h1>
          <p className="mt-1 text-sm text-muted-foreground">Enter your email to continue.</p>
        </div>

        <form onSubmit={state === 'PASSWORD_SET' ? onSignIn : onIdentify} className="space-y-4">
          <FormField label="Email" htmlFor="email" required error={identifyError}>
            <Input
              id="email"
              type="email"
              autoComplete="email"
              required
              value={email}
              onChange={(event) => onEmailChange(event.target.value)}
              disabled={identifying || signingIn}
            />
          </FormField>

          {state === 'PASSWORD_SET' && (
            <div className="space-y-2">
              <FormField label="Password" htmlFor="password" required error={signInError}>
                <Input
                  id="password"
                  type="password"
                  autoComplete="current-password"
                  required
                  autoFocus
                  value={password}
                  onChange={(event) => setPassword(event.target.value)}
                  disabled={signingIn}
                />
              </FormField>
              <button
                type="button"
                onClick={() => void onForgotPassword()}
                disabled={sendingReset}
                className="text-xs font-medium text-primary underline-offset-4 hover:underline disabled:opacity-50"
              >
                Forgot password?
              </button>
              {resetSent && (
                // **Still deliberately vague about whether the address is known.** Unlike the
                // blocks above, this one is reached without the server having told us anything —
                // `forgot-password` answers 204 either way, and copy that firmed up here would
                // hand back the distinction that route exists to withhold. The expiry and the
                // spam hint are safe: they are true of any link we would have sent.
                <p className="text-xs text-muted-foreground">
                  If that email is in our system, we&rsquo;ve sent a reset link. It expires in{' '}
                  {LINK_LIFETIME} — check your spam folder if it hasn&rsquo;t arrived.
                </p>
              )}
            </div>
          )}

          {state && stateBlock[state]}

          {(state === null || state === 'PASSWORD_SET') && (
            <Button type="submit" className="w-full" loading={identifying || signingIn}>
              {state === 'PASSWORD_SET' ? 'Sign in' : 'Continue'}
            </Button>
          )}
        </form>

        {/*
          **Permanent, not conditional, and that is the whole fix.** This lived inside the
          `UNKNOWN` branch, so the only way to learn you could register was to submit an address
          and be told we had never heard of you. Hidden on `UNKNOWN` alone, because that state
          already renders the same offer as a button two lines up.
        */}
        {state !== 'UNKNOWN' && (
          <p className="border-t border-border pt-4 text-center text-sm text-muted-foreground">
            New here?{' '}
            <Link
              to="/signup"
              state={{ email: email.trim() }}
              className="font-medium text-primary underline-offset-4 hover:underline"
            >
              Create an account
            </Link>
          </p>
        )}
      </Card>
    </div>
  )
}
