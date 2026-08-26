import { useMemo, useState, type ReactNode } from 'react'
import { useMe } from '../../lib/authContext'
import { FiltersContext, type DateRange } from './filtersContext'

/**
 * The two shell-wide filters the top bar owns: which brand is in view, and over what
 * period. Neither is sent anywhere yet — no endpoint in this unit takes either — so this
 * is state that Units 08 and 17 will read, held now so those units can be additive.
 *
 * A non-GM starts and stays on their own brand: the switcher is not rendered for them at
 * all, so no interaction can widen a non-GM's view. The server would refuse it anyway
 * (invariant 1); this just means the UI never asks.
 */
export default function FiltersProvider({ children }: { children: ReactNode }) {
  const me = useMe()
  const [activeBrandId, setActiveBrandId] = useState<string | null>(me.brandId)
  const [dateRange, setDateRange] = useState<DateRange>('year')

  const value = useMemo(
    () => ({ activeBrandId, setActiveBrandId, dateRange, setDateRange }),
    [activeBrandId, dateRange],
  )

  return <FiltersContext.Provider value={value}>{children}</FiltersContext.Provider>
}
