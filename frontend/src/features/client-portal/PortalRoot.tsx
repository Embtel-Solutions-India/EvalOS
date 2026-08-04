import { useEffect, useState } from 'react'
import ClientDraftView from './ClientDraftView'
import { fetchDraft, setPortalToken, statusOf } from './portalApi'
import { failureMessage, tokenFromFragment, type ClientDraftView as DraftView } from './portalRules'

/**
 * The client portal's entire entry point.
 *
 * **No `AppShell`, no `AuthProvider`, no router, no nav, no brand switcher.** Mounted from
 * `main.tsx` beside the staff app rather than inside it, because a client is not a staff user with
 * fewer links — and because `AuthProvider` sits above `App`, so a route inside it could not avoid
 * mounting it. One screen needs no router.
 *
 * The token comes out of the URL fragment and is handed to `portalApi`, which keeps it in memory.
 * Nothing is written to storage.
 */

type State =
  | { status: 'loading' }
  | { status: 'ready'; view: DraftView }
  | { status: 'failed'; message: string }

/** Said when the address bar has no fragment at all — a different problem from a refused link. */
const NO_TOKEN =
  'This page needs the full link that was sent to you, including everything after the # sign. ' +
  'Please open it again from the original message, or ask whoever sent it for a new one.'

export default function PortalRoot() {
  const [state, setState] = useState<State>({ status: 'loading' })

  useEffect(() => {
    const token = tokenFromFragment(window.location.hash)
    if (token === null) {
      setState({ status: 'failed', message: NO_TOKEN })
      return
    }
    setPortalToken(token)

    const controller = new AbortController()
    fetchDraft(controller.signal)
      .then((view) => setState({ status: 'ready', view }))
      .catch((error: unknown) => {
        if (controller.signal.aborted) return
        // Expired, revoked and unknown all arrive as the same 401, and this says the same thing
        // about all three — never a stack trace, and never a login form for somebody with no account.
        setState({ status: 'failed', message: failureMessage(statusOf(error)) })
      })
    return () => controller.abort()
  }, [])

  if (state.status === 'loading') {
    return (
      <Centred>
        <p className="text-sm" style={{ color: 'var(--text-muted)' }}>
          Loading your draft…
        </p>
      </Centred>
    )
  }

  if (state.status === 'failed') {
    return (
      <Centred>
        <div
          className="w-full max-w-md rounded-lg border p-5"
          style={{ background: 'var(--bg-surface)', borderColor: 'var(--border-default)' }}
        >
          <h1 className="text-base font-semibold tracking-tight">We could not open your draft</h1>
          <p className="mt-2 text-sm leading-relaxed" style={{ color: 'var(--text-muted)' }}>
            {state.message}
          </p>
        </div>
      </Centred>
    )
  }

  return (
    <div className="min-h-svh" style={{ background: 'var(--bg-base)' }}>
      <ClientDraftView view={state.view} onUpdated={(view) => setState({ status: 'ready', view })} />
    </div>
  )
}

function Centred({ children }: { children: React.ReactNode }) {
  return (
    <div
      className="flex min-h-svh items-center justify-center px-5"
      style={{ background: 'var(--bg-base)', color: 'var(--text-primary)' }}
    >
      {children}
    </div>
  )
}
