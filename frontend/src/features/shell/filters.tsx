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
  // `month`, and NOT `year` — this value is shared by two filters that point in opposite
  // directions. The dashboards read it backwards ("what happened since"), but the board reads it
  // FORWARDS through `dueBeforeFor`, so `year` moves the default deadline window from one month
  // out to twelve and leaves the production board effectively unfiltered for every role on first
  // load. It was briefly `year` because the email funnel's newest deal is months old, so `year`
  // is the only window with data in it — but a per-screen default is a whole second source of
  // truth for a control the user can already see, and that screen's empty state names the window
  // it searched and says to widen it. One click beats unfiltering the board for everyone.
  const [dateRange, setDateRange] = useState<DateRange>('month')

  const value = useMemo(
    () => ({ activeBrandId, setActiveBrandId, dateRange, setDateRange }),
    [activeBrandId, dateRange],
  )

  return <FiltersContext.Provider value={value}>{children}</FiltersContext.Provider>
}
