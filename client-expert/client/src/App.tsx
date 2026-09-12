import { Suspense, lazy } from 'react'
import { Navigate, Route, BrowserRouter, Routes } from 'react-router-dom'
import { Toaster } from '@shared/components/ui/sonner'
import { AppLoadingScreen } from '@shared/components/common/AppLoadingScreen'
import { ErrorBoundary } from '@shared/components/common/ErrorBoundary'
import { PortalLayout } from '@/layouts/PortalLayout'

/*
 * 2026-09-10 — what left this router, and the two different reasons.
 *
 * **Deleted outright**, because the requirement change of the same day settled that nothing will
 * ever want them: /payments (a client pays through a GHL invoice link the salesperson generates
 * — the portal is never a payment surface), /messages and /tickets (invariant 14, no outbound
 * channel), and /analytics (a client-facing analytics page that no flow asks for). Their pages
 * and services are gone from disk; git history holds them. `/invoices` came back on 2026-09-11
 * as Unit 41 and is a different screen: read-only, no payment action.
 *
 * **Parked on 2026-09-10, then DELETED on 2026-09-11** — the seven-step intake funnel at
 * /start*. It was unregistered-but-kept because the coming client-portal questionnaire wants
 * those screens, feeding a GHL *opportunity* rather than minting a case outside Handoff A
 * (invariant 8, which is why it was unregistered).
 *
 * **Parking stopped being available when the account shell went.** Every step of that funnel
 * imported `useAuth` and `authService`, so with the shell deleted it no longer compiled — and
 * code that cannot compile is not parked, it is broken. Deleting it was the honest reading.
 * The questionnaire will be rebuilt against a portal token rather than an account anyway, so
 * what was being preserved was a shape, and git history preserves that just as well.
 *
 * ---
 *
 * 2026-09-11 (34d) — **the account shell is gone, and this app has one credential.**
 *
 * `/login`, `/forgot-password`, `/verify-email`, `AuthProvider`, `AuthenticatedRoute`,
 * `PublicRoute`, `AuthLayout`, `authService`, `useAuth`, `/profile` and `/settings` are all
 * DELETED. They implemented an email-and-password account, which is **the alternative D1
 * refused rather than deferred**: a password store needs a mail channel for verification and
 * resets, and invariant 14 says EvalOS has none. The expert app shed the same shell on
 * 2026-09-10; the client's was kept only because `/dashboard` and `/requests` were still mock
 * screens living inside it, and 34d is what moved them out.
 *
 * **The credential is a scoped portal link.** It arrives in the URL fragment, `usePortalToken`
 * lifts it into the API client on first render, and every screen below reads it from there — so
 * a client who opens one link can navigate the whole app without opening another. A fragment is
 * never sent to the server and never lands in an access log.
 *
 * **`/reports` is DELETED, and it was the one close call.** They were download screens for the
 * finished letter, and **EvalOS has no route that serves it** — `PortalCaseService` filters
 * `SIGNED_LETTER` out of the client's documents deliberately, because delivery is a decision
 * nobody has taken. Parking them was the first instinct, and the cost of parking turned out to
 * be four modules kept alive so an unregistered screen could compile (`types/index`,
 * `constants/evaluation`, `mock/mockData`, `services/reportService`) — all of them mock.
 *
 * Unlike the GM funnel screens, which are live and were left alone, nothing here was reachable
 * by anyone. Deleting unreachable mock code is not a scope cut. **When the delivery decision
 * lands, this screen is about forty lines against whatever route it produces**, and git history
 * holds the old one.
 *
 * ---
 *
 * 2026-09-12 (Unit 42) — **a door came back, deliberately, and it is not the account shell.**
 *
 * `/welcome`, `/signin` and `/set-password` are new. Read the 34d entry above before assuming
 * this reverses it: it does not. There is still exactly one credential — the scoped portal
 * token — and `authService.signIn` / `authService.setPassword` both mint the *same* token
 * `usePortalToken` already knows how to hold, through the same `setPortalToken`. What changed
 * is how someone gets that token into their browser: alongside a link EvalOS sends, they can now
 * also identify themselves by email and, if they have set one, a password. There is no second
 * store, no session cookie, and no account record this app reads — the server decides what an
 * email can do (`PASSWORD_SET` / `NO_PASSWORD` / `UNKNOWN`) and this app only asks.
 *
 * **Every `portal_access` link already sent still works, unchanged.** `/welcome` says so — a
 * front door offering only a password would tell a client holding a working link that it broke.
 *
 * **34d's mail-channel objection was real, and it was answered, not dropped.** It said EvalOS
 * has no mail channel (invariant 14) and a password store needs one for verification and resets.
 * That was true on 2026-09-11. Invariant 14 was amended the same day for exactly this: EvalOS now
 * has SMTP for authentication mail only, which is what `forgotPassword` sends through. The
 * amendment is that narrow — it licenses a reset link and a set-password link, nothing else. It
 * is not a general mail channel, and no other feature gets to point at this paragraph to send a
 * client something.
 */

const Welcome = lazy(() => import('@/pages/auth/Welcome'))
const SignIn = lazy(() => import('@/pages/auth/SignIn'))
const SetPassword = lazy(() => import('@/pages/auth/SetPassword'))
const Start = lazy(() => import('@/pages/auth/Start'))
const Dashboard = lazy(() => import('@/pages/dashboard/Dashboard'))
const Requests = lazy(() => import('@/pages/requests/Requests'))
const Documents = lazy(() => import('@/pages/documents/Documents'))
const Invoices = lazy(() => import('@/pages/invoices/Invoices'))
const Meetings = lazy(() => import('@/pages/meetings/Meetings'))
const DraftReview = lazy(() => import('@/pages/draft/DraftReview'))
const NotFound = lazy(() => import('@shared/pages/NotFound'))

function AppRoutes() {
  return (
    <Routes>
      {/* A link that names them still works; this is only where an empty visit lands. */}
      <Route path="/" element={<Navigate to="/welcome" replace />} />
      <Route path="/welcome" element={<Welcome />} />
      <Route path="/signin" element={<SignIn />} />
      <Route path="/set-password" element={<SetPassword />} />
      {/*
        `/start` was a 404 while two shipped screens linked to it — `/welcome`'s card and
        `SignIn`'s UNKNOWN branch. The route is a placeholder, not the funnel; Unit 43 replaces
        the file behind it.
      */}
      <Route path="/start" element={<Start />} />

      {/*
        Every screen shares one credential and one shell now, which is what 34d bought. Before
        it, the three real screens each stood alone outside a mock account shell because there
        was nothing to put them in; the shell is gone and the nav is over the real routes.
      */}
      <Route element={<PortalLayout />}>
        <Route path="/dashboard" element={<Dashboard />} />
        <Route path="/requests" element={<Requests />} />
        <Route path="/documents" element={<Documents />} />
        <Route path="/invoices" element={<Invoices />} />
        <Route path="/meetings" element={<Meetings />} />
        {/* Both, so the case list can link straight to one and the picker still has a home. */}
        <Route path="/draft" element={<DraftReview />} />
        <Route path="/draft/:caseId" element={<DraftReview />} />
      </Route>

      <Route path="*" element={<NotFound />} />
    </Routes>
  )
}

export default function App() {
  return (
    <ErrorBoundary>
      <BrowserRouter>
        <Suspense fallback={<AppLoadingScreen />}>
          <AppRoutes />
        </Suspense>
        <Toaster />
      </BrowserRouter>
    </ErrorBoundary>
  )
}
