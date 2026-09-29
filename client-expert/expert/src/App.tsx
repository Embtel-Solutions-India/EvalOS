import { Suspense, lazy } from 'react'
import { Route, BrowserRouter, Routes } from 'react-router-dom'
import { Toaster } from '@shared/components/ui/sonner'
import { AppLoadingScreen } from '@shared/components/common/AppLoadingScreen'
import { ErrorBoundary } from '@shared/components/common/ErrorBoundary'
import { ExpertLayout } from '@/layouts/ExpertLayout'

/*
 * 2026-09-10 — the expert account shell was DELETED, not parked.
 *
 * Five routes went with it (/expert/login, /expert, /expert/cases/:id, /expert/payments,
 * /expert/profile) along with their pages, layouts, guards, auth context, header, sidebar and the
 * two mock services behind them. All five presumed an expert *account* — the alternative **D1
 * refused rather than deferred**, because a password store needs a reset flow and a reset flow
 * needs a mail channel invariant 14 says does not exist.
 *
 * Deleted rather than kept, unlike the client app's intake funnel: nothing in that direction
 * wanted an expert account.
 *
 * **2026-09-18: the direction changed, and this note is edited rather than argued with.** "The
 * expert is reached by a link and only by a link" was the rule here, and it is no longer the
 * rule — staff-minted expert links are being retired and an expert will sign in the way a client
 * does. **D1's reasoning did not survive its premise**: it refused an account because a password
 * store needs a reset flow and a reset flow needs a mail channel, and Unit 52 built that channel.
 * So the refusal is spent, not repeated.
 *
 * **What had NOT been decided was the process** (settled by Unit 59: `/` is the sign-in). It is specced before it is coded — an expert is not a client (they are party-scoped,
 * they exist on the roster before they can sign in, and they may sit on two brands' panels, see
 * `PortalAccessService.mintForParty`) so the client's flow is a starting point and not a
 * template. Tracked in `open-decisions.md`.
 *
 * **Payouts are read-only (D59 as edited, spec 62).** The Expert Network Manager settles by hand on
 * the staff screens; `/payouts` shows the expert what has been recorded.
 */

const Welcome = lazy(() => import('@/pages/Welcome'))
const ExpertCasePortal = lazy(() => import('@/pages/portal/ExpertCasePortal'))
const SetPassword = lazy(() => import('@/pages/auth/SetPassword'))
const Cases = lazy(() => import('@/pages/Cases'))
const Messages = lazy(() => import('@/pages/Messages'))
const NewCases = lazy(() => import('@/pages/NewCases'))
const Payouts = lazy(() => import('@/pages/Payouts'))
const Dashboard = lazy(() => import('@/pages/Dashboard'))
const NotFound = lazy(() => import('@shared/pages/NotFound'))

/**
 * The expert portal, its own app on its own origin.
 *
 * **Signing in is the only way in (Unit 59, 2026-09-28).** An expert on the roster signs up at `/`,
 * sets a password from the emailed link and signs in; `/cases` lists their cases and `/case?caseId=`
 * opens one. Staff-minted links, and the token-in-the-URL `/case` they fed, were removed.
 */
function AppRoutes() {
  return (
    <Routes>
      {/* Unit 59: the door, the emailed link, and a signed-in expert's cases. */}
      <Route path="/" element={<Welcome />} />
      <Route path="/set-password" element={<SetPassword />} />
      {/* Signed-in screens share the sidebar shell, which also guards the token. */}
      <Route element={<ExpertLayout />}>
        <Route path="/dashboard" element={<Dashboard />} />
        <Route path="/new" element={<NewCases />} />
        <Route path="/cases" element={<Cases />} />
        <Route path="/case" element={<ExpertCasePortal />} />
        <Route path="/messages" element={<Messages />} />
        <Route path="/payouts" element={<Payouts />} />
      </Route>

      <Route path="*" element={<NotFound />} />
    </Routes>
  )
}

function AppShell() {
  return (
    <BrowserRouter>
      <ErrorBoundary>
        <Suspense fallback={<AppLoadingScreen />}>
          <AppRoutes />
        </Suspense>
      </ErrorBoundary>
    </BrowserRouter>
  )
}

export default function App() {
  return (
    <>
      <AppShell />
      <Toaster />
    </>
  )
}
