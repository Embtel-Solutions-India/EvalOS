import { CalendarClock, FileCheck2, FileText, LayoutDashboard, Receipt } from 'lucide-react'
import type { ComponentType } from 'react'

export interface NavItem {
  label: string
  to: string
  icon: ComponentType<{ className?: string }>
}

/**
 * The whole app, in one group (34d).
 *
 * **Every entry is a real, EvalOS-backed screen.** Before 34d this list promised a mock account
 * shell — Home and My Requests over fake data, plus Profile and Settings for an account that is
 * not coming — while the three screens that actually worked were unreachable from it. That is
 * inverted now.
 *
 * **They can share a sidebar because they share a credential.** The scoped portal token is
 * lifted out of the URL fragment once and held in the API client, so a client who opens any one
 * link can walk the rest without another. Before, each real screen stood alone and a nav entry
 * would have opened it with no token at all.
 *
 * **`/reports` is deliberately absent, and its page is DELETED, not parked.** This line said
 * "parked" while `App.tsx` recorded the deletion in the same commit; `App.tsx` is right. The
 * reason for the absence is unchanged and is the part that matters: EvalOS has no route that
 * serves the signed letter, because delivery is a decision nobody has taken, and a nav entry is a
 * promise that a route exists.
 *
 * **`ACCOUNT_NAV` and `SECONDARY_NAV` are gone**, with the pages behind them. A group is not
 * worth keeping for the day something might refill it.
 */
export const PRIMARY_NAV: NavItem[] = [
  { label: 'Home', to: '/dashboard', icon: LayoutDashboard },
  { label: 'My requests', to: '/requests', icon: FileCheck2 },
  { label: 'Documents', to: '/documents', icon: FileText },
  { label: 'Invoices', to: '/invoices', icon: Receipt },
  // Added 2026-09-11, the other half of "what a client is owed sight of". Sales books the
  // meeting from their desk and GHL sends the invitation; this is where the client finds it
  // again without searching an inbox.
  //
  // **Invoices and meetings are the whole of it.** No stage, no deal value, no sales notes —
  // the decision of 2026-09-11. Stage names are written for staff, and a prospect reading that
  // they are currently "Cold" is a leak no relabelling makes safe.
  { label: 'Meetings', to: '/meetings', icon: CalendarClock },
]
