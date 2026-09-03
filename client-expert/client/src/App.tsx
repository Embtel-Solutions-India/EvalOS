import { Suspense, lazy } from 'react'
import { Navigate, Route, BrowserRouter, Routes } from 'react-router-dom'
import { Toaster } from '@shared/components/ui/sonner'
import { AppLoadingScreen } from '@shared/components/common/AppLoadingScreen'
import { ErrorBoundary } from '@shared/components/common/ErrorBoundary'
import { AuthProvider } from '@/context/AuthContext'
import { AuthLayout } from '@/layouts/AuthLayout'
import { IntakeLayout } from '@/layouts/IntakeLayout'
import { PortalLayout } from '@/layouts/PortalLayout'
import { AuthenticatedRoute, IntakeContinueRoute, IntakeStartRoute, PublicRoute } from '@/routes/guards'

const Login = lazy(() => import('@/pages/auth/Login'))
const ForgotPassword = lazy(() => import('@/pages/auth/ForgotPassword'))
const VerifyEmail = lazy(() => import('@/pages/auth/VerifyEmail'))

const Welcome = lazy(() => import('@/pages/intake/Welcome'))
const ChooseService = lazy(() => import('@/pages/intake/ChooseService'))
const ChoosePurpose = lazy(() => import('@/pages/intake/ChoosePurpose'))
const AboutYou = lazy(() => import('@/pages/intake/AboutYou'))
const Questionnaire = lazy(() => import('@/pages/intake/Questionnaire'))
const IntakeDocuments = lazy(() => import('@/pages/intake/IntakeDocuments'))
const IntakeReview = lazy(() => import('@/pages/intake/IntakeReview'))

const Dashboard = lazy(() => import('@/pages/dashboard/Dashboard'))
const Analytics = lazy(() => import('@/pages/analytics/Analytics'))
const Requests = lazy(() => import('@/pages/requests/Requests'))
const RequestDetail = lazy(() => import('@/pages/requests/RequestDetail'))
const Documents = lazy(() => import('@/pages/documents/Documents'))
const Payments = lazy(() => import('@/pages/payments/Payments'))
const Invoices = lazy(() => import('@/pages/invoices/Invoices'))
const Reports = lazy(() => import('@/pages/reports/Reports'))
const ReportDetail = lazy(() => import('@/pages/reports/ReportDetail'))
const Messages = lazy(() => import('@/pages/messages/Messages'))
const Tickets = lazy(() => import('@/pages/tickets/Tickets'))
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

      {/* The guided client-acquisition flow: Welcome → Choose Service →
          Purpose (conditional) → About You (creates the account) are public;
          Questionnaire → Documents → Review require the account just
          created. See routes/guards.tsx for the split. */}
      <Route element={<IntakeLayout />}>
        <Route element={<IntakeStartRoute />}>
          <Route path="/start" element={<Welcome />} />
          <Route path="/start/service" element={<ChooseService />} />
          <Route path="/start/purpose" element={<ChoosePurpose />} />
          <Route path="/start/about-you" element={<AboutYou />} />
        </Route>
        <Route element={<IntakeContinueRoute />}>
          <Route path="/start/questions" element={<Questionnaire />} />
          <Route path="/start/documents" element={<IntakeDocuments />} />
          <Route path="/start/review" element={<IntakeReview />} />
        </Route>
      </Route>

      {/* The one route wired to EvalOS (Unit 34c). It carries a scoped portal token out of the
          URL fragment and is deliberately OUTSIDE the account shell above: the credential names
          one case, not an account, and mounting it behind AuthenticatedRoute would answer Unit
          34's open decision D1 by accident. No layout, no nav — see pages/documents/Documents. */}
      <Route path="/documents" element={<Documents />} />

      <Route element={<AuthenticatedRoute />}>
        <Route element={<PortalLayout />}>
          <Route path="/dashboard" element={<Dashboard />} />
          <Route path="/analytics" element={<Analytics />} />
          <Route path="/requests" element={<Requests />} />
          <Route path="/requests/:id" element={<RequestDetail />} />
          <Route path="/payments" element={<Payments />} />
          <Route path="/invoices" element={<Invoices />} />
          <Route path="/reports" element={<Reports />} />
          <Route path="/reports/:id" element={<ReportDetail />} />
          <Route path="/messages" element={<Messages />} />
          <Route path="/tickets" element={<Tickets />} />
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
