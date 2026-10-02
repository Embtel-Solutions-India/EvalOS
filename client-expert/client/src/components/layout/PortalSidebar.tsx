import { LogOut } from 'lucide-react'
import { NavLink } from 'react-router-dom'
import { UnreadBadge } from '@evalos/chat'
import { Logo } from '@shared/components/common/Logo'
import { signOut } from '@shared/services/apiClient'
import { PRIMARY_NAV, type NavItem } from '@/constants/navigation'
import { cn } from '@shared/utils/cn'

function NavSection({ items, onNavigate }: { items: NavItem[]; onNavigate?: () => void }) {
  return (
    <div className="space-y-0.5">
      {items.map((item) => (
        <NavLink
          key={item.to}
          to={item.to}
          onClick={onNavigate}
          className={({ isActive }) =>
            cn(
              'flex items-center gap-3 rounded-md px-3 py-2 text-sm font-medium transition duration-150 active:scale-[0.97]',
              isActive
                ? 'bg-sidebar-accent text-white'
                : 'text-sidebar-foreground hover:bg-sidebar-accent/60 hover:text-white hover:translate-x-0.5',
            )
          }
        >
          <item.icon className="h-4 w-4 shrink-0" />
          <span className="flex-1">{item.label}</span>
          {item.unread && <UnreadBadge />}
        </NavLink>
      ))}
    </div>
  )
}

export function PortalSidebar({ onNavigate }: { onNavigate?: () => void }) {
  return (
    <div className="flex h-full flex-col bg-sidebar text-sidebar-foreground">
      <div className="flex h-16 items-center border-b border-sidebar-border px-5">
        <Logo variant="light" />
      </div>
      {/* The "New Request" button stood here and opened /start/service. It went with the intake
          funnel — a client starts a request in GHL, which is the front of house. */}
      <nav className="no-scrollbar flex-1 space-y-6 overflow-y-auto px-3 py-5">
        <NavSection items={PRIMARY_NAV} onNavigate={onNavigate} />
      </nav>
      {/* Unit 75 (D72): the sign-in survives a reload, so leaving needs a real Sign out. It also
          ends the token on the server. Every client signs in with a password, so the way back is
          the sign-in page, not an old email. */}
      <div className="border-t border-sidebar-border px-3 py-3">
        <button
          type="button"
          onClick={() => void signOut('/signin')}
          className="flex w-full items-center gap-3 rounded-md px-3 py-2 text-sm font-medium text-sidebar-foreground transition duration-150 hover:bg-sidebar-accent/60 hover:text-white"
        >
          <LogOut className="h-4 w-4 shrink-0" />
          Sign out
        </button>
      </div>
    </div>
  )
}
