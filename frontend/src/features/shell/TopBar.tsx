import { useAuth } from '../../lib/authContext'
import BrandSwitcher from './BrandSwitcher'
import DateFilter from './DateFilter'
import NotificationBell from './NotificationBell'

/**
 * Scope on the left, account on the right, with the search field between them.
 *
 * The two controls on the left both answer "which work am I looking at" — brand, then
 * period — so they sit together rather than floating in a row of equals. Search is a
 * controlled input that goes nowhere: there is no search endpoint, and a box that silently
 * does nothing is better than one that pretends by filtering the current page. Its
 * placeholder says so rather than promising a date.
 *
 * **No background, no border, no shadow** (`UI_MIGRATION_GUIDE.md`): the header sits on the
 * canvas rather than being a white bar, which is what keeps the nav card and the content
 * cards the only white surfaces on screen. `sticky` rather than `fixed`, so the canvas
 * colour travels with it and nothing below needs a hardcoded offset.
 *
 * **The page title is not here yet, deliberately.** The template puts a 36px title in this
 * bar, and six screens currently render their own `h1` paired with their own eyebrow. Moving
 * it here means editing all six in one go or shipping a duplicated heading in between, so it
 * is the follow-up once the last screen is migrated — recorded in the guide rather than left
 * as a surprise.
 */
export default function TopBar() {
  const { logout } = useAuth()

  return (
    <header
      className="sticky top-0 z-20 flex items-center gap-3"
      style={{
        background: 'var(--bg-base)',
        minHeight: 'var(--header-height)',
        padding: `0 var(--shell-gutter)`,
      }}
    >
      <div className="flex items-center gap-2">
        <BrandSwitcher />
        <DateFilter />
      </div>

      <div className="min-w-0 flex-1">
        <label className="relative block max-w-sm">
          <span className="sr-only">Search cases</span>
          <SearchIcon />
          <input
            type="search"
            placeholder="Search — not available yet"
            disabled
            title="Case search has no endpoint yet"
            className="h-9 w-full pr-4 pl-9 text-sm"
            style={{
              background: 'var(--bg-surface)',
              border: '1px solid var(--border-default)',
              borderRadius: 'var(--radius-md)',
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
          className="h-9 px-4 text-sm font-medium transition-colors"
          style={{
            background: 'var(--bg-surface)',
            borderRadius: 'var(--radius-md)',
            color: 'var(--text-muted)',
            boxShadow: 'var(--shadow-card)',
          }}
        >
          Sign out
        </button>
      </div>
    </header>
  )
}

/** Inline for the reason the bell's is: a handful of icons do not earn a dependency. */
function SearchIcon() {
  return (
    <svg
      aria-hidden="true"
      className="pointer-events-none absolute top-1/2 left-3.5 h-3.5 w-3.5 -translate-y-1/2"
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
