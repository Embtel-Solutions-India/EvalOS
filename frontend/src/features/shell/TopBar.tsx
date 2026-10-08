import { useAuth, useMe } from '../../lib/authContext'
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
  const first = useMe().displayName.trim().split(/\s+/)[0]

  return (
    <header
      className="sticky top-0 z-20 flex items-center gap-3"
      style={{
        background: 'var(--bg-base)',
        minHeight: 'var(--header-height)',
        padding: `0 var(--shell-gutter)`,
      }}
    >
      {/* The Sketch's header opens with a greeting; it goes first and yields to the controls on
          narrow screens rather than pushing them off the bar. */}
      <p className="hidden shrink-0 text-base font-semibold min-[1500px]:block">Hello {first}, welcome back!</p>

      <div className="flex items-center gap-2">
        <DateFilter />
      </div>

      {/* The brand switcher and the search box are hidden for now (2026-10-08) and come back later:
          `BrandSwitcher.tsx` stays in the tree untouched, and the GM keeps the all-brands view. */}
      <div className="min-w-0 flex-1" />

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
