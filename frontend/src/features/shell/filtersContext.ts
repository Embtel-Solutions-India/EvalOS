import { createContext, useContext } from 'react'

/**
 * The shell filters context and its hook, split from `FiltersProvider` for the same
 * Fast Refresh reason as `lib/authContext.ts`.
 */
export type DateRange = 'today' | 'week' | 'month' | 'year'

export type FiltersValue = {
  /** null means "all brands", which only the GM can mean. */
  activeBrandId: string | null
  setActiveBrandId: (brandId: string | null) => void
  dateRange: DateRange
  setDateRange: (range: DateRange) => void
}

export const FiltersContext = createContext<FiltersValue | null>(null)

export function useFilters(): FiltersValue {
  const value = useContext(FiltersContext)
  if (!value) throw new Error('useFilters must be used inside FiltersProvider')
  return value
}
