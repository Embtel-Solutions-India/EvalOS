import { useAuth } from '../../lib/authContext'
import BrandSwitcher from './BrandSwitcher'
import DateFilter from './DateFilter'
import NotificationBell from './NotificationBell'

/**
 * Scope on the left, account on the right, with the search field between them.
 *
 * The two controls on the left both answer "which work am I looking at" — brand, then
 * period — so they sit together behind one divider rather than floating in a row of
 * equals. Search is a controlled input that goes nowhere: there is no search endpoint,
 * and a box that silently does nothing is better than one that pretends by filtering the
 * current page. Its placeholder says so rather than promising a date.
 */
export default function TopBar() {
  const { logout } = useAuth()

  return (
    <header
      className="flex items-center gap-4 border-b px-6 py-3"
      style={{ background: 'var(--bg-surface)', borderColor: 'var(--border-default)' }}
    >
      <div className="flex items-center gap-2">
        <BrandSwitcher />
        <span aria-hidden className="h-5 w-px" style={{ background: 'var(--border-default)' }} />
        <DateFilter />
      </div>

      <div className="min-w-0 flex-1">
        <label className="relative block max-w-xs">
          <span className="sr-only">Search cases</span>
          <SearchIcon />
          <input
            type="search"
            placeholder="Search — not available yet"
            disabled
            title="Case search has no endpoint yet"
            className="w-full rounded-md border py-1.5 pr-3 pl-8 text-sm"
            style={{
              background: 'var(--bg-raised)',
              borderColor: 'var(--border-default)',
              color: 'var(--text-muted)',
            }}
          />
        </label>
      </div>

      <div className="flex items-center gap-2">
        <NotificationBell />
        <button
          type="button"
          onClick={logout}
          className="rounded-md px-2.5 py-1.5 text-sm font-medium transition-colors hover:bg-[var(--bg-raised)]"
          style={{ color: 'var(--text-muted)' }}
        >
          Sign out
        </button>
      </div>
    </header>
  )
}

/** Inline for the reason the bell's is: two icons do not earn a dependency. */
function SearchIcon() {
  return (
    <svg
      aria-hidden="true"
      className="pointer-events-none absolute top-1/2 left-2.5 h-3.5 w-3.5 -translate-y-1/2"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      style={{ color: 'var(--text-muted)' }}
    >
      <circle cx="11" cy="11" r="7" />
      <path d="m20 20-3.5-3.5" />
    </svg>
  )
}
