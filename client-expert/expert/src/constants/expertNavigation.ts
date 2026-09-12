import { LayoutDashboard, UserRound, Wallet } from 'lucide-react'
import type { ComponentType } from 'react'

export interface ExpertNavItem {
  label: string
  to: string
  icon: ComponentType<{ className?: string }>
}

export const EXPERT_NAV: ExpertNavItem[] = [
  { label: 'Dashboard', to: '/expert', icon: LayoutDashboard },
  { label: 'Payments', to: '/expert/payments', icon: Wallet },
  { label: 'Profile', to: '/expert/profile', icon: UserRound },
]
