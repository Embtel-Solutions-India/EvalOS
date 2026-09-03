import { Suspense, lazy } from 'react'
import { Navigate, Route, BrowserRouter, Routes } from 'react-router-dom'
import { Toaster } from '@shared/components/ui/sonner'
import { AppLoadingScreen } from '@shared/components/common/AppLoadingScreen'
import { ErrorBoundary } from '@shared/components/common/ErrorBoundary'
import { ExpertAuthProvider } from '@/context/ExpertAuthContext'
import { ExpertAuthLayout } from '@/layouts/ExpertAuthLayout'
import { ExpertPortalLayout } from '@/layouts/ExpertPortalLayout'
import { ExpertAuthenticatedRoute, ExpertPublicRoute } from '@/routes/expertGuards'

const ExpertLogin = lazy(() => import('@/pages/expert/ExpertLogin'))
const ExpertDashboard = lazy(() => import('@/pages/expert/ExpertDashboard'))
const ExpertCaseDetail = lazy(() => import('@/pages/expert/ExpertCaseDetail'))
const ExpertPayments = lazy(() => import('@/pages/expert/ExpertPayments'))
const ExpertProfile = lazy(() => import('@/pages/expert/ExpertProfile'))
const ExpertCasePortal = lazy(() => import('@/pages/portal/ExpertCasePortal'))
const NotFound = lazy(() => import('@shared/pages/NotFound'))

/**
 * The expert portal, its own app on its own origin.
 *
 * The `/expert` path prefix is kept even though this app is alone on its
 * subdomain: every link inside these screens is absolute, and moving the
 * deployment should not also move the URLs. Drop the prefix here, in one
 * place, if somebody later wants the subdomain root to be the dashboard.
 */
function AppRoutes() {
  return (
    <Routes>
      <Route path="/" element={<Navigate to="/expert" replace />} />

      {/* The one route wired to EvalOS (Unit 34e). It carries a scoped portal token out of the
          URL fragment and is deliberately OUTSIDE the account shell below: the credential names
          one case, not an account, and mounting it behind ExpertAuthenticatedRoute would answer
          Unit 34's open decision D1 by accident. No layout, no nav — the expert opens the link
          they were sent, answers, signs, and closes the tab. */}
      <Route path="/case" element={<ExpertCasePortal />} />

      <Route element={<ExpertAuthLayout />}>
        <Route element={<ExpertPublicRoute />}>
          <Route path="/expert/login" element={<ExpertLogin />} />
        </Route>
      </Route>
      <Route element={<ExpertAuthenticatedRoute />}>
        <Route element={<ExpertPortalLayout />}>
          <Route path="/expert" element={<ExpertDashboard />} />
          <Route path="/expert/cases/:id" element={<ExpertCaseDetail />} />
          <Route path="/expert/payments" element={<ExpertPayments />} />
          <Route path="/expert/profile" element={<ExpertProfile />} />
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
    <ExpertAuthProvider>
      <AppShell />
      <Toaster />
    </ExpertAuthProvider>
  )
}
