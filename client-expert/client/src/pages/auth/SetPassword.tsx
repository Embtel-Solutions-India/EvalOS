import { type FormEvent, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { z } from 'zod'
import { Button } from '@shared/components/ui/button'
import { Card } from '@shared/components/ui/card'
import { FormField } from '@shared/components/common/FormField'
import { PageHeader } from '@shared/components/common/PageHeader'
import { Input } from '@shared/components/ui/input'
import { NO_TOKEN, tokenFromFragment } from '@shared/lib/portal'
import { statusOf } from '@shared/services/apiClient'
import { passwordRules } from '@/schemas/intake'
import { authFailureMessage, setPassword } from '@/services/authService'

/**
 * Where a set-password link lands (Unit 42) — a `NO_PASSWORD` reply's email, or a reset link.
 *
 * **`passwordRules` is imported, not restated.** It is the one place the strength rule (length,
 * case, digit, special character) is written; a second copy here would be a second rule to keep
 * in sync with the intake funnel's. The confirm-match check is wired up fresh because this form's
 * two fields are not `aboutYouSchema`'s — the check itself is one line, not a rule to duplicate.
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
      <div className="mx-auto max-w-2xl p-6">
        <PageHeader title="Set your password" description={NO_TOKEN} />
      </div>
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
    <div className="flex min-h-dvh flex-col items-center justify-center px-4 py-12">
      <Card className="w-full max-w-sm space-y-5 p-6">
        <div>
          <h1 className="text-xl font-semibold tracking-tight text-foreground">Set your password</h1>
          <p className="mt-1 text-sm text-muted-foreground">Choose a password for signing in next time.</p>
        </div>

        <form onSubmit={(event) => void onSubmit(event)} className="space-y-4">
          <FormField label="Password" htmlFor="password" required error={fieldErrors.password}>
            <Input
              id="password"
              type="password"
              autoComplete="new-password"
              value={password}
              onChange={(event) => setPasswordValue(event.target.value)}
              disabled={submitting}
            />
          </FormField>

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
            <p className="text-xs font-medium text-destructive" role="alert">
              {submitError}
            </p>
          )}

          <Button type="submit" className="w-full" loading={submitting}>
            Set password
          </Button>
        </form>
      </Card>
    </div>
  )
}
