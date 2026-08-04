import { Navigate, Route, Routes, useLocation } from 'react-router-dom'
import Forbidden from './components/Forbidden'
import BoardView from './features/board/BoardView'
import ChecklistBoard from './features/checklist/ChecklistBoard'
import PortalRoot from './features/client-portal/PortalRoot'
import ExpertRoster from './features/experts/ExpertRoster'
import LoginPage from './features/auth/LoginPage'
import RoleDashboard from './features/dashboards/RoleDashboard'
import AppShell from './features/shell/AppShell'
import PlaceholderPage from './features/shell/PlaceholderPage'
import CaseDetailPage from './features/case/CaseDetail'
import { CASE_DETAIL_PATH, NAV_ITEMS, mayReach } from './features/shell/navigation'
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
  '/checklists': <ChecklistBoard />,
  '/experts': <ExpertRoster />,
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
      <Route path="/login" element={<Navigate to="/dashboard" replace />} />

      <Route element={<AppShell />}>
        <Route index element={<Navigate to="/dashboard" replace />} />
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
