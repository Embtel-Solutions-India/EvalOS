import { type FormEvent, useState } from 'react'
import { ArrowLeft } from 'lucide-react'
import { Link, useNavigate } from 'react-router-dom'
import { z } from 'zod'
import { Button } from '@shared/components/ui/button'
import { Card } from '@shared/components/ui/card'
import { FormField } from '@shared/components/common/FormField'
import { Input } from '@shared/components/ui/input'
import { Logo } from '@shared/components/common/Logo'
import { NO_TOKEN, tokenFromFragment } from '@shared/lib/portal'
import { statusOf } from '@shared/services/apiClient'
import { PASSWORD_REQUIREMENTS, passwordRules } from '@/schemas/intake'
import { authFailureMessage, setPassword } from '@/services/authService'

/**
 * Where a set-password link lands (Unit 42) — a `NO_PASSWORD` reply's email, or a reset link.
 *
 * **`passwordRules` is imported, not restated.** It is the one place the strength rule (length,
 * case, digit, special character) is written; a second copy here would be a second rule to keep
 * in sync with the intake funnel's. The confirm-match check is wired up fresh because this form's
 * two fields are not `aboutYouSchema`'s — the check itself is one line, not a rule to duplicate.
 * {@link PASSWORD_REQUIREMENTS} follows the same rule for the same reason: the sentence that
 * describes the rule lives beside the rule.
 *
 * <h2>The UX pass of 2026-09-17</h2>
 *
 * **The no-token state was a different screen wearing the same URL.** It rendered a bare
 * `PageHeader` in a `max-w-2xl` block — no card, no logo, no button — while every sibling auth
 * screen is a centred card under a logo. Someone whose mail client truncated the `#` fragment (a
 * real and common thing: the token IS the fragment) landed on something that looked broken, and
 * then had nowhere to click. It is the same card as its siblings now, and it ends in the action
 * that actually recovers: go back and ask for a fresh link.
 *
 * **The rules were revealed one rejection at a time.** Five `.regex` calls, surfaced only after a
 * submit, meant choosing a password could take five round trips to discover what was wanted. They
 * are stated under the field before the first attempt.
 *
 * **A refused link said "request a new one from the sign-in screen" and did not link to it.**
 */
const schema = z
  .object({
    password: passwordRules,
    confirmPassword: z.string().min(1, 'Please confirm your password.'),
  })
  .refine((data) => data.password === data.confirmPassword, {
    message: 'Passwords do not match.',
    path: ['confirmPassword'],
  })

/** The card every state of this screen sits in, so a dead end looks like the same product. */
function AuthCard({ title, description, children }: {
  title: string
  description: string
  children: React.ReactNode
}) {
  return (
    <div className="flex min-h-dvh flex-col items-center justify-center gap-8 px-4 py-12">
      <Logo size="lg" showTagline />
      <Card className="w-full max-w-sm space-y-5 p-6">
        <div>
          <h1 className="text-xl font-semibold tracking-tight text-foreground">{title}</h1>
          <p className="mt-1 text-sm text-muted-foreground">{description}</p>
        </div>
        {children}
      </Card>
    </div>
  )
}

/** Always available, because every failure on this screen recovers the same way. */
function BackToSignIn() {
  return (
    <Link
      to="/signin"
      className="inline-flex items-center gap-2 text-sm font-medium text-primary underline-offset-4 hover:underline"
    >
      <ArrowLeft className="h-4 w-4" aria-hidden="true" />
      Back to sign in
    </Link>
  )
}

export default function SetPassword() {
  const navigate = useNavigate()
  const token = tokenFromFragment(window.location.hash)

  const [password, setPasswordValue] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')
  const [fieldErrors, setFieldErrors] = useState<{ password?: string; confirmPassword?: string }>({})
  const [submitting, setSubmitting] = useState(false)
  const [submitError, setSubmitError] = useState<string | undefined>()

  if (!token) {
    return (
      <AuthCard title="Set your password" description={NO_TOKEN}>
        {/*
          **A button, not just the sentence.** The client cannot fix a truncated link from here,
          so the only real recovery is asking for a fresh one — which is what the sign-in screen
          does on `NO_PASSWORD`. Naming that as the action beats leaving them to infer it.
        */}
        <Button className="w-full" onClick={() => navigate('/signin')}>
          Request a new link
        </Button>
      </AuthCard>
    )
  }

  async function onSubmit(event: FormEvent) {
    event.preventDefault()
    const result = schema.safeParse({ password, confirmPassword })
    if (!result.success) {
      const errors = result.error.flatten().fieldErrors
      setFieldErrors({ password: errors.password?.[0], confirmPassword: errors.confirmPassword?.[0] })
      return
    }
    setFieldErrors({})
    setSubmitError(undefined)
    setSubmitting(true)
    try {
      // The returned token is already in the API client — setPassword() puts it there. `token`
      // is narrowed to `string` by the guard above, but not across this closure boundary.
      await setPassword(token as string, password)
      navigate('/dashboard')
    } catch (error) {
      // The server answers one 400 for every reason a link does not work — spent, expired,
      // unknown, another brand's — so this says one thing back. "Request a new one" is the only
      // action available, and it is always the right one.
      setSubmitError(
        authFailureMessage(
          statusOf(error),
          'This link is no longer valid. It may have been used already, or it may have expired. ' +
            'Please request a new one from the sign-in screen.',
        ),
      )
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <AuthCard title="Set your password" description="Set the password you'll sign in with from now on.">
      <form onSubmit={(event) => void onSubmit(event)} className="space-y-4">
        <FormField label="Password" htmlFor="password" required error={fieldErrors.password}>
          <Input
            id="password"
            type="password"
            autoComplete="new-password"
            aria-describedby="password-requirements"
            value={password}
            onChange={(event) => setPasswordValue(event.target.value)}
            disabled={submitting}
          />
        </FormField>

        {/*
          Before the first submit, and wired to the field with `aria-describedby` so a screen
          reader hears the requirement when it reaches the input rather than after failing it.
        */}
        <p id="password-requirements" className="text-xs text-muted-foreground">
          {PASSWORD_REQUIREMENTS}
        </p>

        <FormField
          label="Confirm password"
          htmlFor="confirmPassword"
          required
          error={fieldErrors.confirmPassword}
        >
          <Input
            id="confirmPassword"
            type="password"
            autoComplete="new-password"
            value={confirmPassword}
            onChange={(event) => setConfirmPassword(event.target.value)}
            disabled={submitting}
          />
        </FormField>

        {submitError && (
          <div className="space-y-2" role="alert">
            <p className="text-xs font-medium text-destructive">{submitError}</p>
            {/* The message names the sign-in screen; this is the screen it names. */}
            <BackToSignIn />
          </div>
        )}

        <Button type="submit" className="w-full" loading={submitting}>
          Set password
        </Button>
      </form>
    </AuthCard>
  )
}
