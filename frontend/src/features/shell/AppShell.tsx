import { useEffect, useState, type ReactNode } from 'react'
import { Outlet, useNavigate } from 'react-router-dom'
import { ChatProvider, ChatToast } from '@evalos/chat'
import '@evalos/chat/chat.css'
import { chatsFor, createStaffChat } from '../../lib/chat'
import { useMe } from '../../lib/authContext'
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
    <Chat>
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
    </Chat>
  )
}

/**
 * One chat client for the signed-in shell (Unit 57): the nav badge, the inbox, each case's panel
 * and the toast share it. A push notification clicked while the app is open routes here in place
 * (public/sw.js posts `evalos:open`).
 */
function Chat({ children }: { children: ReactNode }) {
  const me = useMe()
  const navigate = useNavigate()
  const [client] = useState(createStaffChat)

  useEffect(() => {
    if (!('serviceWorker' in navigator)) return
    const onMessage = (event: MessageEvent) => {
      const path = event.data?.type === 'evalos:open' ? event.data.path : null
      if (typeof path === 'string' && path.startsWith('/')) navigate(path)
    }
    navigator.serviceWorker.addEventListener('message', onMessage)
    return () => navigator.serviceWorker.removeEventListener('message', onMessage)
  }, [navigate])

  if (!chatsFor(me.role)) return children
  return (
    <ChatProvider client={client}>
      {children}
      <ChatToast onOpen={(id) => navigate(`/conversations/${id}`)} />
    </ChatProvider>
  )
}
