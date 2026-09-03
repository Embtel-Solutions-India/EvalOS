import { createContext, useCallback, useMemo, useState, type ReactNode } from 'react'
import * as authService from '@/services/authService'
import type { User } from '@/types'

export interface AuthContextValue {
  user: User | null
  isAuthenticated: boolean
  login: (payload: authService.LoginPayload) => Promise<User>
  register: (payload: authService.RegisterPayload) => Promise<User>
  logout: () => Promise<void>
  verifyEmail: () => Promise<void>
  markProfileCompleted: () => void
  updateUser: (updates: Partial<User>) => User | null
  refreshUser: () => void
}

export const AuthContext = createContext<AuthContextValue | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(() => authService.getStoredUser())
  const [isAuthenticated, setIsAuthenticated] = useState(() => authService.isSessionAuthenticated())

  const login = useCallback(async (payload: authService.LoginPayload) => {
    const loggedInUser = await authService.login(payload)
    setUser(loggedInUser)
    setIsAuthenticated(true)
    return loggedInUser
  }, [])

  const register = useCallback(async (payload: authService.RegisterPayload) => {
    const newUser = await authService.register(payload)
    setUser(newUser)
    setIsAuthenticated(true)
    return newUser
  }, [])

  const logout = useCallback(async () => {
    await authService.logout()
    setIsAuthenticated(false)
  }, [])

  const verifyEmail = useCallback(async () => {
    const updated = await authService.verifyEmail()
    if (updated) setUser(updated)
  }, [])

  const markProfileCompleted = useCallback(() => {
    const updated = authService.updateStoredUser({ profileCompleted: true })
    if (updated) setUser(updated)
  }, [])

  const refreshUser = useCallback(() => {
    setUser(authService.getStoredUser())
  }, [])

  const updateUser = useCallback((updates: Partial<User>) => {
    const updated = authService.updateStoredUser(updates)
    if (updated) setUser(updated)
    return updated
  }, [])

  const value = useMemo<AuthContextValue>(
    () => ({
      user,
      isAuthenticated,
      login,
      register,
      logout,
      verifyEmail,
      markProfileCompleted,
      updateUser,
      refreshUser,
    }),
    [user, isAuthenticated, login, register, logout, verifyEmail, markProfileCompleted, updateUser, refreshUser],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}
