import type { ReactNode } from 'react'
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
 * The nav, as a floating rounded card. Filtered by role from the one table the router also
 * guards against (`navigation.ts`), so a listed item is always reachable and a reachable
 * item is always listed.
 *
 * **Icons are new, and they are a reversal.** This file used to argue against them: a
 * pictogram beside eight text labels is decoration in a tool whose users read the nav by
 * position after a day. That reasoning still holds on its own terms — what changed is that
 * an icon rail is a defining part of the visual language being adopted
 * (`UI_MIGRATION_GUIDE.md`), and the nav is where it is most visible. They are inline SVG
 * rather than an icon package for the reason the bell always was: seven glyphs do not earn
 * a dependency, and this app has four runtime deps in total.
 *
 * **The grouping stays**, built from consecutive runs in `NAV_ITEMS`. The template ships a
 * flat accordion, and flattening this would undo the fix that stopped three roles having
 * their primary screen listed last.
 *
 * **No left accent bar on the active item.** The template marks the current screen with a
 * tinted fill and accent text only — it even leaves the border rule commented out in its
 * own stylesheet — and a fill plus a bar is two markers for one state.
 */
export default function LeftNav() {
  const me = useMe()

  return (
    <nav
      className="fixed z-30 flex flex-col overflow-hidden"
      style={{
        top: 'var(--shell-gutter)',
        left: 'var(--shell-gutter)',
        bottom: 'var(--shell-gutter)',
        width: 'var(--sidebar-width)',
        background: 'var(--bg-surface)',
        borderRadius: 'var(--radius-xl)',
        boxShadow: 'var(--shadow-card)',
      }}
      aria-label="Main"
    >
      <div className="px-5 pt-6 pb-4">
        <span className="block text-lg font-semibold tracking-tight">EvalOS</span>
        <span className="mt-0.5 block font-mono text-[11px] tracking-tight" style={{ color: 'var(--text-muted)' }}>
          {me.role === 'GM' ? 'all brands' : 'brand desk'}
        </span>
      </div>

      <div className="scroll-slim flex-1 overflow-y-auto px-3 pb-3">
        {navSectionsFor(me.role).map((section) => (
          <div key={section.group} className="mb-3 last:mb-0">
            <h2
              className="px-3 pb-1.5 text-[10px] font-semibold tracking-[0.1em] uppercase"
              style={{ color: 'var(--text-muted)' }}
            >
              {section.group}
            </h2>
            <ul className="space-y-1">
              {section.items.map((item) => (
                <li key={item.path}>
                  <NavLink
                    to={item.path}
                    className="flex items-center gap-2.5 px-3 text-sm transition-colors"
                    style={({ isActive }) => ({
                      height: '2.25rem',
                      borderRadius: 'var(--radius-lg)',
                      background: isActive ? 'var(--accent-soft)' : 'transparent',
                      color: isActive ? 'var(--accent-primary)' : 'var(--text-muted)',
                      fontWeight: isActive ? 600 : 500,
                    })}
                  >
                    <span aria-hidden className="shrink-0">
                      {NAV_ICONS[item.path] ?? NAV_ICONS.fallback}
                    </span>
                    <span className="truncate">{item.label}</span>
                  </NavLink>
                </li>
              ))}
            </ul>
          </div>
        ))}
      </div>

      <div className="flex items-center gap-2.5 px-4 py-3.5" style={{ borderTop: '1px solid var(--border-default)' }}>
        <span
          aria-hidden
          className="grid h-9 w-9 shrink-0 place-items-center text-xs font-semibold"
          style={{
            background: 'var(--accent-soft)',
            color: 'var(--accent-primary)',
            borderRadius: 'var(--radius-lg)',
          }}
        >
          {initials(me.displayName)}
        </span>
        <span className="min-w-0">
          <span className="block truncate text-sm font-semibold">{me.displayName}</span>
          <span className="block truncate text-xs" style={{ color: 'var(--text-muted)' }}>
            {ROLE_LABELS[me.role] ?? me.role}
          </span>
        </span>
      </div>
    </nav>
  )
}

/**
 * One glyph per nav path, keyed by the same `path` the router uses so there is no second
 * list to keep in step — an entry missing here degrades to the fallback rather than
 * breaking the item. Purely presentational: `navigation.ts` is untouched.
 */
const NAV_ICONS: Record<string, ReactNode> = {
  '/dashboard': (
    <Glyph>
      <rect x="3" y="3" width="7" height="9" rx="1.5" />
      <rect x="14" y="3" width="7" height="5" rx="1.5" />
      <rect x="14" y="12" width="7" height="9" rx="1.5" />
      <rect x="3" y="16" width="7" height="5" rx="1.5" />
    </Glyph>
  ),
  '/board': (
    <Glyph>
      <rect x="3" y="4" width="5" height="16" rx="1.5" />
      <rect x="10" y="4" width="5" height="11" rx="1.5" />
      <rect x="17" y="4" width="4" height="7" rx="1.5" />
    </Glyph>
  ),
  '/my-cases': (
    <Glyph>
      <path d="M4 7h16v13H4z" />
      <path d="M9 7V4h6v3" />
    </Glyph>
  ),
  '/checklists': (
    <Glyph>
      <path d="M9 5h10M9 12h10M9 19h10" />
      <path d="m3 5 2 2 2-3M3 12l2 2 2-3M3 19l2 2 2-3" />
    </Glyph>
  ),
  '/experts': (
    <Glyph>
      <circle cx="9" cy="8" r="3.5" />
      <path d="M3 20c0-3.3 2.7-6 6-6s6 2.7 6 6" />
      <path d="M17 11a2.5 2.5 0 1 0 0-5" />
      <path d="M19 20c0-2.2-.9-4.2-2.3-5.6" />
    </Glyph>
  ),
  '/payouts': (
    <Glyph>
      <rect x="2.5" y="6" width="19" height="12" rx="2.5" />
      <circle cx="12" cy="12" r="2.5" />
      <path d="M6 12h.01M18 12h.01" />
    </Glyph>
  ),
  '/brands': (
    <Glyph>
      <path d="M4 20V9l8-5 8 5v11" />
      <path d="M10 20v-6h4v6" />
    </Glyph>
  ),
  fallback: (
    <Glyph>
      <circle cx="12" cy="12" r="8" />
    </Glyph>
  ),
}

function Glyph({ children }: { children: ReactNode }) {
  return (
    <svg
      className="h-5 w-5"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.6"
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      {children}
    </svg>
  )
}

/** First and last initial, so "Brandon Iyer" reads BI and a single name still renders. */
function initials(name: string): string {
  const parts = name.trim().split(/\s+/).filter(Boolean)
  if (parts.length === 0) return '?'
  const first = parts[0][0]
  return (parts.length > 1 ? first + parts[parts.length - 1][0] : first).toUpperCase()
}
