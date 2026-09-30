import { type FormEvent, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { Button } from '@shared/components/ui/button'
import { Card } from '@shared/components/ui/card'
import { Input } from '@shared/components/ui/input'
import { FormField } from '@shared/components/common/FormField'
import { tokenFromFragment } from '@shared/lib/portal'
import { passwordRules } from '@shared/schemas/auth'
import { statusOf } from '@shared/services/apiClient'
import { authFailureMessage, setPassword } from '@/services/expertAuthService'

const SPENT = 'This link is no longer valid — it may have been used or expired. Ask for a new one from the sign-in page.'

/** The emailed link lands here (`/set-password#token`): choose a password, and be signed in. */
export default function SetPassword() {
  const navigate = useNavigate()
  const [token] = useState(() => tokenFromFragment(window.location.hash))
  const [password, setValue] = useState('')
  const [error, setError] = useState<string>()
  const [busy, setBusy] = useState(false)

  async function onSubmit(event: FormEvent) {
    event.preventDefault()
    const rule = passwordRules.safeParse(password)
    if (!rule.success) {
      setError(rule.error.issues[0]?.message)
      return
    }
    setBusy(true)
    setError(undefined)
    try {
      await setPassword(token as string, password)
      navigate('/dashboard')
    } catch (failure) {
      setError(authFailureMessage(statusOf(failure), SPENT))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="flex flex-1 items-center justify-center px-4 py-12">
      <Card className="w-full max-w-md space-y-4 p-6">
        <h1 className="text-base font-semibold text-foreground">Set your password</h1>
        {!token ? (
          <p className="text-sm text-muted-foreground">
            This page needs the full link from the email. <Link className="underline" to="/">Ask for a new one</Link>.
          </p>
        ) : (
          <form className="space-y-4" onSubmit={(event) => void onSubmit(event)}>
            <FormField label="New password" htmlFor="password" error={error}
              hint="At least 8 characters, with an uppercase letter, a lowercase letter, a number and a special character.">
              <Input id="password" type="password" autoComplete="new-password" value={password}
                onChange={(event) => setValue(event.target.value)} />
            </FormField>
            <Button type="submit" className="w-full" disabled={busy || !password}>
              {busy ? 'Saving…' : 'Set password and sign in'}
            </Button>
          </form>
        )}
      </Card>
    </div>
  )
}
