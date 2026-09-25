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
 * **What has NOT been decided is the process**, which is why `/` is a holding page and not a
 * sign-in. It is specced before it is coded — an expert is not a client (they are party-scoped,
 * they exist on the roster before they can sign in, and they may sit on two brands' panels, see
 * `PortalAccessService.mintForParty`) so the client's flow is a starting point and not a
 * template. Tracked in `open-decisions.md`.
 *
 * **A payouts screen is still coming** (Unit 35, D6 — an expert reads their own ledger rows), but
 * off `GET /api/portal/expert/payouts` and a party-scoped EXPERT token. Do not restore the old
 * page from git to serve it: whatever lands is built against the process above once it exists.
 */

const Welcome = lazy(() => import('@/pages/Welcome'))
const ExpertCasePortal = lazy(() => import('@/pages/portal/ExpertCasePortal'))
const NotFound = lazy(() => import('@shared/pages/NotFound'))

/**
 * The expert portal, its own app on its own origin.
 *
 * **`/` is a holding page as of 2026-09-18.** Staff-minted expert links are being retired — the
 * decision is that an expert signs in the way a client does — and that process is not designed
 * yet. Until it is, the front door says so rather than offering a door that is not there. The app
 * had no `/` at all before this, so the bare origin answered 404 and read as a broken portal.
 *
 * **`/case` is still mounted, and that is on purpose rather than an oversight.** Every link staff
 * have already minted points at it, and `PortalAccessService.mintForExpert` is still wired into
 * four staff screens — so removing the route would strand experts mid-review on cases already in
 * flight, silently, with a 404. It goes when the minting goes, in one change, not before.
 */
function AppRoutes() {
  return (
    <Routes>
      <Route path="/" element={<Welcome />} />

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
