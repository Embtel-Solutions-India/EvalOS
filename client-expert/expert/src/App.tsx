import { Suspense, lazy } from 'react'
import { Route, BrowserRouter, Routes } from 'react-router-dom'
import { Toaster } from '@shared/components/ui/sonner'
import { AppLoadingScreen } from '@shared/components/common/AppLoadingScreen'
import { ErrorBoundary } from '@shared/components/common/ErrorBoundary'

/*
 * 2026-09-10 — the expert account shell was DELETED, not parked.
 *
 * Five routes went with it (/expert/login, /expert, /expert/cases/:id, /expert/payments,
 * /expert/profile) along with their pages, layouts, guards, auth context, header, sidebar and the
 * two mock services behind them. All five presumed an expert *account* — the alternative **D1
 * refused rather than deferred**, because a password store needs a reset flow and a reset flow
 * needs a mail channel invariant 14 says does not exist.
 *
 * Deleted rather than kept, unlike the client app's intake funnel: nothing in the new direction
 * wants an expert account. The expert is reached by a link and only by a link.
 *
 * **A payouts screen is still coming** (Unit 35, D6 — an expert reads their own ledger rows), but
 * off `GET /api/portal/expert/payouts` and a party-scoped EXPERT token. Do not restore the old
 * page from git to serve it; that would answer D1 the way it was refused.
 */

const ExpertCasePortal = lazy(() => import('@/pages/portal/ExpertCasePortal'))
const NotFound = lazy(() => import('@shared/pages/NotFound'))

/**
 * The expert portal, its own app on its own origin.
 *
 * **One live route: `/case`.** An expert opens the link they were sent, answers, signs, and closes
 * the tab. There is no dashboard to land on and no account to log into — see the parked note above.
 */
function AppRoutes() {
  return (
    <Routes>
      {/* Unit 34e. The token comes out of the URL fragment; there is no shell to mount it in. */}
      <Route path="/case" element={<ExpertCasePortal />} />

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
