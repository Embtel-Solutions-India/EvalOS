import { Navigate, Route, Routes } from 'react-router-dom'
import Forbidden from './components/Forbidden'
import BoardView from './features/board/BoardView'
import InboxPage from './features/queues/InboxPage'
import DraftQueuePage from './features/queues/DraftQueuePage'
import MyDraftsPage from './features/queues/MyDraftsPage'
import PmNotesPage from './features/queues/PmNotesPage'
import ExpertAssignmentPage from './features/queues/ExpertAssignmentPage'
import DeliveryQueuePage from './features/queues/DeliveryQueuePage'
import ChecklistBoard from './features/checklist/ChecklistBoard'
import ExpertRoster from './features/experts/ExpertRoster'
import MeetingsPage from './features/meetings/MeetingsPage'
import NewMeetingPage from './features/meetings/NewMeetingPage'
import DealPage from './features/opportunities/DealPage'
import NewDealPage from './features/opportunities/NewDealPage'
import NewLeadPage from './features/opportunities/NewLeadPage'
import OpportunityBoardPage from './features/opportunities/OpportunityBoardPage'
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
  DEAL_DETAIL_PATH,
  EXPERT_PAYOUTS_PATH,
  NAV_ITEMS,
  PAYMENT_DETAIL_PATH,
  homePathFor,
  mayReach,
} from './features/shell/navigation'
import AuthProvider from './lib/auth'
import { useAuth, useMe } from './lib/authContext'
import JobRunsPage from './features/jobs/JobRunsPage'
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
  '/admin/jobs': <JobRunsPage />,
  // The diary. Its own screen rather than a panel on the board: a meeting booked from a deal card
  // was invisible the moment the card scrolled away, and EvalOS kept no record of it at all until
  // `V47__meeting.sql`.
  '/meetings': <MeetingsPage />,
  // **The last screen over a GHL pipeline.** Three funnel screens sat above this one until
  // 2026-09-16; they drew an aggregate over a date window and asked "how is the funnel
  // converting", and all three are gone (`/marketing/google-ads` on 2026-09-14 because its
  // pipeline no longer existed, `/marketing/email` and `/sales/pipeline` on 2026-09-16 because
  // they were GM-only and their audience was Marketing — see `navigation.ts`).
  //
  // This one is not a funnel and never was: it draws individual deals with the contact on them
  // and asks "what is on my desk". Same pipelines underneath, different question — which is why
  // it survives them, and why SALES and MARKETING can reach it when they could not reach those.
  '/opportunities/board': <OpportunityBoardPage />,
  // The two "open something" screens. They were a button and an inline form on the board until
  // 2026-09-17; the sidebar is the trigger now, so each is a screen of its own.
  '/opportunities/new': <NewDealPage />,
  '/marketing/leads/new': <NewLeadPage />,
  '/meetings/new': <NewMeetingPage />,
}

/**
 * One surface. **This file used to branch on a `/portal/` prefix and render a client portal of its
 * own**, outside `AuthProvider`, because there was a time when the staff SPA was the only thing
 * deployed and the client's draft-review screen had to live somewhere. It has not been that for a
 * while: the client portal is its own app (`client-expert/client`), slice 34b moved the draft
 * review into it, and since Unit 42 clients reach it from a button on the website and sign in.
 * Nothing mints a link to `/portal/client` any more, so the branch was a second, unmaintained
 * client portal that only a stale URL could reach. Deleted with the five files behind it.
 */
export default function App() {
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

        {/* Reached from an opportunity card, for the reason the case route is unlisted. */}
        <Route
          path={DEAL_DETAIL_PATH}
          element={
            <RoleRoute path={DEAL_DETAIL_PATH}>
              <DealPage />
            </RoleRoute>
          }
        />

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
