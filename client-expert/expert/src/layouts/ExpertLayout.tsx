import { TermsGate } from '@shared/legal/TermsGate'
import { LiveInvalidate } from '@shared/components/common/LiveInvalidate'
import { useQuery } from '@tanstack/react-query'
import { ChatProvider, ChatToast, PushRefresh, UnreadBadge, useChat } from '@evalos/chat'
import '@evalos/chat/chat.css'
import { Bell, BriefcaseBusiness, ChevronDown, Inbox, LayoutDashboard, LifeBuoy, LogOut, MessagesSquare, Wallet } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import { Navigate, NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom'
import { Logo } from '@shared/components/common/Logo'
import { hasPortalToken, signOut } from '@shared/services/apiClient'
import { createPortalChat } from '@shared/services/portalChat'
import { cn } from '@shared/utils/cn'
import { transfersToConfirm } from '@/lib/expertCase'
import { getMe, listCases, listPayouts } from '@/services/expertPortalService'

/**
 * The signed-in expert's shell (Unit 59): a sidebar with the two real screens, and one chat client
 * shared by the nav badge, the inbox and each case's panel.
 *
 * Dashboard · New cases (open offers) · Your cases · Messages · Payouts (spec 62; the expert confirms
 * receipt, Unit 63). On desktop a top bar carries the bell (unread messages) and who is signed in.
 *
 * **Only what exists is in the nav.** No profile, no resources — each would be a page with nothing
 * behind it.
 */
export function ExpertLayout() {
  const [chat] = useState(() => createPortalChat('expert'))
  const navigate = useNavigate()
  const { pathname } = useLocation()

  // The token is in localStorage (D73): a reload, a new tab and a restarted browser all keep it.
  if (!hasPortalToken()) return <Navigate to="/" replace />

  const title = pathname === '/dashboard' ? 'Dashboard' : pathname === '/new' ? 'New cases' : pathname === '/messages' ? 'Messages' : pathname === '/payouts' ? 'Payouts' : pathname === '/case' ? 'Case' : 'Your cases'

  // Unit 72 (D71): the policies are accepted once, before anything else.
  return (
    <TermsGate audience="expert" onSignOut={() => void signOut('/')}>
    <ChatProvider client={chat}>
      <PushRefresh workerUrl="/sw.js" />
      <LiveInvalidate prefix="expert-portal" />
      <div className="min-h-dvh bg-muted/40 lg:grid lg:grid-cols-[16rem_1fr]">
        <aside className="hidden lg:block">
          <div className="fixed inset-y-0 left-0 w-64">
            <Sidebar />
          </div>
        </aside>

        <div className="flex min-h-dvh min-w-0 flex-col">
          <header className="sticky top-0 z-30 flex h-14 items-center gap-3 border-b bg-background/95 px-4 backdrop-blur sm:px-6 lg:hidden">
            <span className="flex-1 text-base font-semibold text-foreground">{title}</span>
            <TopBarActions />
          </header>
          <header className="sticky top-0 z-30 hidden h-16 items-center justify-end gap-4 border-b bg-background/95 px-8 backdrop-blur lg:flex">
            <TopBarActions />
          </header>
          <main className="flex-1 px-4 pb-24 pt-6 sm:px-6 lg:px-8 lg:py-8">
            <Outlet />
          </main>
        </div>
      </div>
      <MobileTabBar />
      <ChatToast
        onOpen={(id) => {
          const caseId = chat.getState().conversations[id]?.caseId
          navigate(caseId ? `/case?caseId=${caseId}` : '/messages')
        }}
      />
    </ChatProvider>
    </TermsGate>
  )
}

/** The nav badges, shared by the sidebar and the phone tab bar. */
function useNavCounts() {
  // Same key as the cases page, so the count and the list are one fetch.
  const { data } = useQuery({ queryKey: ['expert-portal', 'cases'], queryFn: listCases, retry: false })
  // Unit 63: transfers the ENM recorded that this expert has not confirmed yet.
  const { data: payouts } = useQuery({ queryKey: ['expert-portal', 'payouts'], queryFn: listPayouts, retry: false })
  return {
    needsYou: data?.filter((item) => item.actionRequired).length ?? 0,
    offered: data?.filter((item) => item.offered).length ?? 0,
    toConfirm: transfersToConfirm(payouts ?? []),
  }
}

/** Phones: the nav as an app-style bar fixed to the bottom, icon over label. Sign out is in the top-bar name menu. */
function MobileTabBar() {
  const { needsYou, offered, toConfirm } = useNavCounts()
  const unread = useChat((s) => s.order.reduce((sum, id) => sum + (s.conversations[id]?.unread ?? 0), 0))
  const onCase = useLocation().pathname === '/case'
  const tabs = [
    { to: '/dashboard', label: 'Dashboard', icon: LayoutDashboard, count: 0 },
    { to: '/new', label: 'New', icon: Inbox, count: offered },
    { to: '/cases', label: 'Cases', icon: BriefcaseBusiness, count: needsYou },
    { to: '/messages', label: 'Messages', icon: MessagesSquare, count: unread },
    { to: '/payouts', label: 'Payouts', icon: Wallet, count: toConfirm },
  ]
  return (
    <nav aria-label="Main" className="fixed inset-x-0 bottom-0 z-40 flex border-t border-sidebar-border bg-sidebar pb-[env(safe-area-inset-bottom)] text-sidebar-foreground lg:hidden">
      {tabs.map(({ to, label, icon: Icon, count }) => (
        <NavLink
          key={to}
          to={to}
          className={({ isActive }) =>
            cn('flex flex-1 flex-col items-center gap-0.5 px-1 py-2 text-[11px] font-medium transition-colors', isActive || (to === '/cases' && onCase) ? 'text-white' : 'hover:text-white')
          }
        >
          <span className="relative">
            <Icon className="h-5 w-5" />
            {count > 0 && (
              <span className="absolute -right-3 -top-2 min-w-4 rounded-full bg-destructive px-1 text-center text-[10px] font-semibold leading-4 text-white" aria-label={`${count} waiting`}>
                {count}
              </span>
            )}
          </span>
          <span className="max-w-full truncate">{label}</span>
        </NavLink>
      ))}
    </nav>
  )
}

function Sidebar() {
  const { needsYou, offered, toConfirm } = useNavCounts()
  const onCase = useLocation().pathname === '/case'

  const item = (active: boolean) =>
    cn(
      'flex items-center gap-3 rounded-md px-3 py-2.5 text-sm font-medium transition-colors',
      active ? 'bg-white/10 text-white shadow-[inset_3px_0_0_hsl(var(--destructive))]' : 'text-sidebar-foreground hover:bg-white/5 hover:text-white',
    )

  return (
    <div className="flex h-full flex-col bg-sidebar text-sidebar-foreground">
      <div className="flex flex-col items-start gap-2 border-b border-sidebar-border px-5 py-5">
        <Logo variant="light" />
        <span className="text-xs text-sidebar-foreground/70">Expert Portal</span>
      </div>

      <nav className="flex-1 space-y-1 px-3 py-5" aria-label="Main">
        <NavLink to="/dashboard" className={({ isActive }) => item(isActive)}>
          <LayoutDashboard className="h-4 w-4 shrink-0" />
          <span className="flex-1">Dashboard</span>
        </NavLink>
        <NavLink to="/new" className={({ isActive }) => item(isActive)}>
          <Inbox className="h-4 w-4 shrink-0" />
          <span className="flex-1">New cases</span>
          {offered > 0 && (
            <span className="rounded-full bg-info px-2 py-0.5 text-xs font-semibold text-white" aria-label={`${offered} new`}>
              {offered}
            </span>
          )}
        </NavLink>
        <NavLink
          to="/cases"
         
          className={({ isActive }) => item(isActive || onCase)}
        >
          <BriefcaseBusiness className="h-4 w-4 shrink-0" />
          <span className="flex-1">Your cases</span>
          {needsYou > 0 && (
            <span className="rounded-full bg-destructive px-2 py-0.5 text-xs font-semibold text-white" aria-label={`${needsYou} need you`}>
              {needsYou}
            </span>
          )}
        </NavLink>
        <NavLink to="/messages" className={({ isActive }) => item(isActive)}>
          <MessagesSquare className="h-4 w-4 shrink-0" />
          <span className="flex-1">Messages</span>
          <UnreadBadge />
        </NavLink>
        <NavLink to="/payouts" className={({ isActive }) => item(isActive)}>
          <Wallet className="h-4 w-4 shrink-0" />
          <span className="flex-1">Payouts</span>
          {toConfirm > 0 && (
            <span className="rounded-full bg-info px-2 py-0.5 text-xs font-semibold text-white" aria-label={`${toConfirm} to confirm`}>
              {toConfirm}
            </span>
          )}
        </NavLink>
      </nav>

      <div className="space-y-3 p-3">
        <div className="rounded-lg border border-sidebar-border bg-white/5 p-3 text-xs">
          <p className="flex items-center gap-2 font-medium text-white">
            <LifeBuoy className="h-4 w-4" />
            Need help with a case?
          </p>
          <p className="mt-1 text-sidebar-foreground/70">Message the case team from the case itself, or from Messages.</p>
        </div>
        {/* Ends the token on the server and in this tab (Unit 75, D73). */}
        <button
          type="button"
          onClick={() => void signOut('/')}
          className="flex w-full items-center gap-3 rounded-md px-3 py-2 text-sm text-sidebar-foreground hover:bg-white/5 hover:text-white"
        >
          <LogOut className="h-4 w-4" />
          Sign out
        </button>
      </div>
    </div>
  )
}

/**
 * The bell and the signed-in expert. The bell is the chat's unread count — the only notification an
 * expert receives in the portal — and opens Messages. The name opens a menu with Sign out.
 */
function TopBarActions() {
  const unread = useChat((s) => s.order.reduce((sum, id) => sum + (s.conversations[id]?.unread ?? 0), 0))
  const { data: me } = useQuery({ queryKey: ['expert-portal', 'me'], queryFn: getMe, retry: false, staleTime: Infinity })
  const [open, setOpen] = useState(false)
  const ref = useRef<HTMLDivElement>(null)
  const initials = (me?.name ?? '').replace(/^(Dr|Prof)\.?\s+/i, '').split(/\s+/).filter(Boolean).map((w, i, all) => (i === 0 || i === all.length - 1 ? w[0] : '')).join('').toUpperCase() || '?'

  useEffect(() => {
    if (!open) return
    const outside = (e: MouseEvent) => {
      if (!ref.current?.contains(e.target as Node)) setOpen(false)
    }
    document.addEventListener('mousedown', outside)
    return () => document.removeEventListener('mousedown', outside)
  }, [open])

  return (
    <div className="flex items-center gap-3">
      <NavLink to="/messages" className="relative rounded-full p-2 text-muted-foreground hover:bg-muted hover:text-foreground" aria-label={unread > 0 ? `Messages, ${unread} unread` : 'Messages'}>
        <Bell className="h-5 w-5" />
        {unread > 0 && <span className="absolute right-1.5 top-1.5 h-2 w-2 rounded-full bg-destructive ring-2 ring-background" aria-hidden="true" />}
      </NavLink>
      <div className="relative" ref={ref}>
        <button type="button" onClick={() => setOpen(!open)} aria-haspopup="menu" aria-expanded={open} className="flex items-center gap-3 rounded-lg px-2 py-1 hover:bg-muted">
          <span className="flex h-9 w-9 items-center justify-center rounded-full bg-primary text-sm font-semibold text-primary-foreground">{initials}</span>
          <span className="hidden text-left sm:block">
            <span className="block text-sm font-semibold leading-tight text-foreground">{me?.name ?? 'Expert'}</span>
            <span className="block text-xs text-muted-foreground">Expert</span>
          </span>
          <ChevronDown className="hidden h-4 w-4 text-muted-foreground sm:block" />
        </button>
        {open && (
          <div role="menu" className="absolute right-0 z-40 mt-1 w-44 rounded-lg border bg-popover p-1 shadow-lg">
            {/* Ends the token on the server and in this tab (Unit 75, D73). */}
            <button type="button" role="menuitem" onClick={() => void signOut('/')} className="flex w-full items-center gap-2 rounded-md px-3 py-2 text-sm text-foreground hover:bg-muted">
              <LogOut className="h-4 w-4" /> Sign out
            </button>
          </div>
        )}
      </div>
    </div>
  )
}
