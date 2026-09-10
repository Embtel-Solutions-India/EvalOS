import { Navigate, Outlet, useLocation } from 'react-router-dom'
import { useAuth } from '@/hooks/useAuth'

// Guards assume AuthProvider has already finished initializing — App.tsx
// blocks rendering of the router until that happens, so these never need
// to special-case a "loading" auth state.

export function PublicRoute() {
  const { isAuthenticated, user } = useAuth()
  if (isAuthenticated && user) {
    return <Navigate to="/dashboard" replace />
  }
  return <Outlet />
}

export function AuthenticatedRoute() {
  const { isAuthenticated } = useAuth()
  const location = useLocation()
  if (!isAuthenticated) {
    return <Navigate to="/login" state={{ from: location }} replace />
  }
  return <Outlet />
}

// IntakeStartRoute and IntakeContinueRoute lived here and guarded the seven-step intake funnel.
// **Removed 2026-09-10 with the funnel itself (D2)** — App.tsx registers no /start route, so both
// guards had no caller. Their commented reasoning went with them rather than being left to
// describe a flow that no longer runs; git history holds it if the decision reverses.
