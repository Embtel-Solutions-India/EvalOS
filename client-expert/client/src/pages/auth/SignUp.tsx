import { type FormEvent, useState } from 'react'
import { ArrowLeft } from 'lucide-react'
import { Link, useLocation } from 'react-router-dom'
import { Button } from '@shared/components/ui/button'
import { Card } from '@shared/components/ui/card'
import { FormField } from '@shared/components/common/FormField'
import { Input } from '@shared/components/ui/input'
import { Logo } from '@shared/components/common/Logo'
import { statusOf } from '@shared/services/apiClient'
import { authFailureMessage, signUp, type IdentifyState } from '@/services/authService'

/**
 * The other door: a client EvalOS has never heard of, creating their own account.
 *
 * **This replaces `Start.tsx`, which was a placeholder apologising for its own absence.** Two
 * shipped screens linked at it — `/welcome`'s card and `SignIn`'s `UNKNOWN` branch, the second
 * offered to somebody who has just been told their email is not in our system — and what they
 * reached said we could not take them. Nothing created a `client_account` at runtime, so that
 * apology was accurate: every row in the table came from one backfill migration.
 *
 * **It creates an account, not an evaluation.** Choosing a service, answering the questionnaire
 * and opening the opportunity are Unit 43 and are reached from the dashboard afterwards. The
 * copy below says so rather than implying a request has been placed.
 *
 * **Nobody is signed in from here, and the screen has no password field.** The email may be one
 * GHL already holds — a client Sales logged last week — and their cases sit behind it. So every
 * path ends at the emailed set-password link, which is the only thing that proves the mailbox.
 * That is the same door every client seeded by `V45` comes through, which is why this screen's
 * answers are `SignIn`'s answers.
 */

/**
 * The three the server can answer, and what each one means to somebody who just signed up.
 *
 * `UNKNOWN` is absent because it is unreachable: the account exists by the time the server
 * replies. A `Record` over the reachable three rather than over `IdentifyState` would not
 * compile against a fifth state, which is the whole point of `SignIn`'s table — so this keeps
 * the full key set and marks the impossible one.
 */
const OUTCOME: Record<IdentifyState, string> = {
  NO_PASSWORD: "You're all set. We've emailed you a link to choose a password — open it and you'll be signed in.",
  // They typed an address we already hold. Not an error, and deliberately not phrased as one:
  // signing up twice is the most ordinary thing a person does when they cannot remember.
  PASSWORD_SET: 'You already have an account with us. Sign in with your password instead.',
  MAIL_UNAVAILABLE:
    "Your account is created, but we can't send you a link to set a password right now. Please contact us and we'll get you in.",
  // Unreachable — the account exists by the time this screen has an answer. Present so the
  // Record stays total.
  UNKNOWN: 'Something went wrong on our side. Please try again in a moment.',
}

export default function SignUp() {
  // `SignIn` forwards the address somebody has already typed once. Retyping it to be told the
  // same thing is the small insult at the end of being told we do not know you.
  const forwarded = (useLocation().state as { email?: string } | null)?.email ?? ''

  const [email, setEmail] = useState(forwarded)
  const [firstName, setFirstName] = useState('')
  const [lastName, setLastName] = useState('')
  const [phone, setPhone] = useState('')

  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | undefined>()
  const [outcome, setOutcome] = useState<IdentifyState | null>(null)

  async function onSubmit(event: FormEvent) {
    event.preventDefault()
    setSubmitting(true)
    setError(undefined)
    try {
      setOutcome(
        await signUp({
          email: email.trim(),
          // Empty strings are not absent values: the server would store "" as a name. Trimmed to
          // undefined so an untouched field stays untouched in GHL too.
          firstName: firstName.trim() || undefined,
          lastName: lastName.trim() || undefined,
          phone: phone.trim() || undefined,
        }),
      )
    } catch (caught) {
      // A 502 is the one failure worth its own words here: it means GHL refused or is
      // unreachable, and the server deliberately creates no account in that case rather than
      // leaving a lead no salesperson can see. Retrying is the right advice.
      setError(
        statusOf(caught) === 502
          ? "We couldn't reach our systems to set you up. Please try again in a few minutes."
          : authFailureMessage(statusOf(caught), 'Please enter a valid email address.'),
      )
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div className="flex min-h-dvh flex-col items-center justify-center gap-8 px-4 py-12">
      <Logo size="lg" showTagline />

      <Card className="w-full max-w-sm space-y-5 p-6">
        <div>
          <h1 className="text-xl font-semibold tracking-tight text-foreground">Create your account</h1>
          <p className="mt-1 text-sm text-muted-foreground">
            Once you're in, you can request an evaluation, send us your documents and follow where
            your case has got to.
          </p>
        </div>

        {outcome ? (
          <div className="space-y-4">
            <p className="text-sm text-muted-foreground">{OUTCOME[outcome]}</p>
            <Link
              to="/signin"
              className="inline-flex items-center gap-2 text-sm font-medium text-primary underline-offset-4 hover:underline"
            >
              <ArrowLeft className="h-4 w-4" aria-hidden="true" />
              Back to sign in
            </Link>
          </div>
        ) : (
          <form onSubmit={onSubmit} className="space-y-4">
            <FormField label="Email" htmlFor="email" required error={error}>
              <Input
                id="email"
                type="email"
                autoComplete="email"
                required
                value={email}
                onChange={(event) => setEmail(event.target.value)}
                disabled={submitting}
              />
            </FormField>

            <div className="grid grid-cols-2 gap-3">
              <FormField label="First name" htmlFor="firstName">
                <Input
                  id="firstName"
                  autoComplete="given-name"
                  value={firstName}
                  onChange={(event) => setFirstName(event.target.value)}
                  disabled={submitting}
                />
              </FormField>
              <FormField label="Last name" htmlFor="lastName">
                <Input
                  id="lastName"
                  autoComplete="family-name"
                  value={lastName}
                  onChange={(event) => setLastName(event.target.value)}
                  disabled={submitting}
                />
              </FormField>
            </div>

            <FormField label="Phone" htmlFor="phone">
              <Input
                id="phone"
                type="tel"
                autoComplete="tel"
                value={phone}
                onChange={(event) => setPhone(event.target.value)}
                disabled={submitting}
              />
            </FormField>

            <Button type="submit" className="w-full" loading={submitting}>
              Create account
            </Button>

            <Link
              to="/signin"
              className="inline-flex items-center gap-2 text-sm font-medium text-primary underline-offset-4 hover:underline"
            >
              <ArrowLeft className="h-4 w-4" aria-hidden="true" />
              I already have an account
            </Link>
          </form>
        )}
      </Card>
    </div>
  )
}
