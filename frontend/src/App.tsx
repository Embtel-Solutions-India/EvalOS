import { Navigate, Route, Routes } from 'react-router-dom'
import Forbidden from './components/Forbidden'
import LoginPage from './features/auth/LoginPage'
import RoleDashboard from './features/dashboards/RoleDashboard'
import AppShell from './features/shell/AppShell'
import PlaceholderPage from './features/shell/PlaceholderPage'
import { NAV_ITEMS, mayReach } from './features/shell/navigation'
import { useAuth, useMe } from './lib/auth'
import NotFound from './pages/NotFound'

/**
 * Three states, one router.
 *
 * Unauthenticated renders only the login page, so no shell code runs without a session
 * and `useMe()` below the shell can throw rather than return null. Every in-shell route
 * is wrapped in {@link RoleRoute}, which checks the same `navigation.ts` table the nav
 * filters — a deep link outside the role's allow-list renders the 403 view rather than
 * redirecting, so the user can see which URL was refused.
 */
export default function App() {
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

        {NAV_ITEMS.filter((item) => item.path !== '/dashboard').map((item) => (
          <Route
            key={item.path}
            path={item.path}
            element={
              <RoleRoute path={item.path}>
                <PlaceholderPage />
              </RoleRoute>
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
