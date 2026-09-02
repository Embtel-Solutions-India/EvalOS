import { Navigate, Route, Routes, useLocation } from 'react-router-dom'
import Forbidden from './components/Forbidden'
import BoardView from './features/board/BoardView'
import InboxPage from './features/queues/InboxPage'
import DraftQueuePage from './features/queues/DraftQueuePage'
import MyDraftsPage from './features/queues/MyDraftsPage'
import PmNotesPage from './features/queues/PmNotesPage'
import ExpertAssignmentPage from './features/queues/ExpertAssignmentPage'
import DeliveryQueuePage from './features/queues/DeliveryQueuePage'
import ChecklistBoard from './features/checklist/ChecklistBoard'
import PortalRoot from './features/client-portal/PortalRoot'
import ExpertRoster from './features/experts/ExpertRoster'
import MarketingPipelinePage from './features/marketing/MarketingPipelinePage'
import PayoutBatch from './features/payouts/PayoutBatch'
import ExpertPayouts from './features/payouts/ExpertPayouts'
import PaymentDetail from './features/payouts/PaymentDetail'
import LoginPage from './features/auth/LoginPage'
import RoleDashboard from './features/dashboards/RoleDashboard'
import AppShell from './features/shell/AppShell'
import PlaceholderPage from './features/shell/PlaceholderPage'
import CaseDetailPage from './features/case/CaseDetail'
import {
  CASE_DETAIL_PATH,
  EXPERT_PAYOUTS_PATH,
  NAV_ITEMS,
  PAYMENT_DETAIL_PATH,
  homePathFor,
  mayReach,
} from './features/shell/navigation'
import AuthProvider from './lib/auth'
import { useAuth, useMe } from './lib/authContext'
import NotFound from './pages/NotFound'

/**
 * The nav paths that have a real screen behind them; everything else in `NAV_ITEMS` still
 * renders the placeholder until its unit lands.
 *
 * `/board` and `/my-cases` are the same component on purpose: a Case Manager's "My cases" is
 * the board narrowed by their own assignment, and the narrowing is the server's job.
 */
const SCREENS: Record<string, React.ReactNode> = {
  '/board': <BoardView />,
  '/my-cases': <BoardView />,
  '/my-drafts': <MyDraftsPage />,
  '/pm-notes': <PmNotesPage />,
  '/inbox': <InboxPage />,
  '/drafts': <DraftQueuePage />,
  '/expert-assignment': <ExpertAssignmentPage />,
  '/delivery': <DeliveryQueuePage />,
  '/checklists': <ChecklistBoard />,
  '/experts': <ExpertRoster />,
  '/payouts': <PayoutBatch />,
  // Three funnels, one component: same stage shape, same question, different GHL pipeline. The
  // heading each carries is only a placeholder — GHL's own pipeline name replaces it on load.
  //
  // `/sales/pipeline` is under its own nav heading rather than Marketing (see `navigation.ts`),
  // but it is the same screen and deliberately not a copy of it: three route entries pointing at
  // one component is the whole cost of the third funnel.
  '/marketing/google-ads': (
    <MarketingPipelinePage funnel="ads" title="Google Ads pipeline" />
  ),
  '/marketing/email': (
    <MarketingPipelinePage funnel="email" title="Email marketing pipeline" />
  ),
  '/sales/pipeline': <MarketingPipelinePage funnel="sales" title="Sales pipeline" />,
  // The sales *desk* is not a fourth funnel and shares nothing with the three above: that
  // component draws an aggregate from a cached read, this one draws individual deals with the
  // contact on them, live, with actions that write to GHL. Same pipeline, different question.
}

/** The client portal's path prefix. `/portal/expert` joins it in Unit 15. */
const PORTAL_PREFIX = '/portal/'

/**
 * Two surfaces, and the split is here rather than in `main.tsx` so the whole route table stays in
 * one file.
 *
 * **The portal is answered before any staff-session code runs, and outside `AuthProvider`** — which
 * is mounted below, around the staff surface only. That is the point of the branch, not an
 * optimization: a client is not a staff user with fewer links, and mounting the provider on their
 * page would read the staff token out of `sessionStorage` and call `/api/me` for somebody who has no
 * account. It gets no `AppShell`, no nav and no brand switcher either — a portal token admits one
 * case, so there is one screen and nowhere to navigate.
 */
export default function App() {
  const { pathname } = useLocation()

  if (pathname.startsWith(PORTAL_PREFIX)) {
    return <PortalRoot />
  }

  return (
    <AuthProvider>
      <StaffApp />
    </AuthProvider>
  )
}

/**
 * The staff surface: three states, one router.
 *
 * Unauthenticated renders only the login page, so no shell code runs without a session and
 * `useMe()` below the shell can throw rather than return null. Every in-shell route is wrapped in
 * {@link RoleRoute}, which checks the same `navigation.ts` table the nav filters — a deep link
 * outside the role's allow-list renders the 403 view rather than redirecting, so the user can see
 * which URL was refused.
 */
function StaffApp() {
  const { state } = useAuth()

  if (state.status === 'loading') {
    return (
      <div className="flex min-h-svh items-center justify-center" style={{ background: 'var(--bg-base)' }}>
        <p className="text-sm" style={{ color: 'var(--text-muted)' }}>
          Restoring your session…
        </p>
      </div>
    )
  }

  if (state.status === 'anonymous') {
    return (
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route path="*" element={<Navigate to="/login" replace />} />
      </Routes>
    )
  }

  return (
    <Routes>
      {/* Already signed in: login is not a place to go back to. */}
      {/* `homePathFor`, not a hardcoded `/dashboard`: since Unit 29 not every role has one. */}
      <Route path="/login" element={<Navigate to={homePathFor(state.me.role)} replace />} />

      <Route element={<AppShell />}>
        <Route index element={<Navigate to={homePathFor(state.me.role)} replace />} />
        <Route path="/dashboard" element={<RoleDashboard />} />

        {/* Reached from a board card, so it has no nav entry — but the same table gates it. */}
        <Route
          path={CASE_DETAIL_PATH}
          element={
            <RoleRoute path={CASE_DETAIL_PATH}>
              <CaseDetailPage />
            </RoleRoute>
          }
        />

        {/* Both reached from the payout batch screen, so neither is listed — and both are
            gated by the same table, for the reason the case detail route is. */}
        <Route
          path={EXPERT_PAYOUTS_PATH}
          element={
            <RoleRoute path={EXPERT_PAYOUTS_PATH}>
              <ExpertPayouts />
            </RoleRoute>
          }
        />
        <Route
          path={PAYMENT_DETAIL_PATH}
          element={
            <RoleRoute path={PAYMENT_DETAIL_PATH}>
              <PaymentDetail />
            </RoleRoute>
          }
        />

        {NAV_ITEMS.filter((item) => item.path !== '/dashboard').map((item) => (
          <Route
            key={item.path}
            path={item.path}
            element={
              <RoleRoute path={item.path}>{SCREENS[item.path] ?? <PlaceholderPage />}</RoleRoute>
            }
          />
        ))}

        <Route path="*" element={<NotFound />} />
      </Route>
    </Routes>
  )
}

/** The route-level half of the nav table. The server still enforces the real thing. */
function RoleRoute({ path, children }: { path: string; children: React.ReactNode }) {
  const me = useMe()
  return mayReach(me.role, path) ? children : <Forbidden />
}
