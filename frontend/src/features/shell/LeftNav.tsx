import { useEffect, useState } from 'react'
import type { ReactNode } from 'react'
import { NavLink } from 'react-router-dom'
import { useMe } from '../../lib/authContext'
import { navSectionsFor } from './navigation'
import { BADGE_FOR_PATH, fetchNavBadges, isUrgentBadge, type NavBadges } from './navBadges'

const ROLE_LABELS: Record<string, string> = {
  GM: 'General Manager',
  BRAND_MANAGER: 'Brand Manager',
  PROJECT_MANAGER: 'Project Manager',
  PROJECT_COORDINATOR: 'Project Coordinator',
  CASE_MANAGER: 'Case Manager',
  EXPERT_NETWORK_MANAGER: 'Expert Network Manager',
}

/**
 * The nav, as a **flush full-height dark rail**. Filtered by role from the one table the router
 * also guards against (`navigation.ts`), so a listed item is always reachable and a reachable
 * item is always listed.
 *
 * **Icons are inline SVG here, and that is now a local exception rather than a policy.** This
 * file argued twice: first against icons at all, then for them as part of the adopted visual
 * language. What changed since is that `lucide-react` is a dependency (Unit 22), so the
 * "seven glyphs do not earn a package" reasoning no longer applies — these paths stay because
 * they work and rewriting them buys nothing, and new glyphs come from Lucide.
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
  const [badges, setBadges] = useState<NavBadges | null>(null)

  useEffect(() => {
    const controller = new AbortController()
    // Failure is silent on purpose: a rail that renders an error where a count should be is
    // worse than a rail with no counts. The screens themselves report their own load failures.
    fetchNavBadges(controller.signal)
      .then(setBadges)
      .catch(() => {})
    return () => controller.abort()
  }, [])

  return (
    <nav
      className="fixed inset-y-0 left-0 z-30 flex flex-col overflow-hidden"
      style={{
        width: 'var(--sidebar-width)',
        background: 'var(--sidebar-bg)',
        color: 'var(--sidebar-text)',
      }}
      aria-label="Main"
    >
      <div className="flex items-center gap-2.5 px-4 pt-5 pb-4">
        <span
          aria-hidden
          className="grid h-9 w-9 shrink-0 place-items-center text-sm font-bold"
          style={{
            background: 'var(--accent-primary)',
            color: '#fff',
            borderRadius: 'var(--radius-md)',
          }}
        >
          IE
        </span>
        <span className="min-w-0">
          {/* The brand you are actually in, not the product name. A Brand Manager holds one brand
              and could not previously see which — `/api/brands` is GM-only, so the name now comes
              down on `/api/me`. The GM is cross-brand and says so. */}
          <span className="block truncate text-sm font-semibold">{me.brandName ?? 'EvalOS'}</span>
          <span className="block truncate text-[11px]" style={{ color: 'var(--sidebar-muted)' }}>
            {me.role === 'GM' ? 'All brands' : 'EvalOS'}
          </span>
        </span>
      </div>

      <div className="scroll-slim flex-1 overflow-y-auto px-3 pb-3">
        {navSectionsFor(me.role).map((section) => (
          <div key={section.group} className="mb-3 last:mb-0">
            <h2
              className="px-3 pb-1.5 text-[10px] font-semibold tracking-[0.1em] uppercase"
              style={{ color: 'var(--sidebar-muted)' }}
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
                      borderRadius: 'var(--radius-md)',
                      background: isActive ? 'var(--sidebar-active-bg)' : 'transparent',
                      color: isActive ? '#fff' : 'var(--sidebar-muted)',
                      fontWeight: isActive ? 600 : 500,
                    })}
                  >
                    <span aria-hidden className="shrink-0">
                      {NAV_ICONS[item.path] ?? NAV_ICONS.fallback}
                    </span>
                    <span className="truncate">{item.label}</span>
                    <Badge path={item.path} badges={badges} />
                  </NavLink>
                </li>
              ))}
            </ul>
          </div>
        ))}
      </div>

      <div className="flex items-center gap-2.5 px-4 py-3.5" style={{ borderTop: '1px solid var(--sidebar-border)' }}>
        <span
          aria-hidden
          className="grid h-9 w-9 shrink-0 place-items-center text-xs font-semibold"
          style={{
            background: 'var(--sidebar-active-bg)',
            color: '#fff',
            borderRadius: 'var(--radius-md)',
          }}
        >
          {initials(me.displayName)}
        </span>
        <span className="min-w-0">
          <span className="block truncate text-sm font-semibold">{me.displayName}</span>
          <span className="block truncate text-xs" style={{ color: 'var(--sidebar-muted)' }}>
            {ROLE_LABELS[me.role] ?? me.role}
          </span>
        </span>
      </div>
    </nav>
  )
}

/**
 * The count beside a screen's name, when there is one and it is not zero.
 *
 * **Zero renders nothing.** A rail carrying a row of noughts trains people to stop reading it, and
 * "nothing is waiting" is already said by the absence. This is the opposite of the dashboard rule,
 * where a metric of zero renders `0` — there the number is the answer, here it is an interruption.
 */
function Badge({ path, badges }: { path: string; badges: NavBadges | null }) {
  const key = BADGE_FOR_PATH[path]
  const count = key && badges ? badges[key] : 0
  if (!key || count === 0) {
    return null
  }
  const urgent = isUrgentBadge(key)
  return (
    <span
      className="font-num ml-auto shrink-0 rounded-md px-1.5 py-0.5 text-[11px] font-semibold tabular-nums"
      style={{
        background: urgent ? 'var(--status-red)' : 'rgb(255 255 255 / 0.12)',
        color: '#fff',
      }}
    >
      {count}
      {/* The number alone is ambiguous on a rail — say what it counts, for a screen reader and
          for anyone who has not learned the layout yet. */}
      <span className="sr-only"> waiting</span>
    </span>
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
  // A funnel, narrowing. The one screen that reads GHL rather than EvalOS, so it gets a shape
  // no production screen uses.
  '/marketing/google-ads': (
    <Glyph>
      <path d="M3 5h18l-7 8v6l-4 2v-8z" />
    </Glyph>
  ),
  // An envelope. The second GHL funnel, so it keeps the "reads GHL" corner of the nav visually
  // distinct from production work without reusing the funnel above.
  '/marketing/email': (
    <Glyph>
      <rect x="3" y="6" width="18" height="12" rx="2" />
      <path d="m3 8 9 6 9-6" />
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
