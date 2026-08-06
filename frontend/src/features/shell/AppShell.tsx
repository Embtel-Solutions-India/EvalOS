import { Outlet } from 'react-router-dom'
import FiltersProvider from './filters'
import LeftNav from './LeftNav'
import TopBar from './TopBar'

/**
 * The frame every staff screen mounts inside. Rendered only for an authenticated session,
 * which is what lets `useMe()` throw rather than return null everywhere below it.
 *
 * **The nav is a floating card, not a flush panel** (`UI_MIGRATION_GUIDE.md`): it is fixed
 * and inset from the viewport on every side, so the tinted canvas shows around it and the
 * only white surfaces in the app are the nav and the content cards. That is what makes a
 * card read as raised here without a heavy shadow, and it is why the content column is
 * offset by `sidebar + 2 × gutter` rather than sitting in a flex row with the nav.
 *
 * The header is `sticky` rather than `fixed`: it stays in flow, so nothing has to be
 * padded down by a hardcoded header height, and the document scrolls normally. The
 * template fixes it and then pays for that with a `margin-top` on the content.
 */
export default function AppShell() {
  return (
    <FiltersProvider>
      <div className="min-h-svh" style={{ background: 'var(--bg-base)' }}>
        <LeftNav />
        <div
          className="flex min-h-svh min-w-0 flex-col"
          style={{ paddingLeft: 'calc(var(--sidebar-width) + var(--shell-gutter) * 2)' }}
        >
          <TopBar />
          <main
            className="min-w-0 flex-1"
            style={{ padding: `0 var(--shell-gutter) var(--shell-gutter)` }}
          >
            <Outlet />
          </main>
        </div>
      </div>
    </FiltersProvider>
  )
}
