import { Outlet } from 'react-router-dom'
import { FiltersProvider } from './filters'
import LeftNav from './LeftNav'
import TopBar from './TopBar'

/**
 * The frame every staff screen mounts inside: fixed left nav, top bar, scrolling main
 * region. Rendered only for an authenticated session, which is what lets `useMe()`
 * throw rather than return null everywhere below it.
 */
export default function AppShell() {
  return (
    <FiltersProvider>
      <div className="flex h-svh" style={{ background: 'var(--bg-base)' }}>
        <LeftNav />
        <div className="flex min-w-0 flex-1 flex-col">
          <TopBar />
          <main className="min-h-0 flex-1 overflow-y-auto px-6 py-6">
            <Outlet />
          </main>
        </div>
      </div>
    </FiltersProvider>
  )
}
