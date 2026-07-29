import { useAuth } from '../../lib/auth'
import BrandSwitcher from './BrandSwitcher'
import DateFilter from './DateFilter'
import NotificationBell from './NotificationBell'

/**
 * Brand switcher, date filter, search, bell, sign out. Search is wired as a controlled
 * input that goes nowhere: there is no search endpoint yet, and a box that silently
 * does nothing is better than one that pretends by filtering the current page.
 */
export default function TopBar() {
  const { logout } = useAuth()

  return (
    <header
      className="flex items-center gap-3 border-b px-6 py-3"
      style={{ background: 'var(--bg-surface)', borderColor: 'var(--border-default)' }}
    >
      <BrandSwitcher />
      <DateFilter />

      <div className="flex-1">
        <label>
          <span className="sr-only">Search cases</span>
          <input
            type="search"
            placeholder="Search — coming with the case table"
            disabled
            className="w-full max-w-sm rounded-md border px-3 py-1.5 text-sm"
            style={{
              background: 'var(--bg-raised)',
              borderColor: 'var(--border-default)',
              color: 'var(--text-muted)',
            }}
          />
        </label>
      </div>

      <NotificationBell />

      <button
        type="button"
        onClick={logout}
        className="rounded-md px-2.5 py-1.5 text-sm font-medium"
        style={{ color: 'var(--text-muted)' }}
      >
        Sign out
      </button>
    </header>
  )
}
