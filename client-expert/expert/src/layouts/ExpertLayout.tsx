import { TermsGate } from '@shared/legal/TermsGate'
import * as DialogPrimitive from '@radix-ui/react-dialog'
import { useQuery } from '@tanstack/react-query'
import { ChatProvider, ChatToast, UnreadBadge, useChat } from '@evalos/chat'
import '@evalos/chat/chat.css'
import { Bell, BriefcaseBusiness, ChevronDown, Inbox, LayoutDashboard, LifeBuoy, LogOut, Menu, MessagesSquare, Wallet, X } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import { Navigate, NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom'
import { Button } from '@shared/components/ui/button'
import { Logo } from '@shared/components/common/Logo'
import { hasPortalToken } from '@shared/services/apiClient'
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
  const [menuOpen, setMenuOpen] = useState(false)
  const [chat] = useState(() => createPortalChat('expert'))
  const navigate = useNavigate()
  const { pathname } = useLocation()

  // The token is memory-only, so a reload lands here without one: back to the door.
  if (!hasPortalToken()) return <Navigate to="/" replace />

  const title = pathname === '/dashboard' ? 'Dashboard' : pathname === '/new' ? 'New cases' : pathname === '/messages' ? 'Messages' : pathname === '/payouts' ? 'Payouts' : pathname === '/case' ? 'Case' : 'Your cases'

  // Unit 72 (D71): the policies are accepted once, before anything else.
  return (
    <TermsGate audience="expert" onSignOut={() => window.location.assign('/')}>
    <ChatProvider client={chat}>
      <div className="min-h-dvh bg-muted/40 lg:grid lg:grid-cols-[16rem_1fr]">
        <aside className="hidden lg:block">
          <div className="fixed inset-y-0 left-0 w-64">
            <Sidebar />
          </div>
        </aside>

        <DialogPrimitive.Root open={menuOpen} onOpenChange={setMenuOpen}>
          <DialogPrimitive.Portal>
            <DialogPrimitive.Overlay className="fixed inset-0 z-40 bg-black/50 data-[state=open]:animate-fade-in lg:hidden" />
            <DialogPrimitive.Content
              className="fixed inset-y-0 left-0 z-50 w-72 max-w-[85vw] data-[state=open]:animate-slide-in-left lg:hidden"
              aria-describedby={undefined}
            >
              <DialogPrimitive.Title className="sr-only">Navigation menu</DialogPrimitive.Title>
              <DialogPrimitive.Close className="absolute right-3 top-3 z-10 rounded-md p-1.5 text-sidebar-foreground hover:bg-sidebar-accent">
                <X className="h-5 w-5" />
                <span className="sr-only">Close menu</span>
              </DialogPrimitive.Close>
              <Sidebar onNavigate={() => setMenuOpen(false)} />
            </DialogPrimitive.Content>
          </DialogPrimitive.Portal>
        </DialogPrimitive.Root>

        <div className="flex min-h-dvh min-w-0 flex-col">
          <header className="sticky top-0 z-30 flex h-14 items-center gap-3 border-b bg-background/95 px-4 backdrop-blur sm:px-6 lg:hidden">
            <Button variant="ghost" size="icon" onClick={() => setMenuOpen(true)} aria-label="Open menu">
              <Menu className="h-5 w-5" />
            </Button>
            <span className="flex-1 text-base font-semibold text-foreground">{title}</span>
            <TopBarActions />
          </header>
          <header className="sticky top-0 z-30 hidden h-16 items-center justify-end gap-4 border-b bg-background/95 px-8 backdrop-blur lg:flex">
            <TopBarActions />
          </header>
          <main className="flex-1 px-4 py-6 sm:px-6 lg:px-8 lg:py-8">
            <Outlet />
          </main>
        </div>
      </div>
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

function Sidebar({ onNavigate }: { onNavigate?: () => void }) {
  // Same key as the cases page, so the count and the list are one fetch.
  const { data } = useQuery({ queryKey: ['expert-portal', 'cases'], queryFn: listCases, retry: false })
  const needsYou = data?.filter((item) => item.actionRequired).length ?? 0
  const offered = data?.filter((item) => item.offered).length ?? 0
  // Unit 63: transfers the ENM recorded that this expert has not confirmed yet.
  const { data: payouts } = useQuery({ queryKey: ['expert-portal', 'payouts'], queryFn: listPayouts, retry: false })
  const toConfirm = transfersToConfirm(payouts ?? [])
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
        <NavLink to="/dashboard" onClick={onNavigate} className={({ isActive }) => item(isActive)}>
          <LayoutDashboard className="h-4 w-4 shrink-0" />
          <span className="flex-1">Dashboard</span>
        </NavLink>
        <NavLink to="/new" onClick={onNavigate} className={({ isActive }) => item(isActive)}>
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
          onClick={onNavigate}
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
        <NavLink to="/messages" onClick={onNavigate} className={({ isActive }) => item(isActive)}>
          <MessagesSquare className="h-4 w-4 shrink-0" />
          <span className="flex-1">Messages</span>
          <UnreadBadge />
        </NavLink>
        <NavLink to="/payouts" onClick={onNavigate} className={({ isActive }) => item(isActive)}>
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
        {/* The token lives only in memory, so a full load of the door is the whole of signing out. */}
        <button
          type="button"
          onClick={() => window.location.assign('/')}
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
            {/* The token lives only in memory, so a full load of the door is the whole of signing out. */}
            <button type="button" role="menuitem" onClick={() => window.location.assign('/')} className="flex w-full items-center gap-2 rounded-md px-3 py-2 text-sm text-foreground hover:bg-muted">
              <LogOut className="h-4 w-4" /> Sign out
            </button>
          </div>
        )}
      </div>
    </div>
  )
}
