import { type FormEvent, type ReactNode, useState } from 'react'
import { ArrowLeft } from 'lucide-react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { LEGAL } from '@/constants/legal'
import { Button } from '@shared/components/ui/button'
import { Card } from '@shared/components/ui/card'
import { FormField } from '@shared/components/common/FormField'
import { Input } from '@shared/components/ui/input'
import { Logo } from '@shared/components/common/Logo'
import { statusOf } from '@shared/services/apiClient'
import {
  authFailureMessage,
  LINK_LIFETIME,
  signUp,
  SUPPORT_EMAIL,
  type IdentifyState,
} from '@/services/authService'

/**
 * The other door: a client EvalOS has never heard of, creating their own account.
 *
 * **This replaces `Start.tsx`, which was a placeholder apologising for its own absence.** Two
 * shipped screens linked at it — `/welcome`'s card and `SignIn`'s `UNKNOWN` branch, the second
 * offered to somebody who has just been told their email is not in our system — and what they
 * reached said we could not take them. Nothing created a `client_account` at runtime, so that
 * apology was accurate: every row in the table came from one backfill migration.
 *
 * **It creates an account, not an evaluation.** Choosing a service, sending the request and
 * opening the opportunity are Unit 43 and are reached from the dashboard afterwards. The
 * copy below says so rather than implying a request has been placed.
 *
 * **Nobody is signed in from here, and the screen has no password field.** The email may be one
 * GHL already holds — a client Sales logged last week — and their cases sit behind it. So every
 * path ends at the emailed set-password link, which is the only thing that proves the mailbox.
 * That is the same door every client seeded by `V45` comes through, which is why this screen's
 * answers are `SignIn`'s answers.
 *
 * <h2>The UX pass of 2026-09-17</h2>
 *
 * **Every outcome ended in the same grey text link.** "You already have an account" and "we could
 * not email you" are not the same situation, and offering one identical, low-contrast
 * `Back to sign in` for both left the client to work out which of them was a success. The one
 * outcome with an obvious next step — you already have a password, go and use it — now ends in a
 * primary button, and the one with no next step inside the portal ends in a way to reach a human
 * rather than in the words "contact us" with nothing attached.
 *
 * **"We've emailed you a link" never said where.** A client who typos their address reads a
 * success message and waits forever. It names the address now, which is also the cheapest way for
 * them to spot the typo themselves.
 */
export default function SignUp() {
  const navigate = useNavigate()

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

  /**
   * What the server can answer, and what each one means to somebody who just signed up.
   *
   * **Total over `IdentifyState`, including the unreachable one.** `UNKNOWN` cannot happen — the
   * account exists by the time the server replies — but narrowing the `Record` to the reachable
   * three would stop a fifth state from failing to compile, which is the only reason this table
   * is a `Record` at all.
   */
  const OUTCOME: Record<IdentifyState, ReactNode> = {
    NO_PASSWORD: (
      <>
        <p className="text-sm text-foreground">
          You&rsquo;re all set. We&rsquo;ve emailed a link to{' '}
          <span className="font-medium">{email.trim()}</span> — open it to set your password, and
          you&rsquo;ll be signed in.
        </p>
        <p className="text-xs text-muted-foreground">
          It expires in {LINK_LIFETIME}. If it hasn&rsquo;t arrived, check your spam folder.
        </p>
      </>
    ),

    // They typed an address we already hold. Not an error, and deliberately not phrased as one:
    // signing up twice is the most ordinary thing a person does when they cannot remember.
    PASSWORD_SET: (
      <>
        <p className="text-sm text-foreground">
          You already have an account with us. Sign in with your password instead.
        </p>
        <Button className="w-full" onClick={() => navigate('/signin', { state: { email: email.trim() } })}>
          Go to sign in
        </Button>
      </>
    ),

    // **The account exists and no link is coming.** Saying so plainly matters more here than
    // anywhere: this screen has just told somebody they are "created", and a client who believes
    // a mail is on its way will wait instead of getting in touch.
    MAIL_UNAVAILABLE: (
      <>
        <p className="text-sm text-foreground">
          Your account is created, but we can&rsquo;t email you a set-password link right now.{' '}
          <span className="font-medium">No email has been sent</span>, so there&rsquo;s nothing to
          wait for — get in touch and we&rsquo;ll get you in.
        </p>
        <Button asChild variant="outline" className="w-full">
          <a
            href={`mailto:${SUPPORT_EMAIL}?subject=${encodeURIComponent('Help setting up my client portal account')}`}
          >
            Email {SUPPORT_EMAIL}
          </a>
        </Button>
      </>
    ),

    // Unreachable — the account exists by the time this screen has an answer. Present so the
    // Record stays total.
    UNKNOWN: (
      <p className="text-sm text-foreground">
        Something went wrong on our side. Please try again in a moment.
      </p>
    ),
  }

  return (
    <div className="flex min-h-dvh flex-col items-center justify-center gap-8 px-4 py-12">
      <Logo size="lg" showTagline />

      <Card className="w-full max-w-sm space-y-5 p-6">
        <div>
          <h1 className="text-xl font-semibold tracking-tight text-foreground">Create your account</h1>
          <p className="mt-1 text-sm text-muted-foreground">
            Once you&rsquo;re in, you can request an evaluation, send us your documents and follow
            where your case has got to.
          </p>
        </div>

        {outcome ? (
          <div className="space-y-4">
            {OUTCOME[outcome]}
            {/*
              Secondary to whatever the outcome itself offers. `PASSWORD_SET` renders its own
              primary button above, so this stays a quiet link rather than competing with it.
            */}
            <Link
              to="/signin"
              state={{ email: email.trim() }}
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

            {/*
              **Said before the button, not after the account exists.** No password is chosen
              here — it is chosen from the emailed link — and a client who does not know that
              reads the missing password field as a broken form.
            */}
            <p className="text-xs text-muted-foreground">
              We&rsquo;ll email you a link to set your password.
            </p>

            <p className="text-xs text-muted-foreground">
              By creating an account you agree to our{' '}
              <Link to={LEGAL.privacy.to} className="font-medium text-primary underline-offset-4 hover:underline">
                {LEGAL.privacy.label}
              </Link>{' '}
              and acknowledge our{' '}
              <Link to={LEGAL.disclaimer.to} className="font-medium text-primary underline-offset-4 hover:underline">
                {LEGAL.disclaimer.label}
              </Link>
              .
            </p>

            <Button type="submit" className="w-full" loading={submitting}>
              Create account
            </Button>

            <Link
              to="/signin"
              state={{ email: email.trim() }}
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
