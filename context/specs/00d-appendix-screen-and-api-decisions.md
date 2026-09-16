# 00d Appendix — screen and API decisions

Written 2026-09-13 against `development` @ `dee45c6`.

`00d` §9 attaches a decision to each of **18 modules**. The commissioning brief asked for one
per *screen* and per *API* as well. This appendix is that complement, and nothing else — it
adds no finding `00d` and the six reports do not already carry, and it does not restate §9.

**Inventories are reused, not re-derived.** Routes come from
`context/audit/2026-09-13/audit-backend.md` → *API inventory* (auth + `path:line` per route);
screens from `audit-frontend.md` → *Screen inventory*; state coverage and widget gaps from
`audit-ux.md`; process gaps from `audit-product.md`; schema and boundaries from
`audit-architecture.md`; the GHL surface from `audit-integration.md`. Where
`verification-unit-specs.md` overturned a `00d` recommendation, **its ruling is what appears
here** (upsert verb, `PipelineScope`, the `CaseIntakeService` injector, Unit 25, the portal
signup commit order, the document key) — see §G.

**One inventory correction, verified in code.** `audit-backend.md`'s summary line reads *"105
routes across 27 controllers"*. Its own table lists **129** rows, and the code agrees:
`grep -c "@(Get|Post|Put|Patch|Delete)Mapping" web/ webhook/` = **129 handler methods across 28
controller files** (104 staff-chain, 25 portal-chain). The table is complete; the summary
sentence undercounts. Everything below is against **129 / 28**.

**How to read this.** The large majority of routes and screens are correct and untouched. They
are collapsed to one line per controller or per screen group. A route or screen gets a full
row **only** when it carries a decision other than KEEP, or is the target of a finding.
Decisions: **K**eep · **M**odify · **R**efactor · **RD**esign · **A**dd · **RM**ove.
Systems: EvalOS · GHL · S3 · mail · portal. Priority follows `00d` §11's phases —
**P0** = Phase 0–1, **P1** = Phase 2–3, **P2** = Phase 4+ or cosmetic.

---

## A. API routes by controller

The two security chains are separate by construction — `PortalSecurityConfig` is `@Order(1)` on
`/api/portal/**` and contains no `JwtFilter`; `JwtFilter`'s servlet registration is disabled so
Boot cannot auto-register it (`audit-backend.md` → *Do-not-break* item 1). They are assessed
separately because a change to one is not a change to the other.

### A.1 Staff chain (`SecurityConfig`, order 2 — bearer JWT) — 104 routes, 25 controllers

| Controller | Routes | Verdict |
|---|---|---|
| `CaseController` | 35 | **KEEP** — except `POST /cases/{id}/refund/request` (A.2 §S3) |
| `CaseBoardController` | 1 | **KEEP** — `GET /cases/board` is the read model 5 screens share; the fix is client-side caching (B, F1), not the route |
| `CaseTimelineController` | 1 | **KEEP** — becomes the case anchor of `UnifiedTimelineService`; the route itself does not change |
| `ChecklistController` | 5 | **KEEP** |
| `ExpertController` | 10 | **KEEP** — `PUT /experts/{id}/payment-detail` is the lone `PUT` among `PATCH`es (`audit-backend` A3); cosmetic, not worth a breaking change |
| `ExpertPickerController` / `ExpertShortlistController` | 1 + 1 | **KEEP** |
| `PayoutController` | 5 | **KEEP** |
| `PaymentController` | 4 | **KEEP** — except `PATCH /payments/{id}` (detail row below) |
| `MetricsController` | 8 | **KEEP** — every gap is on the consuming card, not the endpoint (B.2) |
| `NotificationController` | 4 | **KEEP** — ENM demand notification is a new `NotificationType`, not a new route |
| `BrandController` | 1 | **KEEP** — the gap is the missing `/brands` screen (B.4) |
| `TeamMemberController` | 3 | **KEEP** — but `PUT /{id}/ghl-pipeline` has **no UI caller** (verified: `grep -r ghl-pipeline frontend/src client-expert` = 0 hits). Detail row below |
| `GhlPipelineController` | 1 | **KEEP** |
| `MarketingController` | 3 | **MODIFY** — detail row below |
| `MarketingLeadController` | 2 | **KEEP the verb** — detail row below |
| `OpportunityBoardController` | 1 | **KEEP** |
| `OpportunityNoteController` | 2 | **MODIFY** — detail row below |
| `SalesCalendarController` | 1 | **KEEP** — the per-card refetch is a frontend defect (B.3), not a route defect |
| `SalesDeskController` | 6 | **KEEP** — cache-on-write happens inside the existing handlers (A.2) |
| `JobAdminController` | 3 | **KEEP** — except `GET /jobs/runs` returning the JPA entity (detail row) |
| `AuthController` / `HealthController` | 2 + 1 | **KEEP** |
| `InboundWebhookController` | 1 | **KEEP the route** — the replay is a sweep behind it (E, Phase 0) |
| `PortalLinkController` | 2 | **KEEP** — expert-only since Unit 42; `?audience` already removed |

**Detail rows — staff chain**

| Route | What exists | What the target expects | The difference | Dec | Depends on | Roles | Systems | P |
|---|---|---|---|---|---|---|---|---|
| `POST /api/cases/{id}/refund/request` (`CaseController.java:787`) | No `@PreAuthorize`; any authenticated staff role may fire it. Scoped load keeps it in-brand | The roles that can rule on money | `CaseTransitions:210-212` makes `REFUND_REQUESTED` block **every other transition** until a GM rules — so any role can deny progress on any case they can see, one POST. Its javadoc still cites Unit 18, deleted by `V33` | **M** | — | GM/BM/PM/PC keep it; PC/ENM/CM lose it | EvalOS | P2 |
| `GET /api/opportunities/{opportunityId}/notes`, `POST …/notes` (`OpportunityNoteController.java:56,62`) | `SALES, MARKETING` only — the GM is excluded by an explicit controller-javadoc decision | `Tier.ALL` reads the deal stream | One half of "no role on earth can read both note streams" (`00d` §3.1). The exclusion is an implementation consequence (`PipelineScope` asks *is this my pipeline*, a GM owns none) hardened into policy | **M** | S2 read model | +GM (and BM by the same argument) | EvalOS | **P0** |
| `GET /api/marketing/ads-pipeline`, `/email-pipeline`, `/sales-pipeline` (`MarketingController.java:71,93,124`) | `@PreAuthorize("GM")` on all three | MARKETING opens its own funnels | **The role is named after two screens it cannot open.** Widen with the same shape as the existing `sales-brand` exception; this is a cheaper partial pre-emption of **Unit 25a** and must say so. Touches `navigation.test.ts`'s GM-only assertion and `architecture.md`'s invariant-1 exception. `38` §5 kept the three `*-pipeline-name` properties deliberately — they are **analytics funnel** config, not one of the nine operational pipelines (`verification` Correction 6) | **M** | invariant-1 amendment | MARKETING (+GM) | EvalOS, GHL | P1 |
| `POST /api/marketing/leads`, `PUT /api/marketing/leads/{opportunityId}` (`MarketingLeadController.java:59,66`) | `upsertContact` + `upsertOpportunity`, no `id` sent ⇒ one open opportunity per contact per pipeline | A returning client's second deal is not merged into their first | **Do not change the verb.** `39` §3a decided this knowingly and calls it correct for a marketing pipeline; `43` §7 and criterion 7 depend on both calls being upserts. The fix is a **UI confirmation on `isNew=false`** ("this contact already has a deal here — open it, or create a second?"), using a flag already bound and already returned. A real create needs a correlation key GHL does not offer — that is Unit 44's | **K** (route) + **A** (UI step) | U44 correlation key | MARKETING | EvalOS, GHL | P2 |
| `PUT /api/team-members/{id}/ghl-pipeline` (`TeamMemberController.java:90`) | Built, GM-gated, **zero UI callers** | A GM onboards a salesperson without a DBA | `C4` of the cutover (`00d` §2.1) requires reassigning every active SALES/MARKETING member through exactly this route, and there is no screen to do it from. Onboarding a seat is currently a database write — a hard ceiling for a pivot whose point is six new seats | **K** (route) + **A** (screen, B.4) | — | GM/BM | EvalOS, GHL | P1 |
| `GET /api/jobs/runs` (`JobAdminController.java:41`) | Returns `List<ScheduledJob>` — the JPA entity | A DTO, like the other 128 | The only entity-returning endpoint in the app besides two narrow GHL projections (which are fine) | **M** | — | GM | EvalOS | P2 |
| `PATCH /api/payments/{id}` (`PaymentController.java:64`) | Built and whitelisted; `payoutApi.editPayment` exists with **zero UI consumers** (frontend F10) | A wrong reference is correctable from the UI | A payment recorded with a wrong reference can only be fixed in the database. Either wire the affordance on `/payouts/payments/:paymentId` or delete the client function — **do not leave it** | **A** (affordance) | — | PAYOUTS roles | EvalOS | P2 |
| `PUT /api/sales/opportunities/{id}` (`SalesDeskController.java:75`) | Built; `opportunityApi.updateDeal` exists with zero UI consumers (F10). Same for `PUT /marketing/leads/{id}` ↔ `valueLead` | Sales can rename a deal and set its value | A salesperson can move, close and annotate a deal but cannot rename or value it. Inline edit on the deal card | **A** (affordance) | — | SALES/MARKETING | EvalOS, GHL | P2 |
| all 6 `SalesDeskController` writes + `POST /marketing/leads` | Each receives GHL's authoritative row back and **discards it**; nothing writes the opportunity cache | A dragged card stays where it was dropped | Cards snap back for up to the 2-minute TTL, and `PipelineScope` authorises writes off the same cache so a just-opened lead reads as unauthorised. **Cache-on-write, not a GHL fall-through** — `39` §7 rejected the fall-through on the 100/10s budget and `verification` Correction 3 upholds that. No `PipelineScope` refactor before Unit 44 | **M** | — | SALES/MARKETING | EvalOS, GHL | **P0** |
| `POST /api/webhooks/ghl/{endpointToken}` (`InboundWebhookController.java:39`) | Token-in-path, dedupe, archive, 5xx on failure | A dropped `opportunity.won` is recoverable | GHL's Custom Webhook action **does not redeliver**, and EvalOS's own redelivery left with Unit 18 — so `05` step 6 and `05b` criterion 4 are factually void. One blip during a deploy = a paid case silently never created. The route is right; what is missing sits behind it (E) | **K** (route) + **A** (replay) | `JobLock`, `SweepRegistrationTest`, `WebhookRouter` | system | EvalOS, GHL | **P0** |

### A.2 Portal chain (`PortalSecurityConfig`, order 1 — `X-Portal-Token`, no roles) — 25 routes, 3 controllers

| Controller | Routes | Verdict |
|---|---|---|
| `ClientAuthController` | 4 | **KEEP** — `identify`/`sign-in`/`forgot-password`/`set-password` are the best-argued code in the tree. `identify`'s enumeration oracle is a written decision (S4), not an oversight; it compounds with S1 (E) |
| `ClientPortalController` | 12 | **KEEP 7** (`invoices`, `meetings`, `cases`, `cases/{id}`, `cases/{id}/approve`, `cases/{id}/request-revisions`, `documents/{id}/url` as the party variant) — 5 carry decisions below |
| `ExpertPortalController` | 9 | **KEEP the backend entirely.** Every gap is a missing *screen*, plus one payload change |

**Detail rows — portal chain**

| Route | What exists | What the target expects | The difference | Dec | Depends on | Roles | Systems | P |
|---|---|---|---|---|---|---|---|---|
| `POST /api/portal/client/documents` (`ClientPortalController.java:191` → `PortalCaseService.java:345`) | `cases.findById(principal.caseId())` — the one method on the class that skips its own `authorized(principal)` | Same authorisation as every sibling | A Unit 42 sign-in mints a party/account-scoped token whose `caseId` is **null by construction**; `findById(null)` throws ⇒ **500 on every client upload**, which is the whole point of the documents screen. It also skips the brand check `byId` applies — a latent cross-brand write the moment a path variable arrives. Survived because `ClientPortalTest:300` stubs the service and `PortalCaseServiceTest` has no upload test | **M** (1 line + the test that would have caught it) | — | Client | EvalOS, portal, S3 | **P0** |
| ” — the S3 key | Requires a live `ghl_contact_id` (`requireState`) | A key that outlives the CRM | Every client created after the 2026-09-11 cutover, and every client Unit 43's funnel is about to create, has no live contact id. **Adopt `43` §5's key verbatim: `{brandId}/client/{clientAccountId}/{documentId}`** — that spec already argued it against both `ghl_contact_id` and `case_id`, because funnel documents are uploaded a step before either exists. Do not reopen it as "`case_id` or `client_account.id`" | **RD** | `43` §5 | Client | S3, EvalOS | **P0** |
| `GET /api/portal/client/documents`, `GET …/documents/{documentId}/url`, `POST …/documents` | No `{caseId}` variant on any of the three | A repeat client picks which case they mean | `authorized(principal)` throws 409 `SAY_WHICH_CASE` whenever a party token resolves to ≠1 case. `approve` and `request-revisions` were given `/cases/{caseId}/…` variants for exactly this reason (documented at `ClientPortalController.java:163-179`); the three document routes were forgotten. `V15` explicitly permits two open cases per contact. A self-signup client with zero cases gets "this link has no cases behind it" | **A** (3 routes) | the C1 fix | Client | EvalOS, portal, S3 | **P0** |
| `GET /api/portal/client/case`, `POST /api/portal/client/approve`, `POST /api/portal/client/request-revisions` | The tokenless trio, kept for a **case-scoped** client link | Either a caller or a deletion | Unit 42 deleted the client link system; `PortalAccessService.mintForClientAccount` is now the only CLIENT mint and is **always party/account-scoped** (verified `service/PortalAccessService.java:256-270`), so no case-scoped CLIENT token can be issued any more. Their only client-side caller, `draftService.readDraft`, has zero importers (frontend F10). They still answer correctly for a single-case party token, so this is redundancy, not breakage — **verify no live token predates Unit 42 before deleting** | **RM** (or K with a written reason) | token audit | Client | EvalOS, portal | P2 |
| `GET /api/portal/client/invoices`, `/meetings` | Fetched from GHL by a contact id that no longer exists; an upstream failure renders as an empty list | "We can't reach your billing records right now" | Two screens shipped last week are **live and actively misleading** to every pre-cutover client. `41` §9's `anUpstreamFaultIsNotBlamedOnTheScope` forbids reusing the missing-scope hint for a generic outage, so this needs **a third string**, not a widened guard. (They return `GhlInvoiceClient.ClientInvoice` / `ClientMeeting` directly — narrow projections, fine, KEEP) | **M** | C7/C8 of the cutover | Client | GHL, portal | **P0** |
| `GET /api/portal/client/documents/{documentId}/url` (delivery) | `PortalCaseService` filters `SIGNED_LETTER` out of the client's documents *"because delivery is a decision nobody has taken"* | The client downloads what they paid for | **The journey has no end.** Take the decision; the presigned-URL pattern is already proven twice | **M** + **A** (screen, C) | a product decision | Client | S3, portal | P1 |
| `GET /api/portal/expert/case` → `ExpertCaseView.evidence` | `readonly string[]` — an unlinked list of filenames | `{id,label}` + a presigned URL per item | The expert is asked to attest to a professional opinion able to see only the **names** of the documents it rests on. **Quality risk on a signed legal instrument.** Reuse the presigned pattern proven twice | **M** (+1 route, E) | — | Expert | S3, portal | P1 |
| `GET /api/portal/expert/cases`, `GET /api/portal/expert/payouts` (`ExpertPortalController.java:75,99`) | Built, whitelisted, tested — and **called by nothing** (verified: `expert/src` references `/expert/case` only) | Two screens | An expert with three assignments holds three links and can see none of the others. `34` D6 decided payouts ("rows only, never a payment detail") on 2026-09-04 and it was never built. **Zero backend work.** `payment_detail` keeps its property of having no read path for anyone (invariant 4) — the expert portal must not become the first exception | **K** (routes) + **A** (screens, D) | expert accounts | Expert | portal | P1 |

### A.3 Routes that must be ADDED

Consolidated in §E with the screens. Named here so the API section is not a description of the
past only: three per-case client document routes, one expert document-URL route, four expert
auth routes, `GET /api/admin/ghl/status` (widening to `/api/admin/sync/status` at Unit 45), a
brand-scoped search endpoint, the unified-timeline read, the Sales case-status read, four team
administration routes, and Unit 50's expert-application set.

---

## B. Screens — internal EvalOS app (`frontend/`, 24 routed paths)

Routing `App.tsx:42-183`; nav and route allow-list are **one table** (`features/shell/navigation.ts`)
with a `readsGhlLocation` flag — `audit-frontend.md` names this as work that should not be
touched. Every routed screen calls the real backend; **mock count is 0**.

| Feature area | Paths | Verdict |
|---|---|---|
| Auth & chrome | `/login`, `/` redirect, `*`, 403 | **KEEP** — except the two hardcoded `/dashboard` back-links (detail) |
| Production queues | `/inbox`, `/drafts`, `/my-drafts`, `/pm-notes`, `/expert-assignment`, `/delivery` | **KEEP** — all real-backed, all with loading/empty/error. Missing retry on 5 of 6 and a hand-rolled skeleton each; both are the systemic patterns in B.5, not per-screen defects |
| Board | `/board`, `/my-cases` | **KEEP** — `QUICK_ACTIONS` + `admits(action, role)` is the strongest pattern in the app |
| Checklists | `/checklists` | **KEEP** |
| Experts & payouts | `/experts`, `/payouts` | **KEEP** — `/experts` is the only paginated screen and the only row-click-opens-a-sheet implementation |
| Marketing/Sales funnels | `/marketing/google-ads`, `/marketing/email`, `/sales/pipeline` | **KEEP** — `00d` R2 overrules the Product report's "retire these". The defect is the name-matching lookup, which `00c` §6.7 kills; retiring built, working, correctly-scoped screens to avoid fixing a lookup is a bad trade. Access widens with A.1's `MarketingController` row |
| Case detail | `/cases/:id` + 11 panels | **KEEP** the screen; **REFACTOR** the fetch shape (detail) |
| Payouts detail | `/payouts/experts/:expertId` | **KEEP** |
| Admin | `/admin/jobs` | **KEEP** + host the sync-status panel (E); token-repair in B.5 |

**Detail rows — internal app**

| Screen | What exists | What the target expects | The difference | Dec | Depends on | Roles | Systems | P |
|---|---|---|---|---|---|---|---|---|
| `/opportunities/board` (`OpportunityBoardPage.tsx:33-37`) | Passes `[reloads]` as `useMetrics` **deps**; the hook clears `data` on any deps change | The hook's own `reload()`, which does not clear — returned, and ignored here | A salesperson adding a note loses the whole board, their scroll position and every open card. This is the exact behaviour `useMetrics:28-37` documents itself as having been fixed to avoid. It is also the **worst-covered screen in the app** on states (no loading of its own, no error path) and Sales/Marketing's *only* screen | **M** (one line) + **M** (states) | — | SALES, MARKETING | EvalOS | **P0** |
| `DealActions.tsx:109-127` (on that board) | `won` / `lost` / `abandoned` as three adjacent equal-weight buttons, one unguarded click each; `won` creates a case | A consequence-stating confirmation, as `DraftReview` and `DeliveryQueuePage` already do | An unused `ConfirmDialog` sits in `shared/` while the copy on these buttons already says what they cannot undo. Separately, `DealActions.tsx:63-69` fetches `/sales/calendars` **once per opened card** against a 100-req/10s budget — hoist to the page | **A** (confirm) + **M** (hoist) | `ConfirmDialog` | SALES | EvalOS, GHL | **P0** |
| `/dashboard` (`App.tsx:132`) | The one in-shell route **not** wrapped in `RoleRoute` | Same gate as the other 17 | `navigation.ts:66` gates it to `PRODUCTION_ROLES`, so `mayReach('SALES','/dashboard')` is false — but a SALES user can deep-link and be rendered their opportunity board. Harmless only because the `Record<Role, ReactNode>` map is exhaustive | **M** | — | all | EvalOS | P2 |
| 5 role dashboards (`RevenueDashboard`, `PmDashboard`, `CoordinatorDashboard`, `CaseManagerDashboard`, `ExpertNetworkDashboard`) | Good `CardState` contract; **`PmDashboard` is the only one using `to`**; 4 `unavailable` tiles blame units that are built or deleted | KPI → filtered queue everywhere; GM/BM get an operational row (at-risk, blocked, bottleneck by stage, delivered this period) | The two most senior roles land on a finance summary with **no clickable tile**, while every figure they need is computed one directory over for the PM. `card.tsx` already makes click-through structural — this is wiring, not design. `unavailable` is being used as a tombstone | **M** | — | GM, BM, PM, PC, CM, ENM | EvalOS | P1 |
| `PortalLinkLedger.tsx:113` (tile on 3 dashboards) | Renders a `CLIENT` audience branch; header argues the tile exists because *"EvalOS sends no mail (invariant 14)"* | Expert-only | Unit 42 retired client links and `PortalLinkController` removed `?audience`; invariant 14 was amended 2026-09-11. Drop the CLIENT branch and the audience column, rewrite the header rationale. Still the right tile — G15 (how an expert link actually reaches its expert) is open | **M** | server stops emitting CLIENT rows | PM, PC, CM | EvalOS | P1 |
| `/cases/:id` (`CaseDetail.tsx` + 11 panels) | Parent parallelises detail+timeline correctly; **child panels each fire after it resolves** — up to 7 requests, two levels deep, uncached, per visit. `DocumentList` mounts twice | One payload where the server can; caching where it cannot | React Query (B.5) removes the duplicate mount for free. No empty state; the escape link exists only in the error branch | **R** | React Query | PM, PC, CM, ENM | EvalOS | P1 |
| `TopBar.tsx:50` — the search box | A **permanently disabled input** reading "Search — not available yet" | One brand-scoped search over case code, client name, applicant name, expert name | Nothing in the product is findable by name. Sales could not use a case screen once granted one; the ENM's `/cases/:id` grant is unreachable because no list of theirs produces a link. The honest disabled control was the right interim call and is now **the top structural IA gap**. Needs the backend route first (E) | **A** | search endpoint | all | EvalOS | **P0** |
| `/brands` (`PlaceholderPage`) | A nav entry with no screen | Either a screen or no entry | Violates the rule stated one line below it in the same file: *"add the entry with the screen, never ahead of it"*. The only remaining unbuilt nav entry | **RM** (entry) until **A** (screen) | — | GM | EvalOS | P2 |
| `/payouts/payments/:paymentId` (`PaymentDetail.tsx:155-166`) | "The expert confirmed receipt" — terminal, one click, confirms every draft at once; no empty state; no edit affordance | Confirmation on a terminal money action; an edit path (A.1) | Money actions are the one place the codebase otherwise protects properly (`attachToPayment` puts the precondition in the `WHERE`) | **A** (confirm + edit) | `ConfirmDialog` | PAYOUTS roles | EvalOS | P1 |
| `/admin/jobs` (`JobRunsPage.tsx:31-46`) | Manual sweep trigger, unguarded; no empty state for a never-run sweep; raw `slate-*` classes | Confirmation, empty state, tokens — and the sync-status panel | `19`'s rule holds: **Run now** must go through the same session-scoped `JobLock`, not around it | **A** (confirm) + **M** (states, tokens) | — | GM | EvalOS | P1 |
| `Forbidden.tsx:17`, `NotFound.tsx:10` | Hardcode a `/dashboard` back-link | `boardPathFor(me.role)`, as `PlaceholderPage.tsx:9` already does | Since Unit 29 not every role has a dashboard — a SALES user is offered a link to a 403 | **M** | — | SALES, MARKETING | EvalOS | P2 |

**B.5 — app-wide, not per-screen** (each is one decision, listed once rather than 17 times):
**no server-state library** (28 hand-rolled `useEffect` fetchers; `/cases/board` fetched
independently by 5 screens) → **REFACTOR** to React Query keeping `emptyWhen`/`warnWhen`/`CardState`
as adapters, P1 · **no `ErrorBoundary`** while both portals have one → **ADD**, ~40 lines, P1 ·
**`strict` unset in all four tsconfigs**, making the whole `string | null` DTO vocabulary
decorative → **MODIFY**, the highest-leverage config change in the repo, P0 · **desktop-only
shell** (25 responsive utilities total; `AppShell` offsets by `--sidebar-width` unconditionally)
→ **ADD** a drawer **or** write down "internal app is desktop-only", P1 · **design-token drift**
in the 6 newest files (`opportunities/*`, `jobs/`, `PortalLinkLedger`) using raw `slate-*` →
**MODIFY**, P2 · **five patterns that exist on exactly one screen each** (KPI→queue, row→sheet,
`?view=` presets, skeletons, error+retry) → make system-wide, P1.

---

## C. Screens — Client Portal (`client-expert/client`, 13 routed paths)

Best state coverage in the product: every page has loading, empty, error **and retry**, plus
`sonner` toasts and token-aware `NO_TOKEN` handling.

| Group | Paths | Verdict |
|---|---|---|
| Auth | `/welcome`, `/signin`, `/set-password` | **KEEP** — `SignIn.tsx:41-51`'s `Record<IdentifyState, string\|null>` forcing exhaustive copy is the best auth code in the tree |
| Reading | `/invoices`, `/meetings` | **KEEP the screens** — the defect is upstream (A.2) and in two React keys (B.5 sibling: `crypto.randomUUID()` as a key, `Invoices.tsx:96`, `Meetings.tsx:84` → use the index, P2). `Meetings` refusing to `new Date()` GHL's unzoned timestamps is correct and should not be "fixed" |
| Documents | `/documents` | **KEEP** — the strongest screen in the portal; it is the *server* that is broken (A.2) |

**Detail rows — Client Portal**

| Screen | What exists | What the target expects | The difference | Dec | Depends on | Roles | Systems | P |
|---|---|---|---|---|---|---|---|---|
| `/start` (`pages/auth/Start.tsx`) | A static placeholder replying *"We can't take new evaluations through the portal just yet"* | Unit 43's intake funnel | A **dead end on the highest-intent page in the product** — `/welcome`'s primary CTA lands here, and so does anyone whose email was just refused at sign-in. **Fix the copy today** (a phone number and an email address); build Unit 43. `43` §7's ordering stands: **EvalOS's rows commit first**, GHL ids are filled by the same call or a staff retry — *"a lead with null GHL ids is not lost, it is un-pushed"* | **M** now, **RD** at U43 | U43 (and its `pipeline`/`pipeline_stage` tables) | Client | EvalOS, GHL, portal | **P0** |
| Session / sign-out (`PortalLayout`, `PortalHeader:23`, `PortalSidebar:40`) | Token in a module variable, never persisted; **no sign-out anywhere**; both chrome files still assert *"There is no session to end"* | A session that survives a refresh, and a way to end it | Unit 42 gave the portal accounts and reversed those comments; a refresh, a back-button or a reopened tab bounces the client to sign-in, and a shared machine cannot be signed out of. **Give a *password* sign-in a `sessionStorage` session and a sign-out; keep the fragment token memory-only** — different credential, different lifetime | **A** | `clearPortalToken` | Client | portal | **P0** |
| `/draft/:caseId` (`DraftReview.tsx:181-194`) | "Approve this draft" — **Handoff B**, sends the letter to an expert to sign — one click. The copy itself says *"It cannot be undone from here"* | A consequence-stating confirmation | The copy warns; the UI does not gate. `shared/components/common/ConfirmDialog.tsx` exists with zero consumers | **A** | `ConfirmDialog` | Client | EvalOS, portal | **P0** |
| Case-row routing (`Dashboard`/`Requests` → `/draft/:caseId`) | **Every** case row links to the draft page regardless of what the action is | The row links where the action is | A client told to upload a transcript lands on *"The draft is not ready to read yet."* The server already computes `actionRequired` — have it name the destination too | **M** | — | Client | EvalOS, portal | P1 |
| `/dashboard` and `/requests` | Same query key, same `listCases` call, same 403 copy, same empty state, two near-identical `CaseRow`s. Only `needsYou`/`running` + a `Shortcuts` block differ. The sidebar lists both | One case list | ~90% duplicate. Either delete `/requests` and make `/dashboard` the list, or make `/requests` genuinely different (filters, history) | **RM** or **M** | — | Client | portal | P2 |
| (missing) delivery / completion | Nothing. `SIGNED_LETTER` is filtered out of the client's documents deliberately | The finished letter, downloadable | **A paying client cannot take delivery of the thing they bought.** The journey has no end | **A** | the delivery decision | Client | S3, portal | P1 |
| (missing) case-scoped document checklist | `/documents` is party-wide | Per-case checklist, which the backend already models | Pairs with the three per-case routes in A.2; without it a two-case client cannot tell which case wants which document | **A** | per-case doc routes | Client | portal, S3 | **P0** |

---

## D. Screens — Expert Portal (`client-expert/expert`, 2 routed paths)

**A four-read backend behind a one-route app.** Nothing here is a backend gap.

| Screen | What exists | What the target expects | The difference | Dec | Depends on | Roles | Systems | P |
|---|---|---|---|---|---|---|---|---|
| `/case` (`ExpertCasePortal.tsx`) | Real, well-stated: `ListSkeleton`, empty, error+retry, per-action toasts, an attestation checkbox gating upload | Keep — plus a confirm on Decline | `Decline` returns the case for rematching, says *"cannot be undone from here"*, and is gated only by a non-empty reason. No modal | **K** + **A** (confirm) | `ConfirmDialog` | Expert | EvalOS, portal | **P0** |
| ” — evidence list | `readonly string[]`, rendered unlinked | Openable documents | See A.2. The expert attests to an opinion while able to read only filenames | **M** | `{id,label}` payload | Expert | S3, portal | P1 |
| (missing) sign-in / set-password | **No front door.** A CM mints a link, it is shown **once**, stored nowhere, hand-pasted into an unknown channel — `PortalLinkLedger`'s own header: *"the likeliest way EvalOS breaches that SLA is a link nobody sent."* The 20h/24h clocks run regardless | Accounts on the Unit 42 pattern | The justification for the asymmetry (no mail channel) expired when invariant 14 was amended on 2026-09-11. One bcrypt column, the existing per-IP limiter, the existing `PortalTokenFilter`; `mintForParty` already accepts an expert party. **`00d` R3: P1, not P0** — it is a whole unit, not a fix, and sits behind §2. First item of Phase 2 | **A** | invariant 14 (amended), mail | Expert | EvalOS, mail, portal | P1 |
| (missing) case list | `GET /portal/expert/cases` built and whitelisted; no route renders it | A list | An expert with three assignments holds three links and can see none of the others. **Zero backend work** | **A** | expert accounts | Expert | portal | P1 |
| (missing) payouts | `GET /portal/expert/payouts` built; `34` D6 decided it 2026-09-04 — *"rows only, never a payment detail"* | Rows only | Decided, unbuilt, zero backend work. `payment_detail` keeps its property of having **no read path anywhere, for anyone** (invariant 4) | **A** | expert accounts | Expert | portal | P1 |

---

## E. Screens and routes to ADD — the build list, by phase

Phases are `00d` §11's. Route names marked *(proposed)* are not in any shipped spec.

### Phase 0 — restore service

| # | Add | Kind | Why | Dec | Depends on | Roles | Systems | P |
|---|---|---|---|---|---|---|---|---|
| 1 | `GET /api/portal/client/cases/{caseId}/documents` · `GET …/documents/{documentId}/url` · `POST …/documents` | routes | The 409 `SAY_WHICH_CASE` wall for any client with ≠1 case (A.2) | **A** | the `authorized` fix | Client | EvalOS, portal, S3 | **P0** |
| 2 | A case picker on the client's `/documents` | screen | The client half of #1 | **A** | #1 | Client | portal | **P0** |
| 3 | `GET /api/admin/ghl/status` *(proposed name — `00d` §2.3)* — configured, location id, last success, last failure + status, unprocessed webhook count with oldest timestamp, per-pipeline cache age | route | `webhook_event.error` and `scheduled_job.FAILED` are recorded and **unread**; Actuator exposes `health` only. §2.1 and §2.2 are currently invisible, and an invisible P0 cannot be triaged. Inherits `19`'s **GM-only, gated at the route *and* in the service** rule — this is cross-brand infrastructure | **A** | — | GM | EvalOS, GHL | **P0** |
| 4 | The status panel on `/admin/jobs` + a red dot in the shell | screen | Consumer of #3. Not a new admin area | **A** | #3 | GM | EvalOS | **P0** |
| 5 | `WebhookReplaySweep` (no new route) | job | Three constraints from `19`, or it ships dead: (i) `SweepRegistrationTest` needs `implements Sweep` + a `@Scheduled` tick + a matching `evalos.jobs.intervals` key **keyed by `JOB_TYPE`**; (ii) **session-scoped** `JobLock`, not `_xact_`, and **Run now** goes through the same lock; (iii) the sweep decides *when*, never *what* — re-route through the existing `WebhookRouter`. `attempts`/backoff belongs on `webhook_event`, not on a job row | **A** | `JobLock`, `WebhookRouter` | system | EvalOS, GHL | **P0** |
| 6 | A third upstream-failure string on `/invoices` and `/meetings` | copy | `41` §9 forbids reusing the missing-scope hint for a generic outage | **A** | — | Client | GHL, portal | **P0** |
| 7 | Confirmations on the four irreversible actions | UI | Client draft approval, expert decline, Sales close lost/abandoned, payment receipt | **A** | `ConfirmDialog` | Client, Expert, SALES, PAYOUTS | portal, EvalOS | **P0** |

### Phase 1 — kill the status loop

| # | Add | Kind | Why | Dec | Depends on | Roles | Systems | P |
|---|---|---|---|---|---|---|---|---|
| 8 | `GET /api/timeline?caseId=|opportunityId=` *(proposed)* — `UnifiedTimelineService` over three anchors (contact → opportunity → case), merged in timestamp order, **filtered by the reader's role at serve time** | route | `Case.ghl_opportunity_id` and `OpportunityNote.ghl_opportunity_id` hold the same value and **nobody joins them**. One service, one endpoint, **no migration, no new truth** — `00d` R1 rules for the read model now and one `note` table only when Unit 50 forces it | **A** | GM note-read widening | all | EvalOS | **P0** |
| 9 | `GET /api/sales/cases`, `GET /api/sales/cases/{id}` *(proposed)* — scoped by `Case.ghl_opportunity_id` ∈ the caller's pipeline, serving **`PortalStageProjection.forSales(stage)`**, a third projection beside `forClient`/`forExpert`, plus `stage_entered_at`, SLA status and a derived expected-completion date | route | `Role.SALES` is `Tier.PIPELINE` and `Case` has no pipeline column, so `ScopePredicate` returns a disjunction — Sales cannot read a case **structurally**, even by deep link. **Never** the draft, strategy notes, expert identity or checklist detail | **A** | S2, the projection method | SALES | EvalOS | **P0** |
| 10 | A Sales case-status screen | screen | Consumer of #9. **Do not build** a status composer, a Sales-sends-email feature, or a shared inbox — a composer creates a second, staler answer to the same question | **A** | #9 | SALES | EvalOS | **P0** |
| 11 | `GET /api/search?q=` *(proposed)* — brand-scoped over case code, client name, applicant name, expert name | route | Backs the disabled `TopBar` input (B) | **A** | — | all | EvalOS | **P0** |
| 12 | Sales widgets: today's & upcoming meetings · overdue follow-ups · untouched opportunities · unpaid invoices | widgets | The desk is **write-only** — Sales books meetings and sets follow-ups into GHL and *no screen in EvalOS reads either back*, defeating the stated purpose of Units 36–41. These are reads of GHL's own data, not an EvalOS reminder engine, so `40` §5 is not violated | **A** | GHL reads | SALES | GHL, EvalOS | P1 |
| 13 | Marketing widgets: untouched new leads · lead volume by source (+ funnel access, A.1) | widgets | The role has no measure of its own output | **A** | invariant-1 amendment | MARKETING | GHL, EvalOS | P1 |
| 14 | The intake note on Handoff A (~4 lines on `customData` onto the `CREATED` audit snapshot) | payload | `OpportunityWon` drops everything Sales and the client already said; the PM opens a paid case with a name and a number and re-interviews. Unit 23 §4 specced a `notes` field, never built | **A** | — | PM, SALES | GHL, EvalOS | **P0** |
| 15 | "Your account is ready" invitations to the `V45`-seeded client accounts | operational | Unit 42 shipped accounts with **no password** and there is no invitation mechanism. A `sendSetPassword` send is already authentication mail under the amended invariant 14 | **A** | mail | Client | mail, portal | **P0** |

### Phase 2 — the expert

16 · Four expert auth routes on the Unit 42 pattern — `POST /api/portal/expert/auth/identify`,
`/sign-in`, `/forgot-password`, `/set-password` *(proposed; mirror `ClientAuthController`)* —
**A**, depends on invariant 14 + mail, Expert, EvalOS/mail/portal, **P1**.
17 · Expert sign-in + set-password screens — **A**, P1. 18 · Expert case list and payouts
screens — **A**, zero backend, P1. 19 · `GET /api/portal/expert/cases/{caseId}/documents/{documentId}/url`
*(proposed)* + `{id,label}` evidence — **A**/**M**, P1. 20 · The four-message outbound decision
(checklist+link, draft ready, expert signing link, delivered), **in writing, as a unit with an
invariant-14 amendment** — **T6 alone justifies it**: an expert who never receives a link cannot
sign while a 24h clock runs. Not the chases. P1.

### Phase 3 — foundations

21 · Per-account sign-in failure counter + lock-until (S1: the 60/min budget keys on
`request.getRemoteAddr()` — *a caller, not an account* — so one account is attackable at
(N sources × 60)/min, with BCrypt the only real brake) — **A**, Client, P1.
22 · `contact.created` / `contact.updated` handlers — **A**, P1, **but not through
`CaseIntakeService`**: `05b` forbids it and `DomainInvariantsTest` scans the classpath for who
may inject it, permitting exactly one class. **Extract `syncContact` into a
`ContactSnapshotService` first** and have both handlers use it. Two caveats: it is *not* a
prerequisite for resolving portal signups (`43` resolves through `identify` + GHL's own
`contacts/upsert`), and the GHL workflow that fires these two events **does not exist in
`WY6bW2xUCI8Tz8gw7aLJ` either** — a C1-family operational item travels with it.
23 · `brand.ghl_location_id` + per-brand `GhlHttp` + per-location pacer — **A**, P1. This is
§5.3's cheaper half; **Unit 25 as specced is not buildable** (one read scope against the six now
needed, deletes the PIT six live features depend on, and waits on a GHL Marketplace app nobody
has created). It must be re-specced before it is sequenced anywhere. 24 · `@Version` on
`ScopedEntity` + an `OptimisticLockingFailureException` → 409 handler — **A**, P1.
25 · Composite brand FKs — **M**, P1.

### Phase 4 — the mirror (`00c` Units 44-48, amended)

26 · `GET /api/admin/sync/status` — the Phase-0 route widened with `outbox`, `delta` and `audit`
blocks; red dot when `deadLettered > 0 || conflicted > 0 || unprocessed > 0 || halted`. Build it
**at** Unit 45, not after. 27 · Outbox, per-field ownership, `sync_drift`, paged diff audit,
error classification. **Note for §E ordering — RESOLVED 2026-09-15:** `pipeline` and `pipeline_stage` are **Unit 44's**.
They were argued to be 43's because *"move it to the hot stage"* requires knowing which stage is
hot; 43 shipped moving nothing to any stage — GHL's automation places the deal — and resolves the
pipeline id live. Give §6.7's `purpose` enum an `INTAKE` member anyway, since the intake pipeline
is a real pipeline with a real job. A mirrored **Case Delivery**
pipeline must not grow a lifecycle: `43` §9 and invariant 8 forbid a second case state machine;
it is mirrored for reporting and hand-off visibility only.

### Phase 5 — ENM (Unit 50, **spec first** — it reverses two written refusals)

28 · `expert_application` + 6 stages (Sourced → Contacted → Qualified → Agreement Sent →
Onboarding → Active, + Rejected/Lapsed), stage history on the generic `audit_event` with
`object_type='EXPERT_APPLICATION'` — routes and a six-stage Kanban + an application detail
screen. 29 · **Open-offers queue** — the queue the two headline ENM KPIs already describe, and
the ENM's only board (their `/cases/:id` grant is currently unreachable). 30 · Workload tab on
the expert sheet — *assembly only*, every figure exists. 31 · Append-only expert notes ·
dated availability windows · a relationship owner · a coverage-gap → "recruit for this field"
action · **cost per signed letter, by expert and by field** (arithmetic over existing rows).
**Do not build an outreach log — the stage move *is* the outreach record.**
32 · A `NotificationType` telling the ENM a case needs an expert (no new route).
All **A**, ENM, EvalOS, **P1**.

### Phase 6 — administration and consistency

33 · Team administration: `POST /api/team-members`, `PATCH /api/team-members/{id}` *(proposed)*
+ a screen over them and the existing `PUT /{id}/ghl-pipeline` — **onboarding a salesperson
currently requires a database write**, a hard ceiling for a pivot whose point is six new seats.
**A**, GM/BM, P1. 34 · A `/brands` screen, or the nav entry removed until one exists. **A**/**RM**,
GM, P2. 35 · B.5's app-wide items.

---

## F. Deletion list

Everything the audits verified as having zero importers/callers today. Frontend list verified by
grep across `client/src`, `expert/src`, `shared/src`, `frontend/src`; backend list unreferenced in
`src/main` and `src/test`.

### F.1 Frontend — 20 files

| Path | Reason |
|---|---|
| `client-expert/shared/src/schemas/auth.ts` | **Delete first (P0).** Declares its own `passwordRules`, a divergent duplicate of the live `client/src/schemas/intake.ts:3`, while `SetPassword.tsx:18-20` claims in a comment that `passwordRules` *"is the one place the strength rule is written"* — false today. `loginSchema` is dead too |
| `client-expert/shared/src/constants/upload.ts` | Contradicts `shared/src/lib/portal.ts` on the upload cap (10 vs 15) and lists `DOC`/`DOCX` as accepted, which `Documents.tsx:42` does not |
| `client-expert/shared/src/components/ui/{avatar,date-picker,dropdown-menu,pagination,popover,radio-group,select,separator,switch,tabs,tooltip}.tsx` (11) | Zero importers — 11 of `shared/`'s 23 UI primitives, which is what makes *"is `shared/` actually shared?"* hard to answer |
| `client-expert/shared/src/hooks/useMediaQuery.ts` | Zero importers |
| `client-expert/shared/src/constants/storage.ts` | `ie_portal.mockAccounts` etc. — mock-era |
| `client-expert/shared/src/utils/storage.ts` | Zero importers |
| `client-expert/shared/src/mock/mockDelay.ts` | Last mock module in `shared/`; its only "hit" is a stale sentence in `expertPortalService.ts:8` |
| `client-expert/expert/src/mock/expertMockData.ts` | `DEMO_EXPERT`, `INITIAL_EXPERT_CASES` — no routed screen imports it |
| `client-expert/expert/src/types/expert.ts` | Its only importer is the file above |
| `client-expert/client/src/constants/countries.ts` | Zero importers |

Partial deletions in files that stay: `client/src/schemas/intake.ts:11-31` (`aboutYouSchema`,
`AboutYouFormValues` — only `passwordRules` is imported) · `shared/src/utils/formatters.ts:23-84`
(`formatDateTime`, `formatCurrency`, `formatFileSize`, `getInitials`, `relativeTime`; only
`formatDate` and `formatDateShort` are live).

Then from `client-expert/package.json`, each backing only deleted code — **re-verify against the
final tree**: `@radix-ui/react-{avatar,dropdown-menu,radio-group,select,separator,switch,tabs,tooltip,popover}`,
`date-fns`, `recharts`, `react-hook-form`, `@hookform/resolvers` (the last four have **zero**
references anywhere in `client/src`, `expert/src` or `shared/src`).

**Keep, despite looking deletable:** `shared/src/components/common/ConfirmDialog.tsx` — it is the
fix for four P0 confirmations (E #7), not dead weight · `client/src/pages/auth/Start.tsx` —
a deliberate, self-documenting Unit 43 placeholder; fix its copy (C), delete it at Unit 43.

### F.2 Backend

| Path | Reason |
|---|---|
| `service/OpportunityCache.java` — `evict(String)` (`:224-227`) + `repository/CachedOpportunityRepository.java:67-69` | Its own javadoc says *"Nothing calls this yet."* Delete both, or wire the inbound webhook to it — it becomes moot when `CachedOpportunity` is removed at Unit 44 |
| `domain/ClientAccount.java` — `linkGhlContact`, `set/getFirstName`, `set/getLastName`, `set/getPhone`, `set/getCountry`, `getLastSignInAt` (10 accessors, `:510-552`) | Unit 43 scaffolding. **Keep the fields and the `V43` columns** — Unit 43 needs them; delete only the accessors |
| `domain/ClientCredentialToken.java` — `getPurpose` (`:621`), `getExpiresAt` (`:625`), `getUsedAt` (`:629`) | `isUsable` is the only thing read |
| `domain/Expert.java` — the `avgResponseHours` field + getter (`:86-87`, `:376`) and `totalPaymentsPending` field + getter (`:111-112`, `:415`) | Mapped, public, and **written by nothing**; four services document them as permanently dead. `getTotalPaymentsPending()` returns a hardcoded zero **and looks like an answer** |
| Migration: drop `expert.avg_response_hours`, `expert.total_payments_pending`, `expert.current_active_count`, `expert.total_cases_completed` | The last two were never even mapped |
| `integration/GhlHttp.delete` | A write capability with no caller, on the one client that must not grow one silently (`audit-integration` F14). Delete it, or the next reviewer must re-derive that it is unused |
| `navigation.ts` — the `/brands` entry | Until the screen exists (B) |

**Do not delete, despite looking unused:** `ScopePredicate.Fields.brandAndPipeline` (used by
`OpportunityNoteRepository`) · `PortalAccessService.MintedLink` (expert path) · every `SCOPE`
constant (`DomainInvariantsTest` requires them) · `PortalAccessService.mintPartyForExpert` /
`statusForExpert` (live callers in `PortalLinkController`).

**Nothing is checked into git that should not be** — `git ls-files` finds no `dist/`, `.tmp/`
or `node_modules`; the on-disk build outputs are covered by `.gitignore`.

---

## G. Coverage statement

**Counted, and one correction.** `audit-backend.md`'s inventory table is complete and correct;
its **summary line is not**. The table lists 129 route rows; the code confirms **129 handler
methods across 28 controller files** (104 staff-chain, 25 portal-chain), not "105 routes across
27 controllers". This appendix is against 129/28. No route is missing from the inventory — only
the count sentence is wrong, and it should be corrected in `audit-backend.md` itself.

**Screens: 39 routed paths across three apps** — internal `frontend/` 24 (18 nav paths, 3
deep-link detail paths, `/login`, an index redirect, a catch-all), Client Portal 13 (11 screens
+ a redirect + a catch-all), Expert Portal 2. `/dashboard` fans out to 5 role dashboards behind
one path. **Mock count across all three apps: 0 routed screens.**

**What carries a decision other than KEEP.**

| | Total | Non-KEEP | Collapsed as KEEP |
|---|---|---|---|
| API routes (existing) | 129 | **23** (18%) | 106 |
| Routes to ADD | — | **15 named** + Unit 50's expert-application set (unsized until specced) | — |
| Screens (existing, routed paths) | 39 | **14** (36%) | 25 |
| Screens/widgets to ADD | — | **18 named** + Unit 50's four ENM surfaces | — |

Counting notes, so the numbers can be checked: the 23 non-KEEP routes are 15 staff-chain
(`refund/request`, 2 opportunity-note, 3 marketing-funnel, `jobs/runs`, and the 8 write routes
that must cache GHL's response) and 8 portal-chain (`POST /client/documents`, `/client/invoices`,
`/client/meetings`, the delivery filter on `documents/{id}/url`, the 3 tokenless client routes,
and the expert evidence payload). Cache-on-write is **one decision applied to 8 routes** — count
it as one and the total is 16 (12%). Chrome-level items (`TopBar`'s search, sign-out,
`PortalLinkLedger`, the app-wide B.5 list) are decisions but not routed paths, and are excluded
from the screen count rather than inflating it.

The asymmetry is the finding, and it matches all six reports: **the backend is largely right and
the frontend is where the product is missing.** 82% of routes need no change; 36% of screens do,
and the biggest single category of work is screens over endpoints that already exist and are
already tested — the expert's case list and payouts, the Sales case-status read's consumer, the
team-administration screen over `TeamMemberController`, and the sync-status panel.

**What I could not judge, and why.**

1. **Whether any live case-scoped CLIENT portal token predates Unit 42.** The three tokenless
   client routes are recommended for removal on the grounds that no such token can be *minted*
   any more (verified in `PortalAccessService`) and their only client caller is dead (F10). A
   token minted before Unit 42 and still inside its TTL would break. **Query `portal_access` for
   `audience='CLIENT' AND case_id IS NOT NULL AND retired_at IS NULL` before deleting.**
2. **Whether the 5 GHL-facing GM routes return anything in the new location.** `GET /ghl/pipelines`
   and the three `/marketing/*-pipeline` reads match pipelines **by name** against
   `WY6bW2xUCI8Tz8gw7aLJ`, which is fresh. Whether the names were recreated is an operational
   fact nobody has checked; a mismatch renders as an empty screen, not an error. Their decision
   (KEEP, per R2) does not change either way, but their *current working state* is unknown.
3. **Actual usage of any screen.** No telemetry exists in either tree. "Unused" claims here are
   all **static** (zero importers, zero callers), never behavioural. R2 explicitly leaves the
   three funnel screens open to revisiting *"if the GM says they are unused"* — that is a
   question for the GM, not for the code.
4. **Priorities within P1.** `00d` §11's phases give a *sequence*; within a phase this appendix
   inherits the reports' relative ordering and does not independently re-rank. Where two
   decisions touch the same file, the phase governs, not the row order.
5. **Per-route performance.** Eight read models call `CaseLifecycleService.list(null,null,null)`
   and scan the whole brand's cases — including `GET /api/metrics/nav`, on every page load. At
   50-100 cases/brand/month this is fine for a year or two and then is not. No route carries a
   performance decision here because the fix is in the service layer, not the route; it is
   `00d` §5's "five performance items".
6. **Nothing was judged against a running system.** Everything above is static reading of
   `development` @ `dee45c6` plus the six reports. Serena MCP was down for this pass, as it was
   for the six audits; the build was green at 966 tests when `audit-backend.md` ran it.
