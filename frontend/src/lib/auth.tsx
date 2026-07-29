import { useCallback, useEffect, useState, type ReactNode } from 'react'
import { api, unwrap } from './api'
import { AuthContext, type AuthState } from './authContext'
import { clearToken, getToken, setToken, type StaffIdentity } from './session'

/**
 * Who is signed in. `/api/me` is the authority — login only hands back a token, and the
 * client never decides its own role or brand from anything else.
 *
 * This file exports one component and nothing else, so Fast Refresh can keep the
 * session across a hot reload; the context and hooks live in `authContext.ts`.
 */
export default function AuthProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<AuthState>(() =>
    getToken() ? { status: 'loading' } : { status: 'anonymous' },
  )

  /** Hydrate from a token that survived a reload. Once, on mount; a failure means log in. */
  useEffect(() => {
    if (!getToken()) return
    const controller = new AbortController()

    unwrap<StaffIdentity>(api.get('/me', { signal: controller.signal }))
      .then((me) => setState({ status: 'authenticated', me }))
      .catch(() => {
        if (controller.signal.aborted) return
        clearToken()
        setState({ status: 'anonymous' })
      })

    return () => controller.abort()
  }, [])

  const login = useCallback(async (email: string, password: string) => {
    const session = await unwrap<{ token: string }>(api.post('/auth/login', { email, password }))
    setToken(session.token)
    // Role and brand come from /api/me, not the login response, so there is one source
    // of identity rather than two that can disagree.
    setState({ status: 'authenticated', me: await unwrap<StaffIdentity>(api.get('/me')) })
  }, [])

  const logout = useCallback(() => {
    clearToken()
    setState({ status: 'anonymous' })
  }, [])

  return <AuthContext.Provider value={{ state, login, logout }}>{children}</AuthContext.Provider>
}
