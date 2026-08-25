import { Outlet } from 'react-router-dom'
import FiltersProvider from './filters'
import LeftNav from './LeftNav'
import TopBar from './TopBar'

/**
 * The frame every staff screen mounts inside. Rendered only for an authenticated session,
 * which is what lets `useMe()` throw rather than return null everywhere below it.
 *
 * **The nav is a flush, full-height dark rail** (`UI_MIGRATION_GUIDE.md`), which reverses the
 * previous language's floating inset card. It is fixed to the left edge with no gutter around
 * it, so the content column is offset by the sidebar width alone. The rail being dark is what
 * lets the content area stay quiet under twenty panels at once; a white rail beside white cards
 * needs a border to separate them and then competes with every card on screen.
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
          style={{ paddingLeft: 'var(--sidebar-width)' }}
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
