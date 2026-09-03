import {
  BarChart3,
  FileCheck2,
  LayoutDashboard,
  LifeBuoy,
  MessageSquare,
  Receipt,
  Settings,
  UserRound,
  Wallet,
} from 'lucide-react'
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
export const SECONDARY_NAV: NavItem[] = [
  { label: 'Analytics', to: '/analytics', icon: BarChart3 },
  { label: 'Payments', to: '/payments', icon: Wallet },
  { label: 'Invoices', to: '/invoices', icon: Receipt },
  { label: 'Reports', to: '/reports', icon: FileCheck2 },
]

export const SUPPORT_NAV: NavItem[] = [
  { label: 'Messages', to: '/messages', icon: MessageSquare },
  { label: 'Help & Support', to: '/tickets', icon: LifeBuoy },
]

export const ACCOUNT_NAV: NavItem[] = [
  { label: 'Profile', to: '/profile', icon: UserRound },
  { label: 'Settings', to: '/settings', icon: Settings },
]
