import { NavLink } from 'react-router-dom'
import { useMe } from '../../lib/authContext'
import { navSectionsFor } from './navigation'

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
 *
 * **No icons, deliberately.** A pictogram beside eight text labels is decoration in a tool
 * whose users learn the nav in a day and then read it by position for years; the headings
 * carry the structure instead, and the codebase already declined a Lucide dependency for
 * one bell. Weight and a single accent rule mark the current screen — the accent is the
 * only non-status colour the palette allows (`ui-context.md`), so it is spent here rather
 * than sprinkled.
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
        <span className="block text-base font-semibold tracking-tight">EvalOS</span>
        <span className="mt-0.5 block font-mono text-[11px] tracking-tight" style={{ color: 'var(--text-muted)' }}>
          {me.role === 'GM' ? 'all brands' : 'brand desk'}
        </span>
      </div>

      <div className="scroll-slim flex-1 overflow-y-auto px-2 py-3">
        {navSectionsFor(me.role).map((section) => (
          <div key={section.group} className="mb-4 last:mb-0">
            <h2
              className="px-3 pb-1.5 text-[11px] font-semibold tracking-[0.08em] uppercase"
              style={{ color: 'var(--text-muted)' }}
            >
              {section.group}
            </h2>
            <ul className="space-y-0.5">
              {section.items.map((item) => (
                <li key={item.path}>
                  <NavLink
                    to={item.path}
                    className="relative block rounded-md py-2 pr-3 pl-3 text-sm transition-colors"
                    style={({ isActive }) => ({
                      background: isActive ? 'var(--bg-raised)' : 'transparent',
                      color: isActive ? 'var(--accent-primary)' : 'var(--text-primary)',
                      fontWeight: isActive ? 600 : 450,
                    })}
                  >
                    {({ isActive }) => (
                      <>
                        {isActive && (
                          <span
                            aria-hidden
                            className="absolute top-1.5 bottom-1.5 -left-2 w-[3px] rounded-r-md"
                            style={{ background: 'var(--accent-primary)' }}
                          />
                        )}
                        {item.label}
                      </>
                    )}
                  </NavLink>
                </li>
              ))}
            </ul>
          </div>
        ))}
      </div>

      <div className="flex items-center gap-2.5 border-t px-4 py-3.5" style={{ borderColor: 'var(--border-default)' }}>
        <span
          aria-hidden
          className="grid h-8 w-8 shrink-0 place-items-center rounded-md text-xs font-semibold"
          style={{ background: 'var(--bg-raised)', color: 'var(--text-muted)' }}
        >
          {initials(me.displayName)}
        </span>
        <span className="min-w-0">
          <span className="block truncate text-sm font-medium">{me.displayName}</span>
          <span className="block truncate text-xs" style={{ color: 'var(--text-muted)' }}>
            {ROLE_LABELS[me.role] ?? me.role}
          </span>
        </span>
      </div>
    </nav>
  )
}

/** First and last initial, so "Brandon Iyer" reads BI and a single name still renders. */
function initials(name: string): string {
  const parts = name.trim().split(/\s+/).filter(Boolean)
  if (parts.length === 0) return '?'
  const first = parts[0][0]
  return (parts.length > 1 ? first + parts[parts.length - 1][0] : first).toUpperCase()
}
