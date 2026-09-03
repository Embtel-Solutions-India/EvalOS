import { LogOut } from 'lucide-react'
import { NavLink } from 'react-router-dom'
import { Logo } from '@shared/components/common/Logo'
import { EXPERT_NAV } from '@/constants/expertNavigation'
import { useExpertAuth } from '@/hooks/useExpertAuth'
import { cn } from '@shared/utils/cn'

export function ExpertSidebar({ onNavigate }: { onNavigate?: () => void }) {
  const { logout } = useExpertAuth()

  return (
    <div className="flex h-full flex-col bg-sidebar text-sidebar-foreground">
      <div className="flex h-16 items-center border-b border-sidebar-border px-5">
        <Logo variant="light" />
      </div>
      <div className="px-5 pb-3 pt-4">
        <p className="text-[11px] font-semibold uppercase tracking-wide text-sidebar-foreground/50">Expert Portal</p>
      </div>
      <nav className="no-scrollbar flex-1 space-y-0.5 px-3">
        {EXPERT_NAV.map((item) => (
          <NavLink
            key={item.to}
            to={item.to}
            end={item.to === '/expert'}
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
