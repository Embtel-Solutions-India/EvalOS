import { FileCheck2, LayoutDashboard, MessagesSquare, Receipt } from 'lucide-react'
import type { ComponentType } from 'react'

export interface NavItem {
  label: string
  to: string
  icon: ComponentType<{ className?: string }>
  /** Carries the chat unread badge. */
  unread?: boolean
}

/**
 * The whole app, in one group (34d; Unit 58's four since 2026-09-28).
 *
 * **Every entry is a real, EvalOS-backed screen**, and they can share a sidebar because they share
 * a credential. A case is reached from Home rather than from a menu, so it has no entry here.
 */
export const PRIMARY_NAV: NavItem[] = [
  { label: 'Home', to: '/dashboard', icon: LayoutDashboard },
  // Unit 58 (2026-09-26): Documents moved into each case, and Meetings left the portal.
  { label: 'Invoices', to: '/invoices', icon: Receipt },
  { label: 'Conversations', to: '/conversations', icon: MessagesSquare, unread: true },
  { label: 'My cases', to: '/cases', icon: FileCheck2 },
]
