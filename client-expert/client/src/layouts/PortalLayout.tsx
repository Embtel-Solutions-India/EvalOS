import { useEffect, useState } from 'react'
import { ChatProvider, ChatToast } from '@evalos/chat'
import '@evalos/chat/chat.css'
import { Navigate, useLocation, useNavigate } from 'react-router-dom'
import { usePortalToken } from '@shared/hooks/usePortalToken'
import { LiquidBackground } from '@shared/components/common/LiquidBackground'
import { PageTransition } from '@shared/components/common/PageTransition'
import { MobileNavDrawer } from '@/components/layout/MobileNavDrawer'
import { PortalHeader } from '@/components/layout/PortalHeader'
import { PortalSidebar } from '@/components/layout/PortalSidebar'
import { SiteFooter } from '@/components/layout/SiteFooter'
import { PRIMARY_NAV } from '@/constants/navigation'
import { createPortalChat } from '@/services/chatService'

/**
 * The title bar's text.
 *
 * One nav list now, since 34d left only real screens — the account and "More" groups went with
 * the shell. A case page is reached from Home, not from a menu, so it gets its own name here
 * rather than falling through to "Home".
 */
function getPageTitle(pathname: string): string {
  if (pathname.startsWith('/cases/')) return 'Your case'
  const match = PRIMARY_NAV.find((item) => pathname.startsWith(item.to))
  return match?.label ?? 'Home'
}

/**
 * With no credential, the door — not a page explaining a link.
 *
 * **The token is memory-only and that is not changing.** It lives in `apiClient`'s module scope,
 * never `localStorage`, so a reload or a bookmark loses it by design. Before Unit 42 the only way
 * to have one was a mailed link, so every screen's "open the full link we sent you" was true.
 * It stopped being true the moment a password could mint one: `signIn` navigates to `/dashboard`
 * with no fragment, so the first refresh dropped the client onto a page telling them to go find
 * an email that, for them, does not exist.
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
  // One chat client for the signed-in shell: the nav badge, the inbox and each case's panel share it.
  const [chat] = useState(createPortalChat)
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

  return (
    <ChatProvider client={chat}>
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
          <SiteFooter />
        </div>
      </div>
      {/* A message while the client is elsewhere in the portal: open that case's page. */}
      <ChatToast
        onOpen={(id) => {
          const caseId = chat.getState().conversations[id]?.caseId
          navigate(caseId ? `/cases/${caseId}` : '/conversations')
        }}
      />
    </ChatProvider>
  )
}
