import { NavLink } from 'react-router-dom'
import { useMe } from '../../lib/authContext'
import { navFor } from './navigation'

const ROLE_LABELS: Record<string, string> = {
  GM: 'General Manager',
  BRAND_MANAGER: 'Brand Manager',
  PROJECT_MANAGER: 'Project Manager',
  PROJECT_COORDINATOR: 'Project Coordinator',
  CASE_MANAGER: 'Case Manager',
  EXPERT_NETWORK_MANAGER: 'Expert Network Manager',
}

/**
 * Fixed left nav, filtered by role from the one table the router also guards against
 * (`navigation.ts`), so a listed item is always reachable and a reachable item is
 * always listed.
 */
export default function LeftNav() {
  const me = useMe()

  return (
    <nav
      className="flex w-60 shrink-0 flex-col border-r"
      style={{ background: 'var(--bg-surface)', borderColor: 'var(--border-default)' }}
      aria-label="Main"
    >
      <div className="border-b px-5 py-4" style={{ borderColor: 'var(--border-default)' }}>
        <span className="font-semibold tracking-tight">EvalOS</span>
      </div>

      <ul className="flex-1 space-y-0.5 p-2">
        {navFor(me.role).map((item) => (
          <li key={item.path}>
            <NavLink
              to={item.path}
              className="block rounded-md px-3 py-2 text-sm font-medium"
              style={({ isActive }) => ({
                background: isActive ? 'var(--bg-raised)' : 'transparent',
                color: isActive ? 'var(--accent-primary)' : 'var(--text-primary)',
              })}
            >
              {item.label}
            </NavLink>
          </li>
        ))}
      </ul>

      <div className="border-t px-5 py-4" style={{ borderColor: 'var(--border-default)' }}>
        <p className="truncate text-sm font-medium">{me.displayName}</p>
        <p className="mt-0.5 text-xs" style={{ color: 'var(--text-muted)' }}>
          {ROLE_LABELS[me.role] ?? me.role}
        </p>
      </div>
    </nav>
  )
}
