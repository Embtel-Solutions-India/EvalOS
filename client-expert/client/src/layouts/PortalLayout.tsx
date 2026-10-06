import { useEffect, useState } from 'react'
import { LiveInvalidate } from '@shared/components/common/LiveInvalidate'
import { ChatProvider, ChatToast, PushRefresh } from '@evalos/chat'
import '@evalos/chat/chat.css'
import { Menu } from 'lucide-react'
import { Navigate, useLocation, useNavigate } from 'react-router-dom'
import { Button } from '@shared/components/ui/button'
import { usePortalToken } from '@shared/hooks/usePortalToken'
import { LiquidBackground } from '@shared/components/common/LiquidBackground'
import { PageTransition } from '@shared/components/common/PageTransition'
import { MobileNavDrawer } from '@/components/layout/MobileNavDrawer'
import { PortalSidebar } from '@/components/layout/PortalSidebar'
import { TermsGate } from '@shared/legal/TermsGate'
import { createPortalChat } from '@shared/services/portalChat'
import { signOut } from '@shared/services/apiClient'

/**
 * With no credential, the door — not a page explaining a link.
 *
 * **The token is kept in `localStorage` (D73, amended 2026-10-07)**, so a reload stays signed in and
 * closing the browser does not end it; the server's 7-day expiry, Sign out and a 401 do.
 * (It was `sessionStorage` from Unit 75, which ended the session with the tab.)
 *
 * **Guarded here rather than in six pages**, because every authenticated route is already inside
 * this layout and `usePortalToken` lifts the fragment on first render — which happens here,
 * before any child. The pages keep their own `NO_TOKEN` copy for a direct render; nothing reaches
 * it through the router.
 */
export function PortalLayout() {
  const [mobileNavOpen, setMobileNavOpen] = useState(false)
  const location = useLocation()
  const tokenPresent = usePortalToken()
  // One chat client for the signed-in shell: the nav badge and the inbox share it.
  const [chat] = useState(() => createPortalChat('client'))
  const navigate = useNavigate()

  // A push notification clicked while this tab is open (public/sw.js): route in place, keeping the token.
  useEffect(() => {
    if (!('serviceWorker' in navigator)) return
    const onMessage = (event: MessageEvent) => {
      const path = event.data?.type === 'evalos:open' ? event.data.path : null
      if (typeof path === 'string' && path.startsWith('/')) navigate(path)
    }
    navigator.serviceWorker.addEventListener('message', onMessage)
    return () => navigator.serviceWorker.removeEventListener('message', onMessage)
  }, [navigate])

  if (!tokenPresent) {
    return <Navigate to="/signin" replace state={{ from: location.pathname + location.search }} />
  }

  // Unit 72 (D71): the policies are accepted once, before anything else. Declining signs out (D73).
  return (
    <TermsGate audience="client" onSignOut={() => void signOut('/signin')}>
    <ChatProvider client={chat}>
      <LiveInvalidate prefix="portal" />
      <PushRefresh workerUrl="/sw.js" />
      <div className="flex min-h-dvh flex-col bg-muted/30">
        <div className="flex-1 lg:grid lg:grid-cols-[16rem_1fr]">
          <aside className="hidden bg-sidebar lg:block">
            <div className="sticky top-0 flex max-h-dvh flex-col">
              <PortalSidebar />
            </div>
          </aside>

          <MobileNavDrawer open={mobileNavOpen} onOpenChange={setMobileNavOpen} />

          <div className="relative flex h-full flex-col overflow-hidden">
            <LiquidBackground className="opacity-30" />
            <main className="relative flex-1 px-4 py-6 sm:px-6 lg:px-8">
              {/* No top bar; on phones the sidebar is the drawer, so its button stays. */}
              <Button variant="ghost" size="icon" className="-ml-2 mb-2 lg:hidden" onClick={() => setMobileNavOpen(true)} aria-label="Open menu">
                <Menu className="h-5 w-5" />
              </Button>
              <PageTransition />
            </main>
          </div>
        </div>
      </div>
      {/* A message while the client is elsewhere in the portal: open that conversation. */}
      <ChatToast onOpen={(id) => navigate(`/conversations?c=${id}`)} />
    </ChatProvider>
    </TermsGate>
  )
}
