import { LogOut } from 'lucide-react'
import { NavLink } from 'react-router-dom'
import { UnreadBadge } from '@evalos/chat'
import { signOut } from '@shared/services/apiClient'
import { PRIMARY_NAV } from '@/constants/navigation'
import { cn } from '@shared/utils/cn'

const TAB = 'relative flex flex-1 flex-col items-center gap-0.5 px-1 py-2 text-[11px] font-medium transition-colors'

/** Phones: the nav as an app-style bar fixed to the bottom, icon over label. Replaces the slide-out drawer. */
export function MobileTabBar() {
  return (
    <nav
      aria-label="Main"
      className="fixed inset-x-0 bottom-0 z-40 flex border-t border-sidebar-border bg-sidebar pb-[env(safe-area-inset-bottom)] text-sidebar-foreground lg:hidden"
    >
      {PRIMARY_NAV.map((item) => (
        <NavLink key={item.to} to={item.to} className={({ isActive }) => cn(TAB, isActive ? 'text-white' : 'hover:text-white')}>
          <span className="relative">
            <item.icon className="h-5 w-5" />
            {item.unread && (
              <span className="absolute -right-3 -top-2">
                <UnreadBadge />
              </span>
            )}
          </span>
          <span className="max-w-full truncate">{item.label}</span>
        </NavLink>
      ))}
      <button type="button" onClick={() => void signOut('/signin')} className={cn(TAB, 'hover:text-white')}>
        <LogOut className="h-5 w-5" />
        Sign out
      </button>
    </nav>
  )
}
