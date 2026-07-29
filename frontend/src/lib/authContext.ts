import { createContext, useContext } from 'react'
import type { StaffIdentity } from './session'

/**
 * The auth context and its hooks, deliberately **not** in the same file as
 * `AuthProvider`.
 *
 * Vite's Fast Refresh can only preserve a module's state when that module exports
 * components alone. With the provider and these hooks together, editing anything in the
 * import graph recreated the context during a hot reload and `App` threw
 * "useAuth must be used inside AuthProvider" until a manual refresh. Splitting them is
 * what `oxlint`'s `react/only-export-components` was asking for.
 */
export type AuthState =
  | { status: 'loading' }
  | { status: 'anonymous'; reason?: string }
  | { status: 'authenticated'; me: StaffIdentity }

export type AuthContextValue = {
  state: AuthState
  login: (email: string, password: string) => Promise<void>
  logout: () => void
}

export const AuthContext = createContext<AuthContextValue | null>(null)

export function useAuth(): AuthContextValue {
  const value = useContext(AuthContext)
  if (!value) throw new Error('useAuth must be used inside AuthProvider')
  return value
}

/** The signed-in identity, for screens that only ever render inside the shell. */
export function useMe(): StaffIdentity {
  const { state } = useAuth()
  if (state.status !== 'authenticated') throw new Error('useMe requires an authenticated shell')
  return state.me
}
