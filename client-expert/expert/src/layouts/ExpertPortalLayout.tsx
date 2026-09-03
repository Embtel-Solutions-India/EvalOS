import { useState } from 'react'
import { useLocation } from 'react-router-dom'
import { LiquidBackground } from '@shared/components/common/LiquidBackground'
import { PageTransition } from '@shared/components/common/PageTransition'
import { ExpertHeader } from '@/components/layout/ExpertHeader'
import { ExpertMobileNavDrawer } from '@/components/layout/ExpertMobileNavDrawer'
import { ExpertSidebar } from '@/components/layout/ExpertSidebar'
import { EXPERT_NAV } from '@/constants/expertNavigation'

function getPageTitle(pathname: string): string {
  if (pathname.startsWith('/expert/cases/')) return 'Case Details'
  // Every EXPERT_NAV entry is an exact top-level path (no nested routes of
  // its own), so exact match is correct and unambiguous here — a prefix
  // check would make Dashboard's `to: '/expert'` match every other expert
  // route too, since '/expert/payments' also starts with '/expert'.
  const match = EXPERT_NAV.find((item) => pathname === item.to)
  return match?.label ?? 'Expert Portal'
}

export function ExpertPortalLayout() {
  const [mobileNavOpen, setMobileNavOpen] = useState(false)
  const location = useLocation()

  return (
    <div className="min-h-dvh bg-muted/30 lg:grid lg:grid-cols-[16rem_1fr]">
      <aside className="hidden lg:block">
        <div className="fixed inset-y-0 left-0 w-64">
          <ExpertSidebar />
        </div>
      </aside>

      <ExpertMobileNavDrawer open={mobileNavOpen} onOpenChange={setMobileNavOpen} />

      <div className="relative flex min-h-dvh flex-col overflow-hidden">
        <LiquidBackground className="opacity-30" />
        <ExpertHeader title={getPageTitle(location.pathname)} onMenuClick={() => setMobileNavOpen(true)} />
        <main className="relative flex-1 px-4 py-6 sm:px-6 lg:px-8">
          <PageTransition />
        </main>
      </div>
    </div>
  )
}
