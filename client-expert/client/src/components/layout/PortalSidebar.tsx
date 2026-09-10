import { LogOut } from 'lucide-react'
import { NavLink } from 'react-router-dom'
import { Logo } from '@shared/components/common/Logo'
import { Separator } from '@shared/components/ui/separator'
import { ACCOUNT_NAV, PRIMARY_NAV, SECONDARY_NAV, type NavItem } from '@/constants/navigation'
import { useAuth } from '@/hooks/useAuth'
import { cn } from '@shared/utils/cn'

function NavSection({ items, onNavigate, muted }: { items: NavItem[]; onNavigate?: () => void; muted?: boolean }) {
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
              muted && !isActive && 'text-sidebar-foreground/70',
              isActive
                ? 'bg-sidebar-accent text-white'
                : 'text-sidebar-foreground hover:bg-sidebar-accent/60 hover:text-white hover:translate-x-0.5',
            )
          }
        >
          <item.icon className="h-4 w-4 shrink-0" />
          {item.label}
        </NavLink>
      ))}
    </div>
  )
}

export function PortalSidebar({ onNavigate }: { onNavigate?: () => void }) {
  const { logout } = useAuth()

  return (
    <div className="flex h-full flex-col bg-sidebar text-sidebar-foreground">
      <div className="flex h-16 items-center border-b border-sidebar-border px-5">
        <Logo variant="light" />
      </div>
      {/* The "New Request" button stood here and opened /start/service. Parked 2026-09-10 with the
          rest of the intake funnel (D2) — a client starts a request in GHL, which is the front of
          house. See the parked note in App.tsx. */}
      <nav className="no-scrollbar flex-1 space-y-6 overflow-y-auto px-3 py-5">
        <NavSection items={PRIMARY_NAV} onNavigate={onNavigate} />
        <div>
          <p className="px-3 pb-1.5 text-[11px] font-semibold uppercase tracking-wide text-sidebar-foreground/50">More</p>
          <NavSection items={SECONDARY_NAV} onNavigate={onNavigate} muted />
        </div>
        <Separator className="bg-sidebar-border" />
        <NavSection items={ACCOUNT_NAV} onNavigate={onNavigate} />
      </nav>
      <div className="border-t border-sidebar-border p-3">
        <button
          type="button"
          onClick={() => void logout()}
          className="flex w-full items-center gap-3 rounded-md px-3 py-2 text-sm font-medium text-sidebar-foreground transition duration-150 hover:bg-sidebar-accent/60 hover:text-white active:scale-[0.97]"
        >
          <LogOut className="h-4 w-4" />
          Logout
        </button>
      </div>
    </div>
  )
}
