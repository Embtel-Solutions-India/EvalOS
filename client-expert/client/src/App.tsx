import { Suspense, lazy } from 'react'
import { Navigate, Route, BrowserRouter, Routes } from 'react-router-dom'
import { Toaster } from '@shared/components/ui/sonner'
import { AppLoadingScreen } from '@shared/components/common/AppLoadingScreen'
import { ErrorBoundary } from '@shared/components/common/ErrorBoundary'
import { AuthProvider } from '@/context/AuthContext'
import { AuthLayout } from '@/layouts/AuthLayout'
import { PortalLayout } from '@/layouts/PortalLayout'
import { AuthenticatedRoute, PublicRoute } from '@/routes/guards'

const Login = lazy(() => import('@/pages/auth/Login'))
const ForgotPassword = lazy(() => import('@/pages/auth/ForgotPassword'))
const VerifyEmail = lazy(() => import('@/pages/auth/VerifyEmail'))

/*
 * 2026-09-10 — what left this router, and the two different reasons.
 *
 * **Deleted outright**, because the requirement change of the same day settled that nothing will
 * ever want them: /payments and /invoices (a client pays through a GHL invoice link the
 * salesperson generates — the portal is never a payment surface), /messages and /tickets
 * (invariant 14, no outbound channel), and /analytics (a client-facing analytics page that no
 * flow asks for). Their pages and services are gone from disk; git history holds them.
 *
 * **Unregistered but KEPT on disk** — the seven-step intake funnel at /start*. It is unreachable
 * today because it minted its own case-shaped reference outside Handoff A, which invariant 8
 * forbids. It is not deleted because the new client-portal flow puts a questionnaire back, this
 * time feeding a GHL *opportunity* rather than creating a case — so the screens are wanted and
 * the wiring underneath them is not. Re-registering is one line per route once that unit exists.
 *
 * Until then: do NOT wire /start* to EvalOS as it stands. The invariant is the reason.
 */

const Dashboard = lazy(() => import('@/pages/dashboard/Dashboard'))
const Requests = lazy(() => import('@/pages/requests/Requests'))
const RequestDetail = lazy(() => import('@/pages/requests/RequestDetail'))
const Documents = lazy(() => import('@/pages/documents/Documents'))
const Reports = lazy(() => import('@/pages/reports/Reports'))
const ReportDetail = lazy(() => import('@/pages/reports/ReportDetail'))
const Profile = lazy(() => import('@/pages/profile/Profile'))
const Settings = lazy(() => import('@/pages/settings/Settings'))
const NotFound = lazy(() => import('@shared/pages/NotFound'))

function AppRoutes() {
  return (
    <Routes>
      <Route path="/" element={<Navigate to="/login" replace />} />

      <Route element={<AuthLayout />}>
        <Route element={<PublicRoute />}>
          <Route path="/login" element={<Login />} />
          <Route path="/forgot-password" element={<ForgotPassword />} />
        </Route>
        <Route path="/verify-email" element={<VerifyEmail />} />
      </Route>

      {/* The one route wired to EvalOS (Unit 34c). It carries a scoped portal token out of the
          URL fragment and is deliberately OUTSIDE the account shell above: the credential names
          one case, not an account, and mounting it behind AuthenticatedRoute would answer Unit
          34's open decision D1 by accident. No layout, no nav — see pages/documents/Documents. */}
      <Route path="/documents" element={<Documents />} />

      <Route element={<AuthenticatedRoute />}>
        <Route element={<PortalLayout />}>
          <Route path="/dashboard" element={<Dashboard />} />
          <Route path="/requests" element={<Requests />} />
          <Route path="/requests/:id" element={<RequestDetail />} />
          <Route path="/reports" element={<Reports />} />
          <Route path="/reports/:id" element={<ReportDetail />} />
          <Route path="/profile" element={<Profile />} />
          <Route path="/settings" element={<Settings />} />
        </Route>
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
    <AuthProvider>
      <AppShell />
      <Toaster />
    </AuthProvider>
  )
}
