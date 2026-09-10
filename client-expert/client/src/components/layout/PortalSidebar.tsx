import { NavLink } from 'react-router-dom'
import { Logo } from '@shared/components/common/Logo'
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
          {item.label}
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
          funnel — a client starts a request in GHL, which is the front of house.

          **There is no Logout, and that is not an omission (34d).** There is no session to end:
          the credential is a scoped link held in memory for the tab, and closing the tab is the
          whole of logging out. A button that cleared it would strand the client on a page they
          could only return to by finding the original email. */}
      <nav className="no-scrollbar flex-1 space-y-6 overflow-y-auto px-3 py-5">
        <NavSection items={PRIMARY_NAV} onNavigate={onNavigate} />
      </nav>
    </div>
  )
}
