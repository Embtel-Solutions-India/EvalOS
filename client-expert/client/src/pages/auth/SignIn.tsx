import { type FormEvent, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Button } from '@shared/components/ui/button'
import { Card } from '@shared/components/ui/card'
import { FormField } from '@shared/components/common/FormField'
import { Input } from '@shared/components/ui/input'
import { statusOf } from '@shared/services/apiClient'
import {
  authFailureMessage,
  forgotPassword,
  identify,
  signIn,
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
 * **Every `IdentifyState` must be accounted for, and `STATE_MESSAGE` is what enforces it.** The
 * submit button renders only for `null` and `PASSWORD_SET`, so a state nothing handles is not a
 * missing sentence — it is a screen with no message and no way forward. `MAIL_UNAVAILABLE` was
 * exactly that until review found it, past a comment saying a new state would "fail loudly".
 * A comment cannot fail; a `Record<IdentifyState, …>` can, so the rule is a type now.
 *
 * @see STATE_MESSAGE
 */

/**
 * The states whose entire answer is a sentence. `null` means "this one renders its own block
 * below" — a deliberate opt-out, which is still an entry, which is the point: a fifth
 * `IdentifyState` will not compile until somebody decides which of the two it is.
 */
const STATE_MESSAGE: Record<IdentifyState, string | null> = {
  PASSWORD_SET: null,
  UNKNOWN: null,
  NO_PASSWORD: "You're in our system, but haven't set a password yet. We've emailed you a link to set one.",
  // Known client, nothing sent, and nothing minted — so no link is coming and there is no action
  // this screen can offer. Separate copy from NO_PASSWORD precisely because the two differ in what
  // the client should do next: wait for an inbox, or stop waiting. "Wait" is the one answer that
  // never recovers on its own.
  MAIL_UNAVAILABLE:
    "You're in our system, but we can't send you a set-password link right now. Please contact us and we'll get you in.",
}

export default function SignIn() {
  const navigate = useNavigate()

  const [email, setEmail] = useState('')
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

  return (
    <div className="flex min-h-dvh flex-col items-center justify-center px-4 py-12">
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
                <p className="text-xs text-muted-foreground">
                  If that email is in our system, we've sent a reset link.
                </p>
              )}
            </div>
          )}

          {state && STATE_MESSAGE[state] && (
            <p className="text-sm text-muted-foreground">{STATE_MESSAGE[state]}</p>
          )}

          {state === 'UNKNOWN' && (
            <div className="space-y-3">
              <p className="text-sm text-muted-foreground">We couldn't find that email.</p>
              <Button
                type="button"
                variant="outline"
                className="w-full"
                onClick={() => navigate('/start', { state: { email: email.trim() } })}
              >
                Start a new evaluation
              </Button>
            </div>
          )}

          {(state === null || state === 'PASSWORD_SET') && (
            <Button type="submit" className="w-full" loading={identifying || signingIn}>
              {state === 'PASSWORD_SET' ? 'Sign in' : 'Continue'}
            </Button>
          )}
        </form>
      </Card>
    </div>
  )
}
