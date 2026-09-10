import { FileCheck2, LayoutDashboard, Settings, UserRound } from 'lucide-react'
import type { ComponentType } from 'react'

export interface NavItem {
  label: string
  to: string
  icon: ComponentType<{ className?: string }>
}

// Kept deliberately short — Home and My Requests are the only two things a
// client should need to think about day to day. Everything else still
// exists (a client with an active request may well need Payments or
// Documents) but sits in a quieter, secondary group rather than competing
// for attention.
export const PRIMARY_NAV: NavItem[] = [
  { label: 'Home', to: '/dashboard', icon: LayoutDashboard },
  { label: 'My Requests', to: '/requests', icon: FileCheck2 },
]

// **Documents is deliberately not here (Unit 34c).** `/documents` is now the real,
// EvalOS-backed screen and its credential is a scoped portal link, not this shell's session —
// so it is reached from that link and not from a sidebar entry that would open it without one.
// **Payments, Invoices, Analytics and SUPPORT_NAV's two entries all left on 2026-09-10**, with
// their pages. A nav entry is a promise that a route exists, so an entry outliving its page is a
// link to a 404 — see the note at the top of App.tsx for which went and why.
//
// One entry left, so this group is a single row. If it loses that one too, delete the group and its
// "More" heading rather than rendering an empty section.
export const SECONDARY_NAV: NavItem[] = [
  { label: 'Reports', to: '/reports', icon: FileCheck2 },
]

export const ACCOUNT_NAV: NavItem[] = [
  { label: 'Profile', to: '/profile', icon: UserRound },
  { label: 'Settings', to: '/settings', icon: Settings },
]
