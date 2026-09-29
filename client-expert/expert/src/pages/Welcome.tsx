import { type FormEvent, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Button } from '@shared/components/ui/button'
import { Card } from '@shared/components/ui/card'
import { Input } from '@shared/components/ui/input'
import { FormField } from '@shared/components/common/FormField'
import { Logo } from '@shared/components/common/Logo'
import { statusOf } from '@shared/services/apiClient'
import { authFailureMessage, sendLink, signIn } from '@/services/expertAuthService'

const REFUSED = "That email and password don't match. Please try again."

/**
 * The expert portal's front door (Unit 59, D23): sign in, or ask for a link.
 *
 * **"First time here" and "forgot your password" are one button**, because they are one act for an
 * expert: whoever is on the panel gets a set-password or reset link at the address on the roster.
 * The screen says the same thing whatever the email was, because the server does.
 */
export default function Welcome() {
  const navigate = useNavigate()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string>()
  const [linkSent, setLinkSent] = useState(false)

  async function onSignIn(event: FormEvent) {
    event.preventDefault()
    setBusy(true)
    setError(undefined)
    try {
      await signIn(email.trim(), password)
      navigate('/dashboard')
    } catch (failure) {
      setError(authFailureMessage(statusOf(failure), REFUSED))
    } finally {
      setBusy(false)
    }
  }

  async function onSendLink() {
    setBusy(true)
    setError(undefined)
    try {
      await sendLink(email.trim())
      setLinkSent(true)
    } catch (failure) {
      setError(authFailureMessage(statusOf(failure), 'Please enter a valid email address.'))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="flex min-h-dvh flex-col items-center justify-center gap-8 px-4 py-12">
      <div className="flex flex-col items-center gap-1">
        <Logo size="lg" />
        <span className="text-[11px] font-medium uppercase tracking-wide text-muted-foreground">Expert Portal</span>
      </div>

      <Card className="w-full max-w-md space-y-4 p-6">
        <h1 className="text-base font-semibold text-foreground">Sign in</h1>
        {linkSent ? (
          <p className="text-sm text-muted-foreground" role="status">
            If that address is on our expert panel, a link to set your password is on its way. It works once and
            expires in 30 minutes. Nothing arrived? Use the email you joined the panel with — we invite you to
            sign up once you are hired.
          </p>
        ) : (
          <form className="space-y-4" onSubmit={(event) => void onSignIn(event)}>
            <FormField label="Email" htmlFor="email">
              <Input id="email" type="email" autoComplete="email" required value={email}
                onChange={(event) => setEmail(event.target.value)} />
            </FormField>
            <FormField label="Password" htmlFor="password" error={error}>
              <Input id="password" type="password" autoComplete="current-password" value={password}
                onChange={(event) => setPassword(event.target.value)} />
            </FormField>
            <Button type="submit" className="w-full" disabled={busy || !email.trim() || !password}>
              {busy ? 'Signing in…' : 'Sign in'}
            </Button>
            <div className="border-t pt-4 text-center">
              <p className="mb-2 text-xs text-muted-foreground">First time here, or forgot your password?</p>
              <Button type="button" variant="outline" className="w-full" disabled={busy || !email.trim()}
                onClick={() => void onSendLink()}>
                Email me a link
              </Button>
            </div>
          </form>
        )}
      </Card>
    </div>
  )
}
