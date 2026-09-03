import { useState } from 'react'
import { useLocation } from 'react-router-dom'
import { LiquidBackground } from '@shared/components/common/LiquidBackground'
import { PageTransition } from '@shared/components/common/PageTransition'
import { MobileNavDrawer } from '@/components/layout/MobileNavDrawer'
import { PortalHeader } from '@/components/layout/PortalHeader'
import { PortalSidebar } from '@/components/layout/PortalSidebar'
import { ACCOUNT_NAV, PRIMARY_NAV, SECONDARY_NAV, SUPPORT_NAV } from '@/constants/navigation'

const ALL_NAV = [...PRIMARY_NAV, ...SECONDARY_NAV, ...SUPPORT_NAV, ...ACCOUNT_NAV]

function getPageTitle(pathname: string): string {
  const match = ALL_NAV.find((item) => pathname.startsWith(item.to))
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
