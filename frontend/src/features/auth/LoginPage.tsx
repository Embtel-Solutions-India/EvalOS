import { useState } from 'react'
import { useAuth } from '../../lib/authContext'

type Submit = { status: 'idle' } | { status: 'sending' } | { status: 'failed'; message: string }

/**
 * The only unauthenticated staff screen. It reports one generic failure for every
 * rejection, matching the backend's `INVALID_CREDENTIALS` — a wrong password and an
 * unknown address must not be distinguishable here either.
 */
export default function LoginPage() {
  const { login } = useAuth()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [submit, setSubmit] = useState<Submit>({ status: 'idle' })

  async function onSubmit(event: React.FormEvent) {
    event.preventDefault()
    setSubmit({ status: 'sending' })
    try {
      await login(email, password)
    } catch {
      setSubmit({ status: 'failed', message: 'Email or password is incorrect.' })
    }
  }

  return (
    <div className="flex min-h-svh items-center justify-center px-6" style={{ background: 'var(--bg-base)' }}>
      <form
        onSubmit={onSubmit}
        className="w-full max-w-sm rounded-xl border p-6"
        style={{ background: 'var(--bg-surface)', borderColor: 'var(--border-default)' }}
      >
        {/* The product mark, drawn like the sidebar's brand tile so the two read as one system.
            The login is the one screen before any brand is known, so it carries EvalOS itself. */}
        <div className="mb-6 flex items-center gap-2.5">
          <span
            aria-hidden
            className="grid h-10 w-10 place-items-center text-lg font-bold text-white"
            style={{ background: 'var(--accent-primary)', borderRadius: 'var(--radius-md)' }}
          >
            E
          </span>
          <span className="text-xl font-semibold tracking-tight">
            Eval<span style={{ color: 'var(--accent-primary)' }}>OS</span>
          </span>
        </div>

        <h1 className="text-lg font-semibold tracking-tight">Sign in</h1>
        <p className="mt-1 text-sm" style={{ color: 'var(--text-muted)' }}>
          Back-of-house production. Staff accounts only.
        </p>

        <label className="mt-6 block text-sm font-medium">
          Email
          <input
            type="email"
            required
            autoComplete="username"
            value={email}
            onChange={(event) => setEmail(event.target.value)}
            className="mt-1.5 w-full rounded-md border px-3 py-2 text-sm font-normal"
            style={{ borderColor: 'var(--border-default)', background: 'var(--bg-surface)' }}
          />
        </label>

        <label className="mt-4 block text-sm font-medium">
          Password
          <input
            type="password"
            required
            autoComplete="current-password"
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            className="mt-1.5 w-full rounded-md border px-3 py-2 text-sm font-normal"
            style={{ borderColor: 'var(--border-default)', background: 'var(--bg-surface)' }}
          />
        </label>

        {submit.status === 'failed' && (
          <p
            className="mt-4 rounded-md px-3 py-2 text-sm"
            style={{ background: 'var(--status-red-bg)', color: 'var(--status-red)' }}
            role="alert"
          >
            {submit.message}
          </p>
        )}

        <button
          type="submit"
          disabled={submit.status === 'sending'}
          className="mt-6 w-full rounded-md px-3 py-2 text-sm font-semibold text-white disabled:opacity-60"
          style={{ background: 'var(--accent-primary)' }}
        >
          {submit.status === 'sending' ? 'Signing in…' : 'Sign in'}
        </button>
      </form>
    </div>
  )
}
