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

// The first four intake steps (Welcome, Choose Service, Purpose, About You)
// are public — a visitor builds their request before creating an account,
// per the "never lose progress" requirement. Anyone who already has an
// account (via /register or a prior request's About You step) gets
// redirected away from Welcome/About You — they don't need the marketing
// welcome screen or to recreate an account they already have.
//
// Choose Service and Purpose are exempt from that redirect: an existing
// client uses the sidebar's "New Request" button to reach exactly this
// route to start an additional request, and they should be able to pick a
// service/purpose without being bounced to the dashboard (ChooseService/
// ChoosePurpose route a returning client past About You automatically).
//
// About You is also exempt, for a different reason: submitting its form is
// what flips isAuthenticated to true (via registerUser) in the first
// place, while this guard is still watching that same route. Without the
// exemption, that state flip re-renders this guard mid-submit and its own
// <Navigate to="/dashboard"> races the page's own navigate('/verify-email')
// call a few lines later — same class of race as /start/review's exemption
// in IntakeContinueRoute below. No code path ever routes an authenticated
// visitor to About You in normal use (ChooseService/ChoosePurpose skip it),
// so exempting it only matters for this one transitional moment.
const EXEMPT_FROM_LOGGED_IN_REDIRECT = new Set(['/start/service', '/start/purpose', '/start/about-you'])

export function IntakeStartRoute() {
  const { isAuthenticated } = useAuth()
  const location = useLocation()
  if (isAuthenticated && !EXEMPT_FROM_LOGGED_IN_REDIRECT.has(location.pathname)) {
    return <Navigate to="/dashboard" replace />
  }
  return <Outlet />
}

// The remaining intake steps (Questionnaire, Documents, Review) require an
// account — About You ends with account creation, so reaching these means
// something interrupted that. Send them back to finish it.
//
// Unlike IntakeStartRoute, this guard does NOT redirect away once the
// profile is "completed" — profileCompleted is a one-time flag on the
// account, not a per-request one, so a returning client submitting a
// second (or later) request via "New Request" has profileCompleted=true
// for this entire flow, not just on /start/review. Each page already
// guards itself against a missing/stale draft (redirecting to
// /start/service when there's no active serviceId), which is the actual
// correct signal for "does this visitor belong here" — profileCompleted
// isn't.
export function IntakeContinueRoute() {
  const { isAuthenticated } = useAuth()
  if (!isAuthenticated) return <Navigate to="/start/about-you" replace />
  return <Outlet />
}
