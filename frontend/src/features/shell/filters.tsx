import { createContext, useContext, useMemo, useState, type ReactNode } from 'react'
import { useMe } from '../../lib/auth'

/**
 * The two shell-wide filters the top bar owns: which brand is in view, and over what
 * period. Neither is sent anywhere yet — no endpoint in this unit takes either — so
 * this is state the later units (08 board, 17 dashboards) read rather than a filter
 * being applied today. Holding it here now is what lets those units be additive.
 *
 * `activeBrandId` is null for "all brands", which **only the GM can mean**. Every
 * other role is pinned to their own brand and the switcher is not rendered at all, so
 * there is no interaction that can widen a non-GM's view. The server would refuse it
 * anyway (invariant 1); this just means the UI never asks.
 */
export type DateRange = 'today' | 'week' | 'month' | 'year'

type FiltersValue = {
  activeBrandId: string | null
  setActiveBrandId: (brandId: string | null) => void
  dateRange: DateRange
  setDateRange: (range: DateRange) => void
}

const FiltersContext = createContext<FiltersValue | null>(null)

export function FiltersProvider({ children }: { children: ReactNode }) {
  const me = useMe()
  // A non-GM starts and stays on their own brand; the GM starts on all brands.
  const [activeBrandId, setActiveBrandId] = useState<string | null>(me.brandId)
  const [dateRange, setDateRange] = useState<DateRange>('month')

  const value = useMemo(
    () => ({ activeBrandId, setActiveBrandId, dateRange, setDateRange }),
    [activeBrandId, dateRange],
  )

  return <FiltersContext.Provider value={value}>{children}</FiltersContext.Provider>
}

export function useFilters(): FiltersValue {
  const value = useContext(FiltersContext)
  if (!value) throw new Error('useFilters must be used inside FiltersProvider')
  return value
}
