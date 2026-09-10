import { useState } from 'react'
import { useLocation } from 'react-router-dom'
import { LiquidBackground } from '@shared/components/common/LiquidBackground'
import { PageTransition } from '@shared/components/common/PageTransition'
import { MobileNavDrawer } from '@/components/layout/MobileNavDrawer'
import { PortalHeader } from '@/components/layout/PortalHeader'
import { PortalSidebar } from '@/components/layout/PortalSidebar'
import { PRIMARY_NAV } from '@/constants/navigation'

/**
 * The title bar's text.
 *
 * One nav list now, since 34d left only real screens — the account and "More" groups went with
 * the shell. `/draft` is deliberately absent from the nav (it is reached from a case, not from
 * a menu) so it falls through to its own name here rather than to "Home".
 */
function getPageTitle(pathname: string): string {
  if (pathname.startsWith('/draft')) return 'Your draft'
  const match = PRIMARY_NAV.find((item) => pathname.startsWith(item.to))
  return match?.label ?? 'Home'
}

export function PortalLayout() {
  const [mobileNavOpen, setMobileNavOpen] = useState(false)
  const location = useLocation()

  return (
    <div className="min-h-dvh bg-muted/30 lg:grid lg:grid-cols-[16rem_1fr]">
      <aside className="hidden lg:block">
        <div className="fixed inset-y-0 left-0 w-64">
          <PortalSidebar />
        </div>
      </aside>

      <MobileNavDrawer open={mobileNavOpen} onOpenChange={setMobileNavOpen} />

      <div className="relative flex min-h-dvh flex-col overflow-hidden">
        <LiquidBackground className="opacity-30" />
        <PortalHeader title={getPageTitle(location.pathname)} onMenuClick={() => setMobileNavOpen(true)} />
        <main className="relative flex-1 px-4 py-6 sm:px-6 lg:px-8">
          <PageTransition />
        </main>
      </div>
    </div>
  )
}
