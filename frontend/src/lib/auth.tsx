import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from 'react'
import { api, unwrap } from './api'
import { clearToken, getToken, setToken, type StaffIdentity } from './session'

/**
 * Who is signed in. `/api/me` is the authority — login only hands back a token, and
 * the client never decides its own role or brand from anything else.
 *
 * A discriminated union rather than `{ user, loading, error }` booleans, so "loading"
 * and "signed in" cannot both be true and no screen has to guard against it.
 */
type AuthState =
  | { status: 'loading' }
  | { status: 'anonymous'; reason?: string }
  | { status: 'authenticated'; me: StaffIdentity }

type AuthContextValue = {
  state: AuthState
  login: (email: string, password: string) => Promise<void>
  logout: () => void
}

const AuthContext = createContext<AuthContextValue | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<AuthState>(() =>
    getToken() ? { status: 'loading' } : { status: 'anonymous' },
  )

  /** Hydrate from a token that survived a reload. Runs once; a failure means log in. */
  useEffect(() => {
    if (state.status !== 'loading') return
    const controller = new AbortController()

    unwrap<StaffIdentity>(api.get('/me', { signal: controller.signal }))
      .then((me) => setState({ status: 'authenticated', me }))
      .catch(() => {
        if (controller.signal.aborted) return
        clearToken()
        setState({ status: 'anonymous' })
      })

    return () => controller.abort()
    // Deliberately once, on mount: re-running on every state change would re-fetch
    // /api/me after every login.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const login = useCallback(async (email: string, password: string) => {
    const session = await unwrap<{ token: string }>(api.post('/auth/login', { email, password }))
    setToken(session.token)
    // Role and brand come from /api/me, not from the login response, so there is one
    // source of identity rather than two that can disagree.
    setState({ status: 'authenticated', me: await unwrap<StaffIdentity>(api.get('/me')) })
  }, [])

  const logout = useCallback(() => {
    clearToken()
    setState({ status: 'anonymous' })
  }, [])

  return <AuthContext.Provider value={{ state, login, logout }}>{children}</AuthContext.Provider>
}

export function useAuth(): AuthContextValue {
  const value = useContext(AuthContext)
  if (!value) throw new Error('useAuth must be used inside AuthProvider')
  return value
}

/** The signed-in identity, for screens that only render inside the shell. */
export function useMe(): StaffIdentity {
  const { state } = useAuth()
  if (state.status !== 'authenticated') throw new Error('useMe requires an authenticated shell')
  return state.me
}
