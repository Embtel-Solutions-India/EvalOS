# EvalOS frontend audit — implementation lens

Audited 2026-09-13 against `development` @ `dee45c6`. Serena MCP was down; everything below
was read from disk with Glob/Grep/Read/Bash. Nothing here is asserted from a spec I did not
verify in code.

Headline: **there is far less mock debt than the brief assumed.** Exactly one mock file
survives in the portals (`expert/src/mock/expertMockData.ts`) and **nothing imports it**. Every
routed screen in all three apps calls the real backend. The real gaps are elsewhere: no
server-state library in the internal app, no responsive shell, no global search, a disjoint
design system between the internal app and the portals, `strict` off in every tsconfig, and a
large stale-dead-code tail in `shared/`.

---

## Screen inventory

### 1. `frontend/` — internal EvalOS app (Vite :5173, Tailwind v4, no React Query)

Routing is `frontend/src/App.tsx:42-183`. `SCREENS` maps path → component; every other
`NAV_ITEMS` path falls through to `PlaceholderPage`. Route guard is `RoleRoute`
(`App.tsx:180-183`) reading the same table the nav filters (`features/shell/navigation.ts`).

| Route | Component (`path`) | Backing | Loading | Empty | Error | Confirm | Notes |
|---|---|---|---|---|---|---|---|
| `/login` | `features/auth/LoginPage.tsx` | REAL `POST /auth/login` + `GET /me` | ✔ `sending` state | n/a | ✔ generic (`:22`) | n/a | Deliberately indistinguishable failure message |
| `/` | redirect via `homePathFor` | — | — | — | — | — | `App.tsx:131` |
| `/dashboard` | `dashboards/RoleDashboard.tsx` | REAL, `Record<Role, ReactNode>` | delegated | delegated | delegated | — | **Route is NOT wrapped in `RoleRoute`** (`App.tsx:132`) — see F14 |
| ↳ GM / BM | `dashboards/RevenueDashboard.tsx` | REAL `/metrics/revenue` | ✔ `CardState.loading` | ✔ | ✔ | n/a | One tile hard-coded `unavailable` blockedBy Unit 16 (`:104`) |
| ↳ PM | `dashboards/PmDashboard.tsx` | REAL `/metrics/pm` | ✔ | ✔ | ✔ | n/a | One `unavailable` tile (`:204`); recharts bar chart |
| ↳ Coordinator | `dashboards/CoordinatorDashboard.tsx` | REAL `/metrics/coordinator` | ✔ | ✔ | ✔ | n/a | One `unavailable` tile (`:94`) |
| ↳ Case Manager | `dashboards/CaseManagerDashboard.tsx` | REAL `/metrics/case-manager` | ✔ | ✔ | ✔ | ✔ `FlagToPmDialog` | |
| ↳ ENM | `dashboards/ExpertNetworkDashboard.tsx` | REAL `/metrics/expert-network` | ✔ | ✔ | ✔ | n/a | Two `unavailable` tiles (`:197-198`) |
| ↳ (shared tile) | `dashboards/PortalLinkLedger.tsx` | REAL `/metrics/portal-links` | ✔ | ✔ (`:56`) | ✔ | n/a | Renders `CLIENT` audience rows — dead after Unit 42 (F8). Raw `slate-*` classes |
| `/board`, `/my-cases` | `board/BoardView.tsx` | REAL `/cases/board` | ▲ text only (`:167-173`) | ✖ board-level | ✔ + retry (`:175-197`) | ✔ `QuickActionDialog` for field-collecting actions | No drag-drop, no search, client-side filters only, no success toast |
| `/inbox` | `queues/InboxPage.tsx` | REAL `/cases/board` | ✔ pulse (`:95`) | ✔ per-bucket (`:98,145-153`) | ✔ | ✖ | |
| `/drafts` | `queues/DraftQueuePage.tsx` | REAL `/cases/board` + `/cases/:id/timeline` | ✔ (`:166,423`) | ✔ (`:169-178,424`) | ✔ | ✔ return dialog (`:501-536`) | |
| `/my-drafts` | `queues/MyDraftsPage.tsx` | REAL `/cases/board` + case detail | ✔ (`:64`) | ✔ (`:67-77,187`) | ✔ | ✖ | |
| `/pm-notes` | `queues/PmNotesPage.tsx` | REAL `/cases/pm-notes` | ✔ (`:38`) | ✔ (`:63`) | ✔ (`:41`) | n/a read-only | |
| `/expert-assignment` | `queues/ExpertAssignmentPage.tsx` | REAL `/cases/board` + `/experts/availability-board` | ✔ (`:114`) | ✔ (`:213`) | ✔ | ✔ `QuickActionDialog` | |
| `/delivery` | `queues/DeliveryQueuePage.tsx` | REAL `/cases/board` | ✔ (`:57`) | ✔ (`:60-69`) | ✔ | ✔ deliver dialog (`:135-174`) | Best-in-app confirmation |
| `/checklists` | `checklist/ChecklistBoard.tsx` | REAL `/checklists/board` | ▲ text (`:90-93`) | ✔ (`:163-165`) | ✔ + retry (`:98-116`) | ✖ | |
| `/experts` | `experts/ExpertRoster.tsx` (+ `AvailabilityBoard`, `SheetUpload`, `ImportReport`, `ExpertProfile`) | REAL `/experts/roster`, `/experts/availability-board`, `POST /experts` | ✔ (`:108`) | ✔ (`:212-216`) | ✔ + retry (`:193-207`) | ✔ in `ExpertProfile` / `SheetUpload` | **The only paginated screen** (`:249-269`) |
| `/payouts` | `payouts/PayoutBatch.tsx` (+ `PaymentForm`) | REAL `/payouts/batch`, `POST /payouts/settle` | ✔ (`:98-100`) | ✔ (`:124-126`) | ✔ (`:104`) | ▲ form review, no modal | Only bulk-select surface in the app (`payoutRules.ts:93-108`) |
| `/payouts/experts/:expertId` | `payouts/ExpertPayouts.tsx` | REAL `/payouts`, `/payments` | ✔ (`:87-89`) | ✔ (`:108,191`) | ✔ (`:93`) | ✖ inline amount edit | |
| `/payouts/payments/:paymentId` | `payouts/PaymentDetail.tsx` | REAL `/payments/:id`, `POST .../confirm` | ✔ (`:67-70`) | ✖ | ✔ (`:75`) | ✖ — "expert confirmed receipt" is terminal and one click (`:155-166`) | |
| `/cases/:id` | `case/CaseDetail.tsx` + 11 panels | REAL (7 endpoints) | ▲ text (`:138-141`) | mixed per panel | ✔ (`:146`) | ✔ `QuickActionDialog` | Two-level fetch waterfall (F6) |
| `/marketing/google-ads` | `marketing/MarketingPipelinePage.tsx` (`funnel="ads"`) | REAL `/marketing/ads-pipeline` | ✔ `CardState` | ✔ window-aware (`:78`) | ✔ | n/a | Polls while `detail==="TOTALLING"` (`:103-107`) — correctly bounded |
| `/marketing/email` | same component, `funnel="email"` | REAL | ✔ | ✔ | ✔ | n/a | |
| `/sales/pipeline` | same component, `funnel="sales"` | REAL | ✔ | ✔ | ✔ | n/a | |
| `/opportunities/board` | `opportunities/OpportunityBoardPage.tsx` + `DealActions`, `DealNotes`, `NewLeadForm` | REAL `/opportunities/board`, `/sales/*`, `/marketing/leads` | ▲ blanks whole board on every mutation (F5) | ✔ (`:78-87`) | ✔ via `Card` | ✖ **close won/lost/abandoned is one unguarded click** (`:109-127`) | Raw `slate-*`; no edit affordance despite `updateDeal`/`valueLead` existing |
| `/admin/jobs` | `jobs/JobRunsPage.tsx` | REAL `/jobs/sweeps`, `/jobs/runs`, `POST /jobs/:t/run` | ✔ `CardState` | ▲ | ✔ + `warning` for stale (`:60-66`) | ✖ manual sweep trigger unguarded | Raw `slate-*` |
| `/brands` | `shell/PlaceholderPage.tsx` | **PLACEHOLDER** | n/a | n/a | n/a | n/a | Only remaining unbuilt nav entry |
| `*` | `pages/NotFound.tsx` | static | n/a | n/a | n/a | n/a | Hardcodes `/dashboard` back-link |
| (403) | `components/Forbidden.tsx` | static | n/a | n/a | n/a | n/a | Hardcodes `/dashboard` back-link |

Shell: `features/shell/AppShell.tsx` → `LeftNav` (fixed rail) + `TopBar` + `FiltersProvider`.
`TopBar` carries brand switcher, date filter, notification bell, sign-out, and a **disabled
search input** (`TopBar.tsx:50` `disabled`, placeholder "Search — not available yet").

### 2. `client-expert/client/` — Client Portal (Vite :5174, Tailwind v3, React Query)

Routing `client/src/App.tsx:95-129`. Authenticated routes sit under `PortalLayout`, which
redirects to `/signin` when no token (`layouts/PortalLayout.tsx:44-46`).

| Route | Component (`path`) | Backing | Loading | Empty | Error | Confirm | Notes |
|---|---|---|---|---|---|---|---|
| `/` | → `/welcome` | — | — | — | — | — | |
| `/welcome` | `pages/auth/Welcome.tsx` | static | n/a | n/a | n/a | n/a | Two cards: `/start`, `/signin` |
| `/signin` | `pages/auth/SignIn.tsx` | REAL `/auth/identify`, `/auth/sign-in`, `/auth/forgot-password` | ✔ `loading` on button | n/a | ✔ per-field + `authFailureMessage` | n/a | `Record<IdentifyState,…>` forces exhaustive copy (`:41-51`) — best auth code in the tree |
| `/set-password` | `pages/auth/SetPassword.tsx` | REAL `/auth/set-password` | ✔ | n/a | ✔ (`:70-76`) + `NO_TOKEN` guard | n/a | zod validation, `role="alert"` |
| `/start` | `pages/auth/Start.tsx` | **STATIC PLACEHOLDER** (Unit 43) | n/a | n/a | n/a | n/a | Deliberate, self-documenting; delete on Unit 43 |
| `/dashboard` | `pages/dashboard/Dashboard.tsx` | REAL `GET /client/cases` | ✔ `ListSkeleton` (`:55`) | ✔ (`:68-74`) | ✔ + 403-specific + retry (`:57-66`) | n/a | ~90% duplicate of `/requests` (F9) |
| `/requests` | `pages/requests/Requests.tsx` | REAL `GET /client/cases` | ✔ (`:52`) | ✔ (`:67-73`) | ✔ + retry | n/a | Same query key, same rows, no "Needs you" split |
| `/documents` | `pages/documents/Documents.tsx` | REAL `GET/POST /client/documents`, `/client/documents/:id/url` | ✔ `TableSkeleton` + upload `Progress` | ✔ ×2 sections | ✔ + retry + toast | ✖ re-upload over an accepted doc is unguarded | Strongest screen in the portal |
| `/invoices` | `pages/invoices/Invoices.tsx` | REAL `GET /client/invoices` | ✔ (`:59`) | ✔ (`:77-81`) | ✔ + 403-specific (`:61-73`) | n/a read-only | `crypto.randomUUID()` as React key (`:96`) — see F16 |
| `/meetings` | `pages/meetings/Meetings.tsx` | REAL `GET /client/meetings` | ✔ | ✔ (`:76-80`) | ✔ + 403-specific | n/a read-only | Renders GHL's unzoned timestamps as text, never `Date` (`:155-173`) — correct |
| `/draft`, `/draft/:caseId` | `pages/draft/DraftReview.tsx` | REAL `GET /client/cases/:id`, `POST .../approve`, `.../request-revisions` | ✔ ×2 queries | ✔ (`:127-129`) | ✔ ×2 + retry | **✖ "Approve this draft" is irreversible (Handoff B) and fires on one click** (`:181-194`) — copy warns, UI does not gate | `crypto.randomUUID()` key `:84` |
| `*` | `@shared/pages/NotFound` | static | n/a | n/a | n/a | n/a | |

Chrome: `PortalLayout` → `PortalSidebar` / `MobileNavDrawer` / `PortalHeader`. **No sign-out
anywhere** (`components/layout/PortalHeader.tsx:23`, `PortalSidebar.tsx:40`) — see F7.

### 3. `client-expert/expert/` — Expert Portal (Vite :5175)

| Route | Component (`path`) | Backing | Loading | Empty | Error | Confirm | Notes |
|---|---|---|---|---|---|---|---|
| `/case` | `pages/portal/ExpertCasePortal.tsx` | REAL `GET /expert/case`, `accept`, `decline`, `request-evidence`, `letter`, `signed-letter` | ✔ `ListSkeleton` (`:83`) | ✔ evidence empty (`:166-170`) | ✔ + retry + per-action toasts | ▲ attestation checkbox gates upload (`:294-319`); **Decline is one click with a free-text reason and no modal** (`:386-392`) | Single-route app; no dashboard, no payouts screen (Unit 35 D6 unbuilt) |
| `*` | `@shared/pages/NotFound` | static | n/a | n/a | n/a | n/a | |

**MOCK count across all three apps: 0 routed screens.** `expert/src/mock/expertMockData.ts`
(109 lines) and its type file `expert/src/types/expert.ts` (48 lines) have **zero importers**
— verified by grep across `client/src expert/src shared/src`. `shared/src/mock/mockDelay.ts`
likewise: the only "hit" is a stale sentence in `expertPortalService.ts:8`.

---

## Findings

**F1 — No server-state library in the internal app; 28 hand-rolled `useEffect` fetchers, zero caching.**
Evidence: `frontend/package.json` has no `@tanstack/react-query`; `frontend/src/features/dashboards/useMetrics.ts:1-91` is the hand-rolled substitute; 28 files carry `useEffect`-based loads. `/cases/board` is independently fetched by `BoardView`, `InboxPage`, `MyDraftsPage`, `DeliveryQueuePage` and `ExpertAssignmentPage` — five screens, five uncached requests, refetched in full on every navigation.
Impact: every tab switch is a cold load; no dedup, no background refresh, no cross-screen invalidation after a mutation. The portals already run React Query with a 60s `staleTime` (`client/src/main.tsx:6-13`), so the two halves of one product behave differently.
Decision: **REFACTOR** — adopt React Query in `frontend/` and keep `useMetrics`' genuinely good ideas (`emptyWhen`, `warnWhen`, `CardState`) as adapters over it. Priority: **P1**.

**F2 — `strict` is not set in any of the four tsconfigs.**
Evidence: `frontend/tsconfig.app.json`, `frontend/tsconfig.node.json`, `client-expert/client/tsconfig.app.json`, `client-expert/expert/tsconfig.app.json` — none contains `"strict"`, and `grep '"strict"'` over all of them returns nothing.
Impact: `strictNullChecks` is off, which makes the entire `string | null` DTO vocabulary decorative — `ClientInvoice.total: number | null`, `ExpertCaseView.caseReference: string | null`, `StaffIdentity.brandId: string | null` are all silently non-null to the compiler. The careful null-handling in `Invoices.money()` and `expertCase.stateOf()` is convention, not a checked contract. This is the single highest-leverage config change in the repo.
Decision: **MODIFY** — add `"strict": true` (at minimum `strictNullChecks`) to all four, fix the fallout. Priority: **P0**.

**F3 — Two disjoint design systems; `shared/` is shared between the two portals only, and cannot be shared with the internal app as-is.**
Evidence: internal app = Tailwind **v4** via `@tailwindcss/vite` (`frontend/vite.config.ts:9`), tokens `--bg-base`/`--text-primary`/`--status-red` (`frontend/src/styles/tokens.css`), Inter. Portals = Tailwind **v3** + postcss/autoprefixer (`client-expert/package.json`), shadcn HSL-triplet tokens `--primary`/`--muted-foreground`/`--destructive` (`shared/src/styles/globals.css:1-60`), DM Sans/DM Serif loaded from Google Fonts at runtime. Two different Radix installs too: internal uses the `radix-ui` meta-package (`frontend/src/components/ui/dialog.tsx:1`), portals use 15 individual `@radix-ui/react-*` packages.
Impact: no primitive can move between them without a rewrite. `shared/` is genuinely shared by client+expert (`cn` 29 consumers, `PageHeader` 8, `ErrorState` 8) and has **not** drifted between those two — but it is invisible to the internal app, which re-implements `Card`, `Dialog`, `Tabs`, `Menu` and all formatting from scratch.
Decision: **REDESIGN** (one token layer, one Tailwind major) or **KEEP** (accept two design systems explicitly and stop calling `shared/` "shared by the product"). Do not half-do it. Priority: **P1**.

**F4 — Large dead-code tail in `shared/` and `expert/`, including a *contradicting* duplicate of a live rule.**
Evidence, all verified zero-importer:
- `shared/src/components/ui/{avatar,date-picker,dropdown-menu,pagination,popover,radio-group,select,separator,switch,tabs,tooltip}.tsx` — 11 files.
- `shared/src/hooks/useMediaQuery.ts`, `shared/src/constants/storage.ts`, `shared/src/constants/upload.ts`, `shared/src/utils/storage.ts`, `shared/src/mock/mockDelay.ts`, `shared/src/schemas/auth.ts`.
- `shared/src/utils/formatters.ts` — `formatDateTime`, `formatCurrency`, `formatFileSize`, `getInitials`, `relativeTime` all unused; only `formatDate` (1) and `formatDateShort` (2) are live.
- `expert/src/mock/expertMockData.ts`, `expert/src/types/expert.ts`, `client/src/constants/countries.ts`, `client/src/schemas/intake.ts:11-31` (`aboutYouSchema`/`AboutYouFormValues`; only `passwordRules` is imported).
- `shared/src/components/common/ConfirmDialog.tsx` — **unused, while three irreversible actions ship with no confirmation** (F11).
The dangerous one: `shared/src/schemas/auth.ts:14` declares its own `passwordRules`, duplicating the live `client/src/schemas/intake.ts:3`. `SetPassword.tsx:18-20` states in a comment that `passwordRules` "is the one place the strength rule is written" — which is false today.
Decision: **REMOVE**. Priority: **P1** (P0 for the duplicated password rule).

**F5 — `OpportunityBoardPage` blanks the entire board to a skeleton after every note, stage move, close or lead-open.**
Evidence: `features/opportunities/OpportunityBoardPage.tsx:33-37` passes `[reloads]` as `useMetrics`' **deps**, and `features/dashboards/useMetrics.ts:38-42` clears `data` on every deps change — exactly the behaviour `useMetrics:28-37` documents itself as having been fixed to avoid, by providing a separate `reload()` that does **not** clear. `reload` is returned by the hook and ignored here.
Impact: a salesperson adding a note loses the whole board, their scroll position and every open card. This is the single worst interaction regression in the app.
Decision: **MODIFY** — drop the `reloads` state, use the hook's own `reload`. One-line-ish fix. Priority: **P0**.

**F6 — Fetch waterfalls on `/cases/:id` and per-card on the opportunity board.**
Evidence: `case/CaseDetail.tsx:58` correctly parallelises detail+timeline, but the child panels each fire independently *after* the parent resolves — `case/DocumentList.tsx:36` (mounted **twice**: `DocumentsPanel.tsx:32` and `ExpertCard.tsx:88`), `case/DraftHistory.tsx:31`, `checklist/CaseChecklist.tsx:56`, `experts/ShortlistPanel.tsx:47`. Up to 7 requests, two levels deep, uncached, on every visit. Separately, `opportunities/DealActions.tsx:63-69` fetches `/sales/calendars` **once per opened deal card** — the file's own comment concedes "loaded once per card" — against GHL's 100-req/10s budget.
Decision: **REFACTOR** — React Query (F1) fixes the calendars dedup for free; hoist the calendar list to the page and let the server return the case's panels on one payload where it can. Priority: **P1**.

**F7 — The Client Portal has accounts but no sign-out, and a refresh signs you out.**
Evidence: `shared/src/services/apiClient.ts:29` holds the token in a module variable, never persisted ("The token is held in a module variable and never persisted", `:12-14`). `PortalLayout.tsx:44-46` redirects to `/signin` when it is absent. `SignIn.tsx:103` navigates to `/dashboard` with no fragment — so the first page refresh drops the client back to sign-in. Meanwhile `PortalHeader.tsx:23` and `PortalSidebar.tsx:40` still assert "There is no Logout… There is no session to end", which Unit 42 reversed. `grep -i 'sign ?out|logout|clearPortalToken'` finds no implementation anywhere in the portals.
Impact: no way to end a session on a shared machine, no way to switch accounts, and losing your place on any refresh. The internal app got this right (`TopBar.tsx:68-84`).
Decision: **ADD** sign-out + a `clearPortalToken`, and decide deliberately whether the signed-in token survives a reload (it must, or the portal is unusable). Update the two stale doc-comments. Priority: **P0**.

**F8 — `PortalLinkLedger` still renders `CLIENT` link rows and rests on a premise Unit 42 retired.**
Evidence: `dashboards/PortalLinkLedger.tsx:113` `row.audience === 'CLIENT' ? 'Client' : 'Expert'`; the file header (`:14-21`) argues the tile exists because "EvalOS sends no mail (invariant 14), so a link reaches its recipient because a staff member copied it out". Invariant 14 was amended and `backend/.../PortalLinkController.java:20-22` records that the `?audience` parameter was removed and the staff app's only caller is `ExpertCard`, always `EXPERT`. The tile renders on three dashboards (`PmDashboard.tsx:215`, `CoordinatorDashboard.tsx:105`, `CaseManagerDashboard.tsx:165`).
Decision: **MODIFY** — drop the CLIENT branch and the audience column if the server no longer emits CLIENT rows; rewrite the header rationale. Priority: **P1**.

**F9 — `/dashboard` and `/requests` in the Client Portal are the same screen twice.**
Evidence: `pages/dashboard/Dashboard.tsx` and `pages/requests/Requests.tsx` share the same query key `['portal','cases']`, the same `listCases` call, the same 403 copy, the same empty state, and each defines its own near-identical `CaseRow`. The only difference is the `needsYou`/`running` split and a `Shortcuts` block (`Dashboard.tsx:119-136`). The sidebar lists both ("Home", "My cases" — `client/src/constants/navigation.ts:31-32`).
Decision: **REMOVE** `/requests` and make `/dashboard` the one case list, or make `/requests` a genuinely different view (filters, history). Priority: **P2**.

**F10 — Three unused exported API functions: features wired at the service layer with no UI.**
Evidence: `opportunities/opportunityApi.ts:99` `valueLead` (`PUT /marketing/leads/:id`), `:147` `updateDeal` (`PUT /sales/opportunities/:id`), `payouts/payoutApi.ts:73` `editPayment` (`PATCH /payments/:id`). Also `client/src/services/draftService.ts:23` `readDraft` (`GET /client/case`).
Impact: a salesperson can move, close and annotate a deal but cannot rename it or set its value; a payment recorded with a wrong reference cannot be corrected from the UI.
Decision: **ADD** the missing affordances (an inline edit on the deal card, an edit control on the payment detail), or **REMOVE** the functions. Do not leave them. Priority: **P2**.

**F11 — Destructive and irreversible actions with no confirmation, while an unused `ConfirmDialog` sits in `shared/`.**
Evidence and severity order:
1. `client/src/pages/draft/DraftReview.tsx:181-194` — "Approve this draft" is Handoff B, sends the letter to an expert to sign, and the copy itself says "It cannot be undone from here". One click, no modal.
2. `frontend/src/features/opportunities/DealActions.tsx:109-127` — `won` / `lost` / `abandoned` are three adjacent equal-weight buttons; `won` creates a case in GHL. One click each.
3. `expert/src/pages/portal/ExpertCasePortal.tsx:386-392` — "Decline" returns the case for rematching, "cannot be undone from here". Gated only by a non-empty reason.
4. `frontend/src/features/payouts/PaymentDetail.tsx:155-166` — "The expert confirmed receipt" is terminal and confirms every draft at once.
5. `frontend/src/features/jobs/JobRunsPage.tsx:31-46` — manual sweep trigger, unguarded.
The counter-example that proves it is achievable: `queues/DeliveryQueuePage.tsx:135-174` confirms in a dialog, for exactly this reason. `shared/src/components/common/ConfirmDialog.tsx` exists and has zero consumers.
Decision: **ADD** confirmation to 1–4 (1 and 3 minimum). Priority: **P0** for the portal pair, **P1** for the rest.

**F12 — The internal app is desktop-only.**
Evidence: `features/shell/AppShell.tsx:27` offsets content by `var(--sidebar-width)` unconditionally; `features/shell/LeftNav.tsx:44` is `fixed inset-y-0 left-0` with no `hidden`/`lg:` variant and no drawer. `styles/tokens.css:107-111` is the only accommodation — 15rem → 13rem below 1200px. Across `features/` + `components/` there are **25 responsive utilities total** (`xl:` 10, `md:` 8, `sm:` 6, `lg:` 1). The portals, by contrast, have a proper `MobileNavDrawer` and an `lg:grid` layout (`client/src/layouts/PortalLayout.tsx:49-56`).
Impact: on a phone the shell reserves 13rem of a 375px viewport; the Kanban board and every table overflow.
Decision: **ADD** a collapsing rail + mobile drawer in `AppShell`/`LeftNav`, or **KEEP** and state "internal app is desktop-only" as a decision. Priority: **P1**.

**F13 — No global search; the search box is a disabled input.**
Evidence: `features/shell/TopBar.tsx:50` `disabled`, placeholder `"Search — not available yet"`, `title="Case search has no endpoint yet"`. No `/search` endpoint in the 68-call inventory.
Decision: **ADD** (needs a backend endpoint first) or **REMOVE** the control until there is one — a permanently disabled affordance in the header of every screen teaches people the app is broken. Priority: **P1**.

**F14 — `/dashboard` is the one route not gated by `RoleRoute`.**
Evidence: `frontend/src/App.tsx:132` renders `<RoleDashboard />` directly, while every other in-shell route goes through `<RoleRoute path=…>` (`:135-171`). `navigation.ts:66` gates `/dashboard` to `PRODUCTION_ROLES`, so `mayReach('SALES','/dashboard')` is `false` — but a SALES user can deep-link there and `RoleDashboard.tsx:43-44` renders their opportunity board. It is harmless today only because the `Record<Role, ReactNode>` map is exhaustive.
Related: `components/Forbidden.tsx:17` and `pages/NotFound.tsx:10` hardcode a `/dashboard` back-link, where `PlaceholderPage.tsx:9` correctly uses `boardPathFor(me.role)`.
Decision: **MODIFY** — wrap `/dashboard` in `RoleRoute` (or move it into the mapped loop), and route the two static pages through `boardPathFor`. Priority: **P2**.

**F15 — No `ErrorBoundary` in the internal app.**
Evidence: the portals wrap everything (`client/src/App.tsx:133`, `expert/src/App.tsx:47`) in `@shared/components/common/ErrorBoundary`. `frontend/src/main.tsx:12-18` and `App.tsx:85-91` have none. A render throw — and `useMe()` throws by design (`lib/authContext.ts:36`) — blanks the page with nothing but a console trace.
Decision: **ADD**. ~40 lines, reuse the portals' shape. Priority: **P1**.

**F16 — `crypto.randomUUID()` used as a React key.**
Evidence: `client/src/pages/invoices/Invoices.tsx:96` `key={invoice.invoiceNumber ?? crypto.randomUUID()}`, `client/src/pages/meetings/Meetings.tsx:84` `key={meeting.id ?? crypto.randomUUID()}`.
Impact: a new key on every render for any row with a null id — React unmounts and remounts those rows each time, and the fallback defeats the point of a key entirely. Use the array index for the null case.
Decision: **MODIFY**. Priority: **P2**.

**F17 — Design-token drift inside the internal app: Unit 38/40's newest code uses raw Tailwind palette classes.**
Evidence: 66 files use `var(--…)` tokens; 6 files use raw `slate-*`/`gray-*` (55 occurrences) — `opportunities/OpportunityBoardPage.tsx`, `DealActions.tsx`, `DealNotes.tsx`, `NewLeadForm.tsx`, `jobs/JobRunsPage.tsx`, `dashboards/PortalLinkLedger.tsx`. These will not respond to any future theming and already read differently from the rest of the app.
Decision: **MODIFY** — port the 6 files to the token set. Priority: **P2**.

**F18 — `MAX_UPLOAD_MB` declared three times, with two different values.**
Evidence: `shared/src/lib/portal.ts:87` = 15, `expert/src/lib/expertCase.ts:113` = 15, `shared/src/constants/upload.ts:6` `DEFAULT_MAX_FILE_SIZE_MB` = 10 (dead). Also `shared/src/constants/upload.ts:5` lists `DOC`/`DOCX` as accepted, which `Documents.tsx:42` does not.
Decision: **REMOVE** `constants/upload.ts`, keep the one in `portal.ts`, have `expertCase.ts` re-export it. Priority: **P2**.

**F19 — Currency formatting is implemented three times.**
Evidence: `frontend/src/lib/money.ts` (`formatMoney` USD-no-cents, `formatPayout` currency-aware 2dp), `client/src/pages/invoices/Invoices.tsx:131-137` (a local `money()`), `shared/src/utils/formatters.ts:37-43` (`formatCurrency`, dead). Three answers to one question across one product.
Decision: **REFACTOR** into one module once F3 is decided. Priority: **P2**.

**F20 — Stale `.env.example` documentation in both portals.**
Evidence: `client-expert/client/.env.example:2-3` and `expert/.env.example:2-3` both say "Leave blank during frontend-only work, where the mock service layer in src/services answers instead of a real backend." No such layer exists any more. `VITE_PORTAL_URL` is declared in both `.env.example` files and in both `vite-env.d.ts` but is **read nowhere**.
Decision: **MODIFY** the copy, **REMOVE** `VITE_PORTAL_URL`. Priority: **P2**.

**F21 — Portals have no dev proxy; the internal app does.**
Evidence: `frontend/vite.config.ts:16-23` proxies `/api` → `localhost:8080`. `client/vite.config.ts` and `expert/vite.config.ts` have no `proxy` block, so dev runs cross-origin against `VITE_API_URL` and depends on the backend's portal CORS chain (`apiClient.ts:5-11` documents `allowCredentials(false)`). Defensible given the header-credential design, but it means the two halves are configured differently for no stated reason.
Decision: **KEEP** with a note, or **ADD** a proxy for parity. Priority: **P2**.

**F22 — No component tests anywhere; `@/*` alias configured and unused in the internal app.**
Evidence: 13 test files total, all pure-function rule tests (`boardRules`, `queueRules`, `payoutRules`, `navigation`, `portal`, `expertCase`, `authService`, `money`, …). No `@testing-library/*` in either `package.json`; zero render tests. Separately, `frontend/tsconfig.app.json:13` and `vite.config.ts:11` define `@/*` and `grep "from '@/"` over `frontend/src` returns **0** — the whole app uses relative imports.
Decision: **ADD** a thin render-test layer for the state matrix (loading/empty/error is exactly what unit tests cannot cover); **REMOVE** the unused alias or adopt it. Priority: **P2**.

**F23 — `frontend/package.json` is named `"client"`.**
Evidence: `frontend/package.json:2` `"name": "client"`, in a repo whose actual client app is `client-expert/client`.
Decision: **MODIFY** to `evalos-staff` or similar. Priority: **P2** (trivial, but it is an active source of confusion).

### What is genuinely good and should not be touched

- `frontend/src/features/shell/navigation.ts` — nav and route allow-list as **one table**, with a `readsGhlLocation` flag so the GM-only scoping rule cannot be forgotten by omission. Permission-aware UI is done properly here: `navFor`/`navSectionsFor` filter the rail, `mayReach` guards the router, and `boardPathFor`/`homePathFor` never offer a link that would 403. The one hole is F14.
- `frontend/src/components/ui/card.tsx:24-46` — a six-member `CardState` union including `empty` (an operational statement, not a missing value) and `unavailable` (names the blocking unit). This is a better state model than most production apps have.
- `frontend/src/lib/api.ts:24-52` — response interceptor lifts the server's envelope message onto `error.message`, drops the token on 401 only, and suppresses StrictMode abort noise.
- `shared/src/lib/portal.ts` and `expert/src/lib/expertCase.ts` — zero-import, test-covered wire-shape modules with an explicit "this app holds no lifecycle enum" rule. `Record<ChecklistItemStatus,…>`/`Record<ExpertSignStatus,…>` make a new server value a compile error.
- `client/src/pages/auth/SignIn.tsx:41-51` — `Record<IdentifyState, string | null>` forcing every auth state to have copy, after review found `MAIL_UNAVAILABLE` silently unhandled.
- `client/src/pages/meetings/Meetings.tsx:148-173` — refusing to `new Date()` GHL's unzoned timestamps.

---

## Types

No `any` anywhere in either tree (verified by grep across all four `src` roots) — genuinely
unusual and worth preserving.

Types are hand-written per feature and each one names the backend DTO it mirrors in a doc
comment (`opportunityApi.ts:3` "`OpportunityBoardService.Deal`", `portal.ts:18` "`PortalCaseService.ChecklistItemView`, exactly", `expertCase.ts:21` "`ExpertPortalService.ExpertCaseView`, exactly"). That discipline is
real, but it is convention-enforced: nothing fails if the backend adds or renames a field.

Drift found:
- `Role` is declared in `frontend/src/lib/session.ts:13-25` only. The portals have no role
  concept, which is correct.
- The `ClientApprovalStatus` / `ChecklistItemStatus` / `ExpertSignStatus` / `SlaStatus`
  vocabularies exist in `shared/lib/portal.ts` and `expert/lib/expertCase.ts` and **also**
  independently in `frontend/src/features/case/caseApi.ts` and `board/boardRules.ts` — the
  internal app spells `ExpertSignStatus`'s four values again in `ExpertCard.tsx:19-23`
  (`PENDING`/`SIGNED`/`OVERDUE`/`REASSIGNED` with its own colour map). Two label tables for one
  server enum, in two apps, with no shared source.
- `shared/src/constants/upload.ts` contradicts `shared/src/lib/portal.ts` on the upload cap and
  the accepted extension list (F18).

The fix is not a shared types package today — it is `strict: true` (F2) plus generating the wire
types from the Spring DTOs if/when that becomes cheap. Priority order: F2 first.

---

## Shared-code plan

Concretely, and in the order it should be done:

1. **Delete before sharing.** Everything in "Things safe to delete today" goes first. `shared/`
   currently looks bigger and more capable than it is; 11 of its 23 UI primitives have no
   consumer, which is what makes "is `shared/` actually shared?" hard to answer. After the
   deletion it is 12 primitives + 12 common components + `cn` + `portal.ts` + `apiClient.ts`,
   and every one of them has ≥1 consumer. **Answer: yes, `shared/` is genuinely shared between
   client and expert and has not drifted** — `cn` 29 consumers, `PageHeader`/`ErrorState` 8,
   `EmptyState`/`LoadingState` 7, `usePortalToken` 7. The drift is all dead weight, not
   divergence.

2. **`ConfirmDialog` → wire it up, don't delete it.** It is the only unused shared component
   that should stay, because F11 needs it in `DraftReview.tsx` and `ExpertCasePortal.tsx` this
   week.

3. **Collapse the duplicate password rule now.** Delete `shared/src/schemas/auth.ts` entirely
   (`loginSchema` is dead, `passwordRules` is a divergent copy). `client/src/schemas/intake.ts`
   keeps `passwordRules`; trim `aboutYouSchema` and `AboutYouFormValues` from it. Then
   `SetPassword.tsx:18-20`'s claim becomes true.

4. **Move `MAX_UPLOAD_MB` to one home.** `shared/src/lib/portal.ts:87` keeps it;
   `expert/src/lib/expertCase.ts:113` re-exports rather than redeclares;
   `shared/src/constants/upload.ts` is deleted.

5. **Do not move `shared/` into `frontend/` yet.** F3 is a prerequisite: the internal app is
   Tailwind v4 + a bespoke token set, the portals are Tailwind v3 + shadcn HSL tokens, and the
   Radix installs differ. Sharing a `Button` across that boundary is a rewrite, not a move.
   What *can* move today, because it is styling-free:
   - `shared/src/utils/cn.ts` → nothing to share, the internal app doesn't use `clsx`/`tailwind-merge` at all. Leave.
   - **A new `shared/src/lib/vocabulary.ts`** holding the server enums both trees spell —
     `ExpertSignStatus`, `SlaStatus`, `ChecklistItemStatus`, `ClientApprovalStatus` — as *types
     and values only*, with the per-app label/colour maps staying per-app. This is the one
     shared module the internal app can consume without touching the design-system question.
   - **Currency formatting** (F19): one `formatMoney`/`formatPayout` module, imported by both
     trees. `frontend/src/lib/money.ts` is already the best version; move it up.

6. **Then decide F3 deliberately.** Either unify on Tailwind v4 + one token layer and make
   `shared/components/ui` the real design system for all three apps, or write down that the
   internal app and the portals are two products with two looks. The current state — a
   directory called `shared` that two of three apps can use — is the worst of both, because it
   reads as an intention nobody is executing.

---

## Things safe to delete today

Verified zero importers across `client/src`, `expert/src`, `shared/src` and `frontend/src`.

```
client-expert/shared/src/components/ui/avatar.tsx
client-expert/shared/src/components/ui/date-picker.tsx
client-expert/shared/src/components/ui/dropdown-menu.tsx
client-expert/shared/src/components/ui/pagination.tsx
client-expert/shared/src/components/ui/popover.tsx
client-expert/shared/src/components/ui/radio-group.tsx
client-expert/shared/src/components/ui/select.tsx
client-expert/shared/src/components/ui/separator.tsx
client-expert/shared/src/components/ui/switch.tsx
client-expert/shared/src/components/ui/tabs.tsx
client-expert/shared/src/components/ui/tooltip.tsx
client-expert/shared/src/hooks/useMediaQuery.ts
client-expert/shared/src/constants/storage.ts          # 'ie_portal.mockAccounts' etc. — mock-era
client-expert/shared/src/constants/upload.ts           # contradicts portal.ts (F18)
client-expert/shared/src/utils/storage.ts
client-expert/shared/src/mock/mockDelay.ts             # last mock module in shared/
client-expert/shared/src/schemas/auth.ts               # divergent duplicate of passwordRules (F4)
client-expert/expert/src/mock/expertMockData.ts        # DEMO_EXPERT, INITIAL_EXPERT_CASES
client-expert/expert/src/types/expert.ts               # only importer is the file above
client-expert/client/src/constants/countries.ts
```

Partial deletions in files that must stay:

```
client-expert/client/src/schemas/intake.ts:11-31       # aboutYouSchema + AboutYouFormValues
client-expert/shared/src/utils/formatters.ts:23-84     # formatDateTime, formatCurrency,
                                                       # formatFileSize, getInitials, relativeTime
```

Once those drop, the following can leave `client-expert/package.json` (each backs only deleted
code — re-verify against the final tree before removing):
`@radix-ui/react-avatar`, `@radix-ui/react-dropdown-menu`, `@radix-ui/react-radio-group`,
`@radix-ui/react-select`, `@radix-ui/react-separator`, `@radix-ui/react-switch`,
`@radix-ui/react-tabs`, `@radix-ui/react-tooltip`, `@radix-ui/react-popover`, `date-fns`,
`recharts`, `react-hook-form`, `@hookform/resolvers` — the last four have **zero** references
anywhere in `client/src`, `expert/src` or `shared/src`.

Keep but fix, do not delete: `shared/src/components/common/ConfirmDialog.tsx` (F11),
`client/src/pages/auth/Start.tsx` (deliberate Unit 43 placeholder, self-documenting).

Nothing is checked into git that should not be — `git ls-files` finds no `dist/`, `.tmp/` or
`node_modules`; the on-disk `frontend/dist`, `client/dist`, `expert/dist`, `client/.tmp`,
`expert/.tmp` are all covered by `.gitignore` and `client-expert/.gitignore`.
