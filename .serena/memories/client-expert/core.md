# client-expert/ — the external portal frontends

Third *folder* in the monorepo (added 2026-09-03), holding **two applications**. Vite 8 /
React 19 / TS 6 / Tailwind 3.

**Split into two apps on 2026-09-03** — they arrived as one. `client/` (port **5174**) and
`expert/` (port **5175**) each have their own `vite.config.ts`, `index.html`, `tsconfig`,
`tailwind.config.js`, `.env` and `dist`, so **each deploys to its own subdomain**.
`application-local.yml` allows both origins by default.

**One dependency set, at `client-expert/`.** One `package.json`, one `node_modules`, one
lockfile — the apps carry configuration, never dependencies, and there is no workspace
indirection. Scripts `cd` into the app they build (`dev:client`, `dev:expert`,
`build:client`, `build:expert`, `build` = both), which is what makes Tailwind and PostCSS
resolve their per-app config.

**`shared/src` is what both import, as `@shared/*`; `@/` is the app you are in.** Neither app
imports the other — that is the property that keeps them deployable apart, and it is the
review rule. Anything both need moves into `shared/`.

**Mostly mock-backed. Four modules are real (Unit 34 slices 34a, 34c and 34e, 2026-09-03):**
`services/apiClient.ts` — the portal HTTP client, `X-Portal-Token` out of the URL fragment, held
in a module variable and **never persisted**, `withCredentials` deleted, no default
`Content-Type` (so a `FormData` body keeps the browser's multipart boundary), `ApiResponse`
unwrapped in one place — and `services/documentService.ts`, against the real S3-backed endpoints.
`lib/portal.ts` holds the wire types and the pure display rules; `lib/portal.test.ts` covers them.
`expert/src/services/expertPortalService.ts` + `expert/src/lib/expertCase.ts` are the expert's
half (34e), against Unit 15's six routes. Every other service module is still a `localStorage` fake
behind `mockDelay`.

**`apiClient`'s base is `/api/portal` and each service names its own half** — `/client/…` or
`/expert/…`. The token decides which audience it is admitted to, so the path is addressing, not
authorization.

**D1, D5, D6 and D8 were DECIDED 2026-09-04, all as recommended** — so the remaining slices are
no longer gated, they are queued behind one backend unit:

- **D1: a portal credential names a PARTY.** `ghl_contact_id` for `CLIENT`, `expert_id` for
  `EXPERT`, `case_id` nullable — so "my cases" is answerable and the delivered list screens have a
  backend. A **case-scoped link stays legal**; the two wired screens keep working unchanged. Party
  tokens live **7 days**, case tokens 30. **No accounts** — refused, not deferred.
- **D5: one vocabulary, EvalOS's, projected.** The server sends the step; this app renders the
  label and still holds no lifecycle enum.
- **D6: an expert reads their own payout rows** — never `payment_detail`.
- **D8: analytics OFF.** `VITE_GTM_ID`, `VITE_GA4_ID` and `utils/analytics.ts` are **deleted**, not
  stubbed: a no-op module is one somebody re-points at a provider.

**The backend half is `35-party-scoped-portal-access.md` and it is not built yet.** Do not wire a
list screen before it lands, and do not wire anything else ad hoc.

## Layout

- `shared/src` — `components/ui` (shadcn primitives), `components/common` (EmptyState,
  FileDropzone, FormField, PageHeader, Logo, ErrorBoundary, …), `services/apiClient.ts`,
  `lib/portal.ts` (+ its test), `styles/globals.css`, `constants/{storage,upload}`,
  `schemas/auth`, `utils/{cn,formatters,storage}`, `hooks/useMediaQuery`, `pages/NotFound`,
  `assets/logo.png`.
- `client/src` — `components/{intake,layout,common}`, `layouts/{Auth,Intake,Portal}`,
  `pages/*` (dashboard, requests, documents, reports, messages, tickets, intake, …),
  `routes/guards.tsx`, `context/AuthContext`, the ten mock `services/*`, `schemas/`,
  `types/`, `constants/`, `mock/`, `utils/analytics.ts`, `lib/questionnaire.ts`.
- `expert/src` — `components/{expert,layout}`, `layouts/{ExpertAuth,ExpertPortal}`,
  `pages/expert/*`, `routes/expertGuards.tsx`, `context/ExpertAuthContext`,
  `services/{expertAuthService,expertCaseService}`, `types/expert.ts`,
  `constants/expertNavigation.ts`, `mock/expertMockData.ts`.

Aliases `@` → that app's `src`, `@shared` → `shared/src`. Build is `tsc -b && vite build`
per app; lint is **oxlint**, not ESLint. **The expert app keeps its `/expert/*` URL paths**
even alone on a subdomain — the links inside its screens are absolute; changing that is one
edit in `expert/src/App.tsx`.

- **`services/*` (in each app, plus `shared/src/services/apiClient.ts`) is the entire
  mock/real boundary.** Every future HTTP call lives there and
  nowhere else; pages depend on function signatures only. This is the one property that
  makes the wiring tractable — **a page that reaches past it ends that, and is a review
  reject.**
- `shared/src/components/ui/*` — shadcn-style generated primitives. **Protected**, same rule as
  `frontend/src/components/ui/*`.
- Two independent auth contexts, two route-guard files, two layouts. Client and expert
  share no session state, and that separation is deliberate — keep it.
- **Vitest as of 34a** (`npm run test` → `vitest run` from `client-expert/`, over all three
  folders; `vitest.config.ts` at that level carries the `@shared` alias, because there is no
  single `vite.config.ts` any more). Pure rules modules only, same discipline as `frontend/`:
  no jsdom, no Testing Library, so a `.tsx` change catches nothing — verify those by running it.

## Contract conflicts — read before writing any wiring code

| | the portal apps as delivered | EvalOS as built |
|---|---|---|
| Credential | email + password in `localStorage`, both audiences | `portal_access` token, `X-Portal-Token` header, never persisted |
| Scope | a **list** of cases per user | **one case per token** (`PortalPrincipal`) |
| Lifecycle | 4 local enums: `RequestStatus`, `SigningStatus`, `DocumentStatus`, `IntakeDocumentStatus` | Unit 31's 12 stages, `CaseTransitions` |
| Identity | mints `IE-{year}-{6 digits}` | `case_code`, `ghl_contact_id` |
| CORS | `withCredentials: true` | `allowCredentials(false)`, `GET/POST/OPTIONS`, `Content-Type` + `X-Portal-Token` only |

**Three invariants are in its path** (none breached — none of those screens calls anything):

- the intake funnel (`pages/intake/*`, `services/intakeService.submitRequest`) creates a
  case-shaped record → **invariant 8**, only `opportunity.won` creates a case
- `pages/payments`, `pages/invoices` → **invariant 2**, invoicing is GHL's.
  **⚠ `pages/invoices` came back on 2026-09-11 as Unit 41, and it is a different screen.** The
  deleted one let a client pay and manage billing — EvalOS *doing* invoicing. The new one is
  **read-only**: it shows what GHL's invoice API returns, stores nothing, and offers no payment
  action (paying goes through GHL's own link). **Invariant 2 lost most of its clauses to the GHL
  programme but kept this one** — *invoicing is GHL's, full stop* — and reading a figure has
  never breached it, which is what Units 24/26/27 already established.
- `pages/messages`, `pages/tickets` → **invariant 14**, EvalOS has no outbound channel

**That gap is closed as of 2026-09-11.** `pages/draft/DraftReview.tsx` at `/draft` is 34b:
read, approve, request revisions. (`pages/reports` was deleted the same day — see the box
above.)

> ## ⚠ 2026-09-11 (34d) — THE CLIENT APP HAS NO MOCK AND NO ACCOUNT
>
> **Five pages, three services, all EvalOS-backed:** `/dashboard`, `/requests`, `/documents`,
> `/invoices`, `/draft` (+ `/draft/:caseId`). `documentService`, `draftService`,
> `invoiceService`. **Anything below describing a mock screen is history.**
>
> **The account shell is DELETED** — `/login`, `/forgot-password`, `/verify-email`,
> `AuthProvider`, `AuthenticatedRoute`, `PublicRoute`, `AuthLayout`, `authService`, `useAuth`,
> `/profile`, `/settings`. It implemented an email-and-password account, **the alternative D1
> refused rather than deferred**: a password store needs a mail channel invariant 14 says does
> not exist. **Do not reintroduce a login.**
>
> **One credential for the whole app.** `usePortalToken` (in `shared/src/hooks`) lifts the
> scoped token out of the URL fragment on first render — a lazy `useState` initializer, never
> an effect, because the token must be set before the first query fires. Every screen calls it.
> A client opening any one link can then walk the rest.
>
> **No Logout and no notification bell.** There is no session (closing the tab is the whole of
> it, and clearing the token would strand the client), and no channel to notify through — the
> portal *is* the notification, which is what D4 settled.
>
> **Deleted with it: the intake funnel** (parked the day before; every step imported `useAuth`,
> so it stopped compiling — code that cannot compile is not parked) **and `/reports`** (parking
> it meant four mock modules kept alive for an unregistered screen whose backend route does not
> exist; `SIGNED_LETTER` is filtered out of client documents deliberately because delivery is an
> untaken decision — it is ~40 lines when that lands).

## The wired screens: `/documents` (34c), `/invoices` (41), `/draft` (34b), and the two lists (34d)

### `/draft` — the client answers their draft (34b, BUILT 2026-09-11)

`client/src/pages/draft/DraftReview.tsx`, `services/draftService.ts`. Outside
`AuthenticatedRoute`, no nav — a scoped portal link, same as its neighbours.

- **⚠ It needed a backend change, and the reason generalises.** Unit 35 gave party scoping to
  the READS and not to the WRITES: `GET /cases/{caseId}` existed, `/approve` and
  `/request-revisions` still resolved the case from the token, so a two-case client could read
  either draft and approve neither. Closed with `POST /cases/{caseId}/approve` and
  `.../request-revisions`. **When a credential gains a scope, check both halves.**
- **The tokenless routes still refuse to guess**, deliberately: approving is Handoff B and has
  no undo that reaches the client.
- **`APPROVAL_STATUS`** maps the three `ClientApprovalStatus` values, with a test that fails on a
  fourth — same rule as `CHECKLIST_STATUS`. `awaitingAnswer` is the server's flag; this app
  infers no case state.
- **`draftLink` is a pasted link, not an S3 key.** Nothing to presign; rendered as an external
  link, with an honest message when absent.

### `/invoices` — the client's billing (Unit 41, BUILT 2026-09-11)

`client/src/pages/invoices/Invoices.tsx`, `services/invoiceService.ts`. **Outside
`AuthenticatedRoute` and absent from nav**, same as `/documents` and for the same reason: a
scoped portal token, not the mock account session.

- **Needs a PARTY-scoped token** (Unit 35), because an invoice belongs to the client, who may
  have several cases. A case-scoped link gets 403 — and the page explains that specific cause in
  the client's own words rather than showing the generic failure, since a different link fixes it.
- **`invoices.readonly` is NOT granted.** Until it is, every call answers 502. That is by design
  and the server names the missing scope in the message.
- **Every status word is GHL's** (`paid`, `unpaid`, `partially_paid`…). This screen computes
  nothing about money — that would be a second opinion about it.
- **A null amount renders as an em dash, never 0.00.** "Not recorded" and "nothing owed" are
  different facts.
- **⚠ A paid invoice is not revenue** (invariant 5 — *paid AND delivered*). Nothing here may be
  summed into a revenue figure.

### `/documents` (34c)

`client/src/pages/documents/Documents.tsx` — checklist (outstanding first), one upload per item with a real
progress bar, per-click presigned download. **Routed OUTSIDE `AuthenticatedRoute` and removed
from `SECONDARY_NAV`**, deliberately: its credential is a scoped portal link naming one case, not
the mock account session, and putting it behind the account guard would answer D1 sideways. It
reads its token from `window.location.hash` in a lazy `useState` initializer — not an effect,
because the token must be set before the query fires.

**It holds no lifecycle enum.** `CHECKLIST_STATUS` in `shared/src/lib/portal.ts` maps EvalOS's five
`ChecklistItemStatus` values to a label and a badge variant, and a test fails if the server can
send a sixth. That is presentation of a server-owned vocabulary — never a derivation, never an
ordering, never a status this app computes. **This is D5's rule in practice; hold the line here.**

Deleted with it because they were the mock surface it replaced: `MOCK_DOCUMENTS`,
`ClientDocument`, `DocumentStatus`, `DocumentStatusBadge`. The intake wizard's fake progress bar
moved to `client/src/mock/simulateUpload.ts` — a mock beside real calls in one module is how somebody ships
the mock.

## The expert's wired screen: `/case` (34e, 2026-09-03)

`expert/src/pages/portal/ExpertCasePortal.tsx`, at **`/case#<token>` and OUTSIDE
`ExpertAuthenticatedRoute`** — same placement and same reason as the client's `/documents`: one case
per token is not an account session, and mounting it behind the shell answers D1 sideways. One
column: goal → the letter → the supplied evidence → the three answers, with the sign panel between.

Against Unit 15's six routes: `GET /api/portal/expert/case` (also stamps the read receipt),
`POST /accept`, `POST /request-evidence`, `POST /decline`, `GET /letter`, `POST /signed-letter`
(multipart, **PDF by content sniffing** server-side, attestation required and checked against the
server's own wording). Staff mint the link with
`POST /api/cases/{id}/portal-link?audience=EXPERT`.

- **The attestation is part of the upload, never a step.** The dropzone is disabled until the tick,
  the wording comes from the server on the view and goes back unedited, and the API refuses an
  upload without it whatever the UI does — it is the evidence. If EvalOS could not name the expert,
  the panel closes rather than sending a nameless attestation into a 400.
- **`expert/src/lib/expertCase.ts` holds no lifecycle.** `stateOf` reads `signed` / `onHold` /
  `awaitingAnswer` — booleans the server states so the browser need not infer them from a stage —
  and the `SIGN_STATUS` / `SIGN_SLA` tables have a test that fails if EvalOS gains a value.
  `serviceType` and `visaCategory` are prettified by a generic `humanize`, deliberately **not**
  tabled: those vocabularies grow, and a table here would show a raw constant the day one does.
- **Expert links are minted at a different origin** — `evalos.portal.expert-base-url`, path
  `/case#<token>`. The two portals are two deployments; a link to the wrong host reads to its
  holder exactly like a revoked token. Blank falls back to the client base.
- **Still mock**: the assignments list (**D1** — a token names one case), login, payments (**D6**)
  and profile. Those screens are routed and untouched; do not wire them ad hoc.
- **`window.open` must be called synchronously in the click handler, then navigated** — both this
  screen's "Open the letter" and the client's document download opened the tab *after* an `await`,
  which Safari and Firefox block: the URL was minted and audited and nothing opened, with no error
  to show. Open the blank tab, sever `opener`, then set `location.href` when the URL arrives (and
  no `noopener` in the features string — it makes `window.open` return null, so there is no handle
  to navigate). Fixed in both apps by review, 2026-09-03; `frontend/src/features/case/DocumentList.tsx`
  had documented the pattern all along.

## Design system — its own, and correctly so

`shared/src/styles/globals.css`: HSL triples behind shadcn names, DM Sans + DM Serif Display, IE
navy `#003152` + crimson `#c8102e`, `.dark` class with a full palette, `--radius: .625rem`.
Shares **no token** with `frontend/src/styles/tokens.css` and should not — a client
reviewing one letter is not a coordinator on a nine-hour shift. Two rules still cross the
boundary because they are semantic: **RAG is status-only, never decorative**, and **tabular
figures on money, dates, deadlines and counts**.

**The palette is hard-coded to one brand and EvalOS is multi-brand** — XpertsPortal has no
home in it. Unit 34 D7: brand name + palette travel in the portal payload.

## Recorded so it is not re-derived

- Port 5174 matching `EVALOS_PORTAL_ORIGINS`' local default is **not a coincidence** —
  Unit 30 §1 specified "one external frontend deployment, two portals, one backend" before
  this app existed.
- `VITE_GTM_ID` / `VITE_GA4_ID` are declared in `.env.example`. **A third-party tag on a
  page showing identity documents is undecided** (Unit 34 D8, recommended off).
- `client-expert/README.md` describes the apps on their own terms and now names the EvalOS backend it
  belongs to; its "connecting a real backend" section is superseded by Unit 34's slices.
- `client/dist` and `expert/dist` are gitignored, along with `.tmp` (per-app `tsc -b`
  state). Not tracked.
- **`react-hook-form` in `node_modules` was a corrupted extraction** (its `dist/index.d.ts`
  re-exported from a `../src` that was not there, so every `useForm` import failed to
  typecheck). Deleting that one package and re-running `npm install` fixed it, same version.
  Suspect the same shape before blaming a config if types vanish from one package only.
