import { useMemo, useState, type ReactNode } from 'react'
import { useMe } from '../../lib/authContext'
import { FiltersContext, DEFAULT_RANGE, type DateRange } from './filtersContext'

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
  // `month` — now meaning the 1st of this month through today, not the last 30 days.
  //
  // **The reason this default was contentious no longer applies, and the note stays as history.**
  // This value used to be read in two directions: backwards by the dashboards, and FORWARDS by the
  // production board through `dueBeforeFor`. That made the default a deadline window too, so
  // setting it to `year` once left the board effectively unfiltered for every role on first load.
  // The board now owns its own forward control (`features/board/deadlineWindow.ts`), because this
  // filter gained periods — `last-month`, a custom interval — that cannot be a "due before" cutoff
  // at all. So the default is once again only what it says: the period the dashboards open on.
  //
  // Still `month` rather than `year`: it is the period somebody actually works in, and a screen
  // whose window is wider than the question is one you have to narrow before you can read it.
  const [dateRange, setDateRange] = useState<DateRange>(DEFAULT_RANGE)

  const value = useMemo(
    () => ({ activeBrandId, setActiveBrandId, dateRange, setDateRange }),
    [activeBrandId, dateRange],
  )

  return <FiltersContext.Provider value={value}>{children}</FiltersContext.Provider>
}
