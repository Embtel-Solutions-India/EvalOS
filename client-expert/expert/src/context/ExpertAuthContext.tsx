import { createContext, useCallback, useMemo, useState, type ReactNode } from 'react'
import * as expertAuthService from '@/services/expertAuthService'
import type { ExpertUser } from '@/types/expert'

export interface ExpertAuthContextValue {
  expert: ExpertUser | null
  isAuthenticated: boolean
  login: (payload: expertAuthService.ExpertLoginPayload) => Promise<ExpertUser>
  logout: () => Promise<void>
}

export const ExpertAuthContext = createContext<ExpertAuthContextValue | null>(null)

export function ExpertAuthProvider({ children }: { children: ReactNode }) {
  const [expert, setExpert] = useState<ExpertUser | null>(() => expertAuthService.getStoredExpert())
  const [isAuthenticated, setIsAuthenticated] = useState(() => expertAuthService.isExpertSessionAuthenticated())

  const login = useCallback(async (payload: expertAuthService.ExpertLoginPayload) => {
    const loggedInExpert = await expertAuthService.login(payload)
    setExpert(loggedInExpert)
    setIsAuthenticated(true)
    return loggedInExpert
  }, [])

  const logout = useCallback(async () => {
    await expertAuthService.logout()
    setIsAuthenticated(false)
  }, [])

  const value = useMemo<ExpertAuthContextValue>(
    () => ({ expert, isAuthenticated, login, logout }),
    [expert, isAuthenticated, login, logout],
  )

  return <ExpertAuthContext.Provider value={value}>{children}</ExpertAuthContext.Provider>
}
