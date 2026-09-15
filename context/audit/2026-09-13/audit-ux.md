# EvalOS — Independent UX Audit

**Auditor:** Senior UX Designer · **Date:** 2026-09-13 · **Branch:** `development` @ `dee45c6`

Every claim is grounded in a file path or a spec section. Where code and spec disagree, the
code is treated as the fact and the divergence is itself reported as a finding.

---

## Executive framing: the one thing that did not get designed

The brief names the operational pain precisely: *a client asks Sales for case status →
Sales messages Production → Production replies → Sales relays it back.*

**That loop is not closed anywhere in the codebase, and the system is currently built so
that it cannot close.**

- `frontend/src/features/shell/navigation.ts` — `PARAMETERIZED` grants `/cases/:id` to
  `PRODUCTION_ROLES`, a constant that explicitly excludes `SALES` and `MARKETING` ("Unit 36
  added two: `SALES` and `MARKETING` … are assigned no case, so they are absent here"). A
  salesperson cannot open a case at any URL.
- `context/specs/40-sales-desk.md` §5: *"No case transitions. Sales acts on opportunities; a
  case that exists belongs to PM/PC/CM."* The only post-win signal Sales gets is a transient
  sentence in `frontend/src/features/opportunities/DealActions.tsx`: *"Marked won in GHL. The
  case appears here once GHL confirms it."* After that the deal leaves the board and Sales is
  blind.
- `frontend/src/features/shell/TopBar.tsx` — global search is a **disabled input** with the
  placeholder `"Search — not available yet"`. There is no entity lookup of any kind.

So a salesperson taking the exact phone call this product was commissioned to fix has: no
case screen, no case search, and no status feed. They must message Production. **The pain is
preserved in the architecture, not designed away.**

The remedy is unusually cheap, which is why this is F1 and not a lament.
`backend/src/main/java/com/ie/evalos/service/PortalStageProjection.java` already converts the
twelve internal stages into a safe, plain-English phrase plus an `actionRequired` boolean
(`forClient(Stage)`, `forExpert(Stage)`), and `Case.ghlOpportunityId`
(`backend/src/main/java/com/ie/evalos/domain/Case.java:224`) already stores the join back to
the opportunity Sales worked. A `forSales(Stage)` plus one read is the whole of it. Every
objection `41-client-portal-invoices.md` §10 raises about leaking staff stage names is
already answered by the projection that exists.

---

## Role-by-role workspace assessment

Screen inventory derives from `frontend/src/features/shell/navigation.ts` (`NAV_ITEMS`,
which is simultaneously the nav and the router allow-list) cross-referenced against the
`SCREENS` map in `frontend/src/App.tsx`. An entry in `NAV_ITEMS` with no key in `SCREENS`
renders `PlaceholderPage` — a nav item with nothing behind it.

### 1. GM (all brands)

**Reachable:** `/dashboard` (`RevenueDashboard`), `/marketing/google-ads`, `/marketing/email`,
`/sales/pipeline` (all three are one component, `MarketingPipelinePage`),
`/opportunities/board`, `/board`, `/delivery`, `/experts`, `/payouts`, `/brands`,
`/admin/jobs`, plus `/cases/:id`, `/payouts/experts/:expertId`, `/payouts/payments/:paymentId`.
**11 nav entries — the widest in the app.**

**Verdict: the widest nav, the least useful landing screen.**

- **`/brands` is a dead nav item.** Present in `NAV_ITEMS` with `becomes: 'Brand
  administration'`, absent from `App.tsx`'s `SCREENS`, so it renders `PlaceholderPage`.
  `navigation.ts` states the rule it is breaking in its own comments — *"add the entry with
  the screen, never ahead of it"* — one entry below the violation.
- **The dashboard answers only "how much money".** `RevenueDashboard.tsx` is four money KPIs
  (open liability, collected, recognised, refunded), a by-brand table, and a `Money out` card
  frozen at `{ kind: 'unavailable', blockedBy: 'Unit 16' }`. **No click-through on any tile**
  (zero `to=` props in the file) and nothing operational: no at-risk count, no bottleneck, no
  unassigned, no overdue, no team health — despite `PmDashboard`, `CoordinatorDashboard` and
  `ExpertNetworkDashboard` computing exactly those numbers one directory over.
- **"What needs me today" has no answer for the GM.** Their day starts on a finance summary
  they cannot act on.
- **Clicks to the most common task** (find a case in trouble): dashboard → `/board` → visually
  scan eight columns → open card. **3+ clicks plus a manual scan**, because there is no search
  and no at-risk drill-through.
- **Stale `unavailable`:** `Money out` blames Unit 16, which is built — `PayoutController.java`,
  `PayoutBatch.tsx`, `ExpertPayouts.tsx`, `PaymentDetail.tsx` all exist and `/payouts` is in
  the GM's own nav. The GM is told data is missing while a nav entry two rows down serves it.

### 2. Brand Manager (one brand)

**Reachable:** `/dashboard` (same `RevenueDashboard`), `/board`, `/checklists`, `/experts`,
`/payouts`, `/cases/:id`, payout details. **5 nav entries.**

**Verdict: an oversight role with no oversight screen.**

- Shares the GM's money-only dashboard minus the by-brand table, which renders
  `emptyWhen(..., 'One brand in scope.')`. Their dashboard is therefore four numbers and two
  empty cards.
- `RoleDashboard.tsx` correctly notes they are *not* the GM with a filter (`SEES_STRATEGY_NOTES`
  excludes them), but the screen gives them nothing their brand actually needs: no SLA breach
  count, no stalled-stage view, no per-team comparison.
- **They own `/checklists` but have no `/delivery`** (`roles: ['GM', 'PROJECT_COORDINATOR']`),
  so they can watch documents arrive and cannot see the other end of the pipe.
- **Missing entirely:** any brand-level production health view. The one role whose entire job
  is one brand's performance has no screen that shows it.

### 3. Project Manager (a team's cases)

**Reachable:** `/dashboard` (`PmDashboard`), `/board`, `/inbox`, `/drafts`,
`/expert-assignment`, `/experts`, `/cases/:id`. **6 nav entries.**

**Verdict: the best-served role in the product, and the model the others should copy.**

- `PmDashboard.tsx` is the only dashboard whose KPI tiles drill through:
  `to="/inbox?view=delivered"`, `to="/inbox?view=at-risk"`, `to="/inbox?view=unassigned"`.
  `InboxPage.tsx` reads that `view` param via `useSearchParams`, so the tile → filtered-queue
  loop genuinely works. **This is the pattern the whole app needs and only this role has it.**
- Nav badges reinforce it: `navBadges.ts` maps `/inbox → unassigned` and
  `/drafts → draftsAwaitingReview`, with `isUrgentBadge` reddening only counts whose healthy
  value is zero. Well judged — a genuinely good piece of design.
- **Gaps:** `Expert response time` is a permanently `unavailable` card blaming Unit 15
  (`PmDashboard.tsx:204`) — Unit 15 is BUILT (`progress-tracker.md:1314`;
  `ExpertPortalController.java` ships). `/expert-assignment` carries **no nav badge** even
  though it is where overdue signatures surface, so the PM must open it to learn there is
  anything in it.

### 4. Project Coordinator

**Reachable:** `/dashboard` (`CoordinatorDashboard`), `/board`, `/delivery`, `/checklists`,
`/cases/:id`. **4 nav entries.**

**Verdict: coherent, with one permanently broken tile.**

- Two of four KPIs drill through (`to="/checklists"`, `to="/delivery"`). `With the client` is
  a genuinely actionable card (awaiting / never opened / over 48h).
- **`Review requests` renders `{ kind: 'unavailable', blockedBy: 'Unit 18' }`**
  (`CoordinatorDashboard.tsx:94`). **Unit 18 was REMOVED on 2026-09-02**
  (`00-build-plan.md:377` — *"Unit 18 — REMOVED … Never built"*). The Coordinator's dashboard
  permanently reads *"Not available until Unit 18 ships the data behind it"* about a unit that
  will never ship. This is the `unavailable` state used as a tombstone, which is exactly what
  `ui-context.md` says it is not for.
- **Missing:** a chase queue distinct from the checklist board — who was chased, when, and who
  is overdue for a second chase. `17-dashboards.md` lists "Pending doc chases" as built, but it
  is a status column on `/checklists`, not a queue ordered by chase age.

### 5. Case Manager

**Reachable:** `/dashboard` (`CaseManagerDashboard`), `/pm-notes`, `/my-drafts`, `/my-cases`,
`/cases/:id`. **4 nav entries.**

**Verdict: good content, zero drill-through, one real duplication.**

- `CaseManagerDashboard.tsx` has the richest card set of any role (priority queue, draft status,
  expert signing, client feedback) and is the **only dashboard with no `to=` on any KPI tile**.
  `Due now`, `Completed on time`, `Draft revision rate` and `Client revision requests` are all
  inert numbers. Priority-queue rows link to `/cases/:id`; the tiles do not.
- **`/pm-notes` and `/my-drafts` are two nav entries over the same population.** `navigation.ts`
  argues the exception at length ("a different question rather than a different view"), but
  `/my-drafts`'s own `becomes` string is *"PM strategy notes and your draft history"* — i.e. it
  already contains what `/pm-notes` exists to show. The file's own `/cases`-beside-`/board` rule
  applies and was waived.
- `/my-cases` correctly reuses `BoardView` rather than forking a screen. Good.
- **Missing:** a returned-draft badge. `navBadges.ts` has no entry for `/my-drafts`, so a draft
  the PM bounced produces no rail signal — the CM must open the screen to discover their work
  was rejected.

### 6. Expert Network Manager (ENM)

**Reachable:** `/dashboard` (`ExpertNetworkDashboard`), `/experts`, `/payouts`, `/cases/:id`,
payout details. **3 nav entries — the thinnest of any internal role.**

**Verdict: a genuinely good dashboard stranded in a two-screen workspace.**

- `ExpertNetworkDashboard.tsx` is the strongest widget set in the app: five KPIs plus
  availability board, low quality scores, coverage gaps, and declining-twice. Empty copy is
  operational and specific (*"Every field has five or more available experts."*). Only **one**
  tile drills through (`to="/experts"`), so the four diagnostic lists are read-only — clicking
  an expert in `Coverage gaps` does nothing.
- **The ENM has no board and no queue.** `boardPathFor()` in `navigation.ts` documents this
  explicitly: *"a role that can reach neither (the Expert Network Manager today)"*. Their
  `/cases/:id` grant exists but they can only arrive by a link somebody pasted — no list in
  their workspace produces one.
- **Two permanently stale `unavailable` cards** (`ExpertNetworkDashboard.tsx:197-198`):
  `Response time` blames Unit 15 (built) and `Payments` blames Unit 16 (built, and in the ENM's
  own nav as `/payouts`).
- **Missing entirely:** an offers/outreach queue. `Offer turnaround` and `Acceptance rate` are
  computed as KPIs, but no screen lists the open offers those numbers describe — so the ENM can
  watch the rate degrade and cannot see which offers are rotting.

### 7. SALES

**Reachable:** `/opportunities/board`. **One screen. That is the entire workspace.**

`homePathFor()` fails `mayReach(role, '/dashboard')` (`SALES` is not in `PRODUCTION_ROLES`) and
falls through `boardPathFor()` to `/opportunities/board`.

**Verdict: the least coherent workspace in the product — and the one the 2026-09-10 pivot
exists to create.**

- **No dashboard.** `RoleDashboard.tsx` argues this is deliberate: *"Their board is their
  dashboard … a tile page would be a summary of one screen."* The argument holds only if the
  board answers "what needs me today". It does not.
- **The board is a stage-grouped card list and nothing else.** In `OpportunityBoardPage.tsx`,
  each `DealCard` renders the deal name, the amount, and a status word. It does **not** show
  the contact, the owner, stage age, last-contacted date, next follow-up, or booked meeting.
  No sort, no filter, no search, no date window, no saved view.
- **Sales can write things it can never read back.** `DealActions.tsx` sets GHL follow-up tasks
  and books calendar meetings — and **no view of either exists anywhere in EvalOS**. A
  salesperson sets a follow-up for Thursday and the system will never remind them.
  `40-sales-desk.md` §5 makes this deliberate (*"no EvalOS-side reminder job"*), but the result
  is a write-only desk: the follow-up lives in GHL, the system Sales is supposed to have stopped
  opening. **This defeats the stated purpose of the programme.**
- **No client, case, invoice, meeting or document context.** Sales cannot answer any question a
  client asks after payment.
- **Clicks to the most common task** (answer "where is my evaluation?"): **impossible.**
- **Off the design system entirely** — see F6.

### 8. MARKETING

**Reachable:** `/opportunities/board`. **One screen, and it is Sales's screen.**

**Verdict: a role modelled as "Sales, minus one button".**

- The only differences are `isMarketing && <NewLeadForm onOpened={reload} />` and the absence of
  `DealActions` (gated `open && isSales`). Marketing can **open** a lead and write notes, and can
  do nothing else to it — no stage move, no follow-up, no booking.
- `39-marketing-lead-desk.md` §5 confirms there is deliberately no promotion action. But that
  leaves the marketer with a board of deals they may not touch and no measure of their own work:
  the GM's `/marketing/google-ads` and `/marketing/email` funnel screens — the two screens that
  actually show campaign performance — are `roles: ['GM']`. **Marketing cannot see the marketing
  funnels.**
- **Missing entirely:** source/campaign attribution, lead volume over time, an untouched-lead
  queue, conversion of their own leads. A marketer has no screen that tells them whether
  marketing is working.

### 9. Client (external — `client-expert/client`)

**Reachable:** `/welcome`, `/signin`, `/set-password`, `/start` (placeholder), `/dashboard`,
`/requests`, `/documents`, `/invoices`, `/meetings`, `/draft`, `/draft/:caseId`. **5 nav
entries** (`client/src/constants/navigation.ts`).

**Verdict: the best-crafted surface in the product, with two structural breaks.**

- `Dashboard.tsx` is the only screen in the entire product that opens with **"what needs you"** —
  a `needsYou` / `In progress` split off the server's `actionRequired` flag. This is the target
  experience, and it exists in exactly one place.
- **`/dashboard` and `/requests` are the same screen.** Both call `listCases()` under the
  identical query key `['portal','cases']`, both render a near-identical `CaseRow`, both link to
  `/draft/:caseId`. The nav labels them "Home" and "My cases". The internal app's `navigation.ts`
  deleted exactly this duplication (`/cases` beside `/board`) and wrote a comment about it; the
  client portal reproduced it.
- **The session is lost on refresh.** `PortalLayout.tsx` redirects to `/signin` when
  `usePortalToken()` is false, and the token lives in `apiClient` module scope, never storage. A
  client who reloads mid-upload is bounced to sign-in. Unit 42's password makes this recoverable,
  but it is still a hard interrupt on every refresh, back-button and reopened tab.
- **No sign-out.** `PortalHeader.tsx` argues *"There is no session to end — closing the tab is
  the whole of it"*. That comment predates Unit 42. There is now a password, therefore a session,
  therefore a shared-machine problem, and no control to end it.
- **No notifications.** *"the portal is the notification"* — so a client learns a document was
  rejected only by choosing to visit. `MISSING`/`INCORRECT` are visible states
  (`shared/lib/portal.ts` `CHECKLIST_STATUS`) but nothing pulls the client back to see them.

### 10. Expert (external — `client-expert/expert`)

**Reachable:** `/case` **only** (`expert/src/App.tsx`). Everything else 404s.

**Verdict: a single-case letterbox, not a workspace. The stated expert pain is untouched.**

- **No case list.** An expert with three assignments needs three separate links. The brief's
  *"experts having to ask Production or Sales about their own cases"* is precisely what this
  produces.
- **No account, no sign-in, no way back.** `ExpertCard.tsx` mints the link and shows it **once**
  (*"It is shown once: nothing reads a token back, and a lost link is re-minted"*), and a staff
  member sends it by hand. `PortalLinkLedger.tsx` names the consequence in its own header: *"the
  likeliest way EvalOS breaches that SLA is a link nobody sent."* The client got a front door in
  Unit 42; the expert did not. **The two external audiences are now asymmetric in the most basic
  way.**
- **The expert cannot read the documents.** `ExpertCaseView.evidence` is `readonly string[]` —
  *labels only*. `ExpertCasePortal.tsx` renders them as a `<ul>` of filenames with a `FileText`
  icon and **no link**. An expert asked to sign a professional opinion can see that a transcript
  exists and cannot open it. Only the draft letter has a fetchable URL (`letterLink()`).
- **No payout visibility.** `34-portal-frontend-wiring.md` D6 is DECIDED 2026-09-04 — *"yes, rows
  only, never a payment detail"* — and no screen was built. The expert's own `App.tsx` comment
  says a payouts screen "is still coming (Unit 35, D6)".
- **No task status, no notes.** The expert cannot see or contribute to the case's note stream.
- **Three orphan files describe a product that does not exist:** `expert/src/types/expert.ts`
  (`ExpertUser`, `paymentStatus`, `previousVersions`), `expert/src/constants/expertNavigation.ts`
  (`EXPERT_NAV` pointing at `/expert`, `/expert/payments`, `/expert/profile` — all deleted
  routes), and `expert/src/mock/expertMockData.ts` (a demo password). Verified zero importers.
  They are a false map of the product for every future reader.

---

## Information architecture

### Navigation structure — strong bones

`navigation.ts` holds one table that is simultaneously the nav, the router allow-list and the
role gate, with `mayReach()` defaulting to `false` for unknown paths. Deep links outside a role
render `Forbidden` rather than redirecting, so the user sees which URL was refused. Grouping is
by consecutive runs of `group` (`Overview · Marketing · Sales · Pipeline · Records · Admin`), so
the table order *is* the screen order and a heading cannot appear twice. **This is better than
most production apps and should not be touched.**

### Where it breaks

| Break | Evidence | Effect |
|---|---|---|
| **No global search** | `TopBar.tsx` — `<input disabled placeholder="Search — not available yet">` | Every entity is reachable only via the list that contains it. A named case cannot be found. |
| **`/brands` has no screen** | in `NAV_ITEMS`, absent from `App.tsx` `SCREENS` | The GM's only dead link. |
| **Case detail has no back-navigation** | `CaseDetail.tsx` — the `way.path` link exists **only inside the `failed` branch** | On the happy path there is no way back to the list you came from. Browser back only. |
| **Case → expert is a dead end** | `ExpertCard.tsx` header: *"The rest of the roster record … belongs to the expert database (Unit 11) and to the payout ledger (Unit 16)"* — and no link to either | Name and tier only. To see the expert's workload or what you owe them: leave, open `/experts`, find them again. |
| **Case → opportunity is a dead end** | `Case.ghlOpportunityId` exists (`domain/Case.java:224`); nothing in `caseApi.ts` returns it | The sales conversation that produced the case is unreachable from it. |
| **Case → payout is a dead end** | `/payouts/experts/:expertId` is reachable only from `PayoutBatch` | "What does this case owe this expert" is unanswerable from the case. |
| **Opportunity → case is a dead end** | `opportunityApi.ts` `Deal` carries no case field | Sales cannot follow their own won deal. |
| **ENM has no list that produces a case link** | no `/board`, no queue | Their `/cases/:id` grant is unreachable in practice. |
| **Marketing cannot see the marketing funnels** | `/marketing/*` is `roles: ['GM']` | The role is named after screens it cannot open. |

### The note stream is split in two, and that is the deepest IA defect

There are two append-only streams and **no bridge**:

- `frontend/src/features/opportunities/DealNotes.tsx` → `OpportunityNote`, keyed on
  `ghl_opportunity_id`. Sales and Marketing write here. *"This is the client conversation rather
  than a record of it."*
- `frontend/src/features/case/Timeline.tsx` → audit rows including `NOTE_ADDED`, keyed on
  `case_id`. Production writes here.

The brief states: *"Notes and activity are **shared** opportunity/case context across roles, not
private notes."* **They are not shared.** At the exact moment custody transfers (Handoff A) the
entire pre-sale conversation becomes invisible to everyone who now owns the work, and everything
Production learns is invisible to the person who sold it.

The join already exists — `Case.ghlOpportunityId` and `OpportunityNote.ghlOpportunityId` are the
same value. Nobody wrote the query.

### What the user must remember rather than being shown

1. **Which link opens what.** Client portal error copy across `Dashboard.tsx`, `Requests.tsx` and
   `DraftReview.tsx` says *"This link opens a single case rather than your account. Use the link
   we sent for that case."* The user must remember which of several links is which — the app
   knows it holds the wrong scope and will not offer the right one.
2. **The expert's link.** Shown once, never recoverable in-product.
3. **Which case the client's documents belong to.** `documentService.listDocuments()` takes no
   case id and `/documents` has **no case selector**, while `/draft` has one (`CasePicker`). A
   client with two cases sees one undifferentiated checklist and cannot tell or choose which case
   they are uploading to.
4. **What the PM asked for.** Solved for the CM (`/pm-notes`) and for nobody else.
5. **Whether a follow-up was set.** Sales writes it to GHL; no screen reads it back.
6. **Which stage a case is in, if you are Sales.** Not shown anywhere.

---

## Widget matrix

Status is judged against code, not spec. **EXISTS** = built and reachable. **PARTIAL** = the
data or a fragment exists but the widget does not answer its question (commonly: no
click-through, or a number with no queue behind it). **MISSING** = nothing.

Priority: **P0** breaks a stated goal of the product · **P1** a role cannot do its job well ·
**P2** real improvement, not urgent.

### Sales

| Role | Widget | Purpose | Click-through | Status | Priority |
|---|---|---|---|---|---|
| Sales | **Case status for my won deals** | Answer "where is my evaluation?" without messaging Production — *the product's founding requirement* | → read-only case status panel (projected phrase, never staff stage names) | **MISSING** | **P0** |
| Sales | Today's meetings | The day's calls, in the desk they work in | → meeting detail / join link | **MISSING** (`SalesCalendarController` books; nothing reads back) | **P0** |
| Sales | Upcoming meetings (7 days) | Prepare; spot an empty week | → filtered meeting list | **MISSING** | P1 |
| Sales | Overdue follow-ups | The follow-ups they set and the system never reminds them of | → the deal card | **MISSING** (written to GHL via `DealActions.tsx`, never read) | **P0** |
| Sales | Untouched opportunities (no note/stage move in N days) | The deals quietly dying | → filtered board | **MISSING** (`opportunity_note.created_at` exists) | **P0** |
| Sales | Recently contacted | Avoid double-contacting; show momentum | → the deal card | **MISSING** | P2 |
| Sales | Pipeline summary (count + value by stage) | Where the book stands | → stage column | **PARTIAL** — `OpportunityBoardPage.tsx` prints `totalDeals`/`totalValue` and a per-column total as plain text; not a widget, no drill | P1 |
| Sales | Kanban of my deals | Work the pipeline | card → expand | **EXISTS** (`OpportunityBoardPage.tsx`) | — |
| Sales | Deal note stream | Shared client conversation | inline on card | **EXISTS** (`DealNotes.tsx`) | — |
| Sales | Pending payments / unpaid invoices | Chase money; know before calling | → GHL invoice link | **MISSING** for staff (`invoices.readonly` is granted and `41` proves the read works — the client sees their invoices, the salesperson does not) | **P0** |
| Sales | New client submissions (intake funnel) | Work a lead the moment it arrives | → the application | **MISSING** (Unit 43 not built) | P1 |
| Sales | My conversion rate / deals won this period | Am I performing | → won list | **MISSING** | P2 |
| Sales | Stage age on each card | Spot the stuck deal without arithmetic | → card | **MISSING** | P1 |

### Marketing

| Role | Widget | Purpose | Click-through | Status | Priority |
|---|---|---|---|---|---|
| Marketing | Lead volume by source/campaign | Is the spend working | → filtered lead list | **MISSING** — funnel screens exist (`MarketingPipelinePage.tsx`) but are `roles: ['GM']` | **P0** |
| Marketing | My funnel by stage | Where leads stall | → stage | **PARTIAL** — the component exists; the role cannot reach it | **P0** |
| Marketing | Untouched new leads | A lead nobody has called | → deal card | **MISSING** | **P0** |
| Marketing | Leads opened this period | Own output | → list | **MISSING** | P1 |
| Marketing | Lead → won conversion | Lead quality, not just volume | → won list | **MISSING** | P1 |
| Marketing | New lead form | Open a lead without GHL | inline | **EXISTS** (`NewLeadForm.tsx`) | — |
| Marketing | Note stream | Shared context | inline | **EXISTS** (`DealNotes.tsx`) | — |
| Marketing | New client submissions (intake funnel) | Leads the website produced | → application | **MISSING** (Unit 43 not built) | P1 |

### Production — Project Manager

| Role | Widget | Purpose | Click-through | Status | Priority |
|---|---|---|---|---|---|
| PM | Delivered on time % | Primary KPI | `to="/inbox?view=delivered"` | **EXISTS** | — |
| PM | At risk right now | The day's fire list | `to="/inbox?view=at-risk"` | **EXISTS** | — |
| PM | Unassigned | Work with no owner | `to="/inbox?view=unassigned"` | **EXISTS** | — |
| PM | Case manager workload (capacity bars) | Who to staff next | → that CM's cases | **PARTIAL** — `CapacityBar` renders; no click-through | P1 |
| PM | Draft revision rate | Quality signal per CM | → the returned drafts | **PARTIAL** — number only | P2 |
| PM | Completion by service type | Where time goes | → filtered list | **PARTIAL** — chart only | P2 |
| PM | Expert response time | Is the network responsive | → offers list | **MISSING** — renders `unavailable, blockedBy: 'Unit 15'`; **Unit 15 is built** | P1 |
| PM | Production bottleneck (cases per stage, ageing) | Which stage is the jam | → that board column | **MISSING** | **P0** |
| PM | Drafts awaiting my review | The PM's own queue | nav entry + badge | **EXISTS** (`/drafts`, badged) | — |
| PM | Overdue expert signatures | The 24h clock breaching | → case | **PARTIAL** — on `/expert-assignment`, **no nav badge** | P1 |
| PM | Upcoming deadlines (next 7 days) | Plan the week | → filtered inbox | **PARTIAL** — board has a `Due within` filter; no dashboard widget | P1 |
| PM | Blocked / exception cases (on hold, rematching, refund) | Work that has stopped | → exception lane | **PARTIAL** — lanes exist on `/board`; no count anywhere | P1 |

### Production — Project Coordinator

| Role | Widget | Purpose | Click-through | Status | Priority |
|---|---|---|---|---|---|
| PC | Documents outstanding | Primary KPI | `to="/checklists"` | **EXISTS** | — |
| PC | Ready to deliver | The batch to send | `to="/delivery"` | **EXISTS** | — |
| PC | With the client (awaiting / never opened / >48h) | Who to chase | → those cases | **PARTIAL** — figures only, no drill | P1 |
| PC | Median wait / Delivered this week | Throughput | → list | **PARTIAL** — numbers only | P2 |
| PC | Missing documents by case | The actual chase list | → checklist row | **PARTIAL** — `/checklists` is one list; not grouped by chase age | P1 |
| PC | Overdue chases (chased, no response in N days) | Second-chase queue | → case | **MISSING** | P1 |
| PC | Review requests | — | — | **MISSING — and permanently mislabelled**: renders `unavailable, blockedBy: 'Unit 18'`; **Unit 18 was removed 2026-09-02** | P1 (remove) |
| PC | Cases entering delivery today | Plan the batch | → `/delivery` | **PARTIAL** — the queue exists, no forward view | P2 |

### Production — Case Manager

| Role | Widget | Purpose | Click-through | Status | Priority |
|---|---|---|---|---|---|
| CM | Priority queue | The day's order of work | rows → `/cases/:id` | **EXISTS** | — |
| CM | Due now | Primary KPI | → filtered `/my-cases` | **PARTIAL** — no `to` | P1 |
| CM | Draft status (with PM / returned to you) | Where my work went | → that draft | **PARTIAL** — figures only | P1 |
| CM | Expert signing (awaiting / overdue) | Is my case moving | → case | **PARTIAL** — figures only | P1 |
| CM | Client feedback | What the client asked for | rows → `/cases/:id` | **EXISTS** | — |
| CM | PM notes | What was asked of me | nav entry | **EXISTS** (`/pm-notes`) — but duplicates `/my-drafts` | P2 (merge) |
| CM | Returned-draft alert | Know without opening a screen | rail badge | **MISSING** — no `navBadges.ts` entry for `/my-drafts` | P1 |
| CM | My overdue cases | The red list | → filtered | **PARTIAL** — `myCasesCritical` badge exists on `/my-cases`; no dashboard tile | P2 |

### Expert Network Manager

| Role | Widget | Purpose | Click-through | Status | Priority |
|---|---|---|---|---|---|
| ENM | Available experts | Primary KPI | `to="/experts"` | **EXISTS** | — |
| ENM | Coverage gaps (<5 available in a field) | Recruit before it bites | → filtered roster | **PARTIAL** — list renders, rows inert | P1 |
| ENM | Availability board | Who is free | → expert sheet | **PARTIAL** — no click-through | P1 |
| ENM | Low quality scores / declining twice | Who to retire | → expert sheet | **PARTIAL** — rows inert | P1 |
| ENM | Acceptance rate / offer turnaround | Network health | → open offers | **PARTIAL** — numbers with no queue behind them | P1 |
| ENM | **Open offers awaiting response** | The queue the two KPIs above describe | → case/expert | **MISSING** | **P0** |
| ENM | Expert workload | Who is overloaded | → expert sheet | **MISSING** — `Cases in flight` is one aggregate number | P1 |
| ENM | Payout status | What the network is owed | `to="/payouts"` | **MISSING** — renders `unavailable, blockedBy: 'Unit 16'`; **Unit 16 is built and `/payouts` is in their nav** | P1 |
| ENM | Response time | Is the network responsive | → offers | **MISSING** — `unavailable, blockedBy: 'Unit 15'`; **Unit 15 is built** | P1 |
| ENM | Expert tasks / what each expert owes | Chase a signature | → case | **MISSING** | P1 |

### GM / Brand Manager

| Role | Widget | Purpose | Click-through | Status | Priority |
|---|---|---|---|---|---|
| GM/BM | Open liability (largest tile) | Money taken, work not delivered | → cases | **PARTIAL** — no `to` on any tile in `RevenueDashboard.tsx` | P1 |
| GM/BM | Collected / Recognised / Refunded | The money picture | → detail | **PARTIAL** — inert | P2 |
| GM | By-brand breakdown | Which brand is carrying | → that brand's board | **PARTIAL** — table renders, rows inert | P1 |
| GM/BM | Money out (payouts) | The other half of the P&L | `to="/payouts"` | **MISSING** — `unavailable, blockedBy: 'Unit 16'`; **Unit 16 is built** | P1 |
| GM/BM | **At-risk / overdue cases** | Operational health, not just money | → filtered board | **MISSING** — computed for the PM, withheld from the GM | **P0** |
| GM/BM | Production bottleneck by stage | Where the business is jammed | → board column | **MISSING** | **P0** |
| GM/BM | Role / team performance | Who needs help | → team member's queue | **MISSING** | P1 |
| GM/BM | Cases delivered this period | Throughput | → delivered list | **MISSING** | P1 |
| GM/BM | Completed cases / cycle time trend | Is the business getting faster | → list | **MISSING** | P2 |
| GM | Sales + marketing funnels | Front-of-house health | nav entries | **EXISTS** (`MarketingPipelinePage` ×3) — but siloed from the dashboard | P2 |
| GM | Blocked / exception cases | Refunds, rematching, holds | → exception lane | **MISSING** | P1 |

### Client (portal)

| Role | Widget | Purpose | Click-through | Status | Priority |
|---|---|---|---|---|---|
| Client | **Needs you** section | The one thing that matters | → the case | **EXISTS** (`Dashboard.tsx`) — the best widget in the product | — |
| Client | In progress cases + plain-English step | Where things stand | → `/draft/:caseId` | **EXISTS** — but always links to the draft even when the action is a document upload | P1 |
| Client | Document checklist with status | What is still needed | inline upload | **EXISTS** (`Documents.tsx`) — **but not case-scoped** | **P0** |
| Client | Invoices | What is owed | (read-only, GHL pays) | **EXISTS** (`Invoices.tsx`) | — |
| Client | Meetings | When we speak | (read-only) | **EXISTS** (`Meetings.tsx`) | — |
| Client | Draft review + approve / request changes | The decision | inline | **EXISTS** (`DraftReview.tsx`) | — |
| Client | Progress indicator across the whole journey | "How far along am I" | — | **MISSING** — deliberately, per `34` D5 (the app may hold no lifecycle). The server could send an ordinal alongside `step`. | P1 |
| Client | Revision history (what I asked for last time) | Did they do what I asked | → prior version | **MISSING** — `draftVersion` is shown, history is not | P1 |
| Client | Completed / delivered letter download | Take delivery | → file | **MISSING** — `PortalCaseService` filters `SIGNED_LETTER` out of client documents; `/reports` was deleted | **P0** |
| Client | Start a new evaluation | Buy again | → funnel | **MISSING** — `/start` is a placeholder (Unit 43 not built) | **P0** |

### Expert (portal)

| Role | Widget | Purpose | Click-through | Status | Priority |
|---|---|---|---|---|---|
| Expert | **My assigned cases** | See all my work in one place — *the stated expert pain* | → case | **MISSING** — one case per link, no list | **P0** |
| Expert | Case goal + applicant | What am I being asked | — | **EXISTS** (`goalOf(view)`) | — |
| Expert | The letter (open) | The thing to sign | presigned link | **EXISTS** (`LetterButton`) | — |
| Expert | Evidence documents | **Read** what the opinion rests on | → open document | **PARTIAL → effectively MISSING** — `evidence` is `readonly string[]`, rendered as unlinked labels | **P0** |
| Expert | Sign & upload panel | Return the signed letter | inline | **EXISTS** (`SignPanel`) — attestation gate is well done | — |
| Expert | Accept / request evidence / decline | Answer the offer | inline | **EXISTS** (`Answers`) | — |
| Expert | Deadline + SLA badge | How long do I have | — | **EXISTS** (`SIGN_SLA`) | — |
| Expert | Payment / payout status | What am I owed, when | → payout row | **MISSING** — D6 decided 2026-09-04, unbuilt | **P0** |
| Expert | Task status update | Tell staff where I am | inline | **PARTIAL** — accept/decline/request-evidence are the only states; no "in progress" | P2 |
| Expert | Case note stream | Shared context, not a private channel | inline | **MISSING** — the expert is excluded from the shared context the brief describes | P1 |
| Expert | Signed-work history | What have I done for you | → past cases | **MISSING** | P2 |

---

## Journey walkthroughs

### The Client journey

| # | Step | What actually happens | Break |
|---|---|---|---|
| 1 | **Sign up** | `/welcome` offers *"Start a new evaluation — Tell us about yourself and what you need evaluated."* → `/start` → `Start.tsx` replies *"We can't take new evaluations through the portal just yet. Please contact us."* | **BREAK (P0).** The front door's primary call-to-action lands on a dead end that contradicts the promise one screen earlier. `Start.tsx`'s own comment says Unit 43 replaces it; Unit 43 is **not built**. Sign-up does not exist. |
| 2 | **Sign in** | `/signin` is email-first: `identify` returns `PASSWORD_SET` / `NO_PASSWORD` / `UNKNOWN`, each with distinct copy; `SetPassword.tsx` handles the mailed token. | **Works.** Well designed — `authService` has its own `authFailureMessage` so a wrong password never renders the link-expired text. |
| 3 | **Choose or request a service** | — | **BREAK (P0).** No screen. `43-client-intake-funnel.md` specs service selection → purpose → about you; none built. |
| 4 | **Questionnaire** | — | **BREAK (P0).** No screen. The conditional questionnaire engine specced in Unit 43 §2 exists only in git history (`f9f1165^`). |
| 5 | **Document submission** | `/documents` — checklist with per-item dropzone, `actionFirst()` ordering, `MISSING`/`INCORRECT` shown with the Coordinator's meaning, upload progress, accepted types and cap stated on the control. | **Works well for one case.** **BREAK (P0): not case-scoped.** `listDocuments()` takes no case id and there is no `CasePicker` here, though `/draft` has one. A client with two cases sees one merged checklist and cannot choose which case they are uploading to. |
| 6 | **Track progress** | `/dashboard` — "Needs you" / "In progress", each row showing the server's `step` phrase. | **Works, with two breaks.** (a) Every `CaseRow` links to `/draft/:caseId` **regardless of what the action is** — a client whose action is "upload a transcript" is sent to the draft screen, which says *"The draft is not ready to read yet."* (b) `/dashboard` and `/requests` are the same screen under two nav labels. |
| 7 | **Payments** | `/invoices` — read-only projection of GHL invoices. No pay button by design (`41` §: payment goes through GHL's own link). | **BREAK (P1).** The screen says what is owed and offers **no route to pay it**. The client must find the GHL link in an email. A read-only "what you owe" with no payment affordance is a half-journey. |
| 8 | **Respond to missing-info requests** | `MISSING` / `INCORRECT` badges appear on `/documents`; `actionRequired` surfaces on the dashboard. | **Works as a state. BREAK (P1) as a loop:** nothing notifies. `PortalHeader.tsx` has no bell and EvalOS sends no mail but authentication mail. The client learns a document was rejected only by choosing to visit. |
| 9 | **View draft** | `/draft/:caseId` — version badge, approval status, external link to the draft. | **Works.** **BREAK (P1):** no revision history. `draftVersion` is a number with nothing behind it; a client on v3 cannot see what they asked for on v1. |
| 10 | **Approve / request changes** | Inline: `Approve this draft` with *"This sends it to the expert to sign. It cannot be undone from here."*, or a required free-text revision request. | **Works — the strongest interaction in the product.** The irreversibility is stated at the point of action, and the revision box refuses an empty reason. |
| 11 | **Completion** | — | **BREAK (P0).** There is no delivery screen and no way to download the finished letter. `client/src/App.tsx` states it plainly: *"`/reports` is DELETED … EvalOS has no route that serves it — `PortalCaseService` filters `SIGNED_LETTER` out of the client's documents deliberately, because delivery is a decision nobody has taken."* **The client journey has no ending.** |

### The Expert journey

| # | Step | What actually happens | Break |
|---|---|---|---|
| 1 | **Get in** | A CM clicks *mint* in `ExpertCard.tsx`, copies a URL, sends it by hand. Shown once; re-minting revokes the previous link. | **BREAK (P0).** No account, no sign-in, no recovery. `PortalLinkLedger.tsx`: *"the likeliest way EvalOS breaches that SLA is a link nobody sent."* The 24h signing clock runs regardless. The client got a front door in Unit 42; the expert did not. |
| 2 | **See assigned cases and tasks** | `/case` renders **the one case the token names**. | **BREAK (P0).** No list. Three assignments = three links in three places. This is the stated expert pain, unaddressed. |
| 3 | **Read case context** | `goalOf(view)` + applicant name + case reference. | **Works** — the goal-first framing is right. |
| 4 | **Read the documents** | `evidence` renders as a `<ul>` of labels with a `FileText` icon. `ExpertCaseView.evidence: readonly string[]`. | **BREAK (P0).** **The expert cannot open a single client document.** They can see a transcript exists and are asked to sign a professional opinion resting on it. |
| 5 | **Do the work** | Out of product, correctly. | OK. |
| 6 | **Download draft** | `LetterButton` fetches a presigned URL on click, opens synchronously to survive popup blockers, severs `opener`. | **Works. Well engineered.** |
| 7 | **Review / sign** | `SignPanel` — required attestation checkbox gates the dropzone, copy says plainly *"A scanned wet signature is expected and accepted — there is no e-signature step to look for."* | **Works — exemplary.** The attestation is server-enforced, not UI-only. |
| 8 | **Upload signed** | Dropzone, PDF only, 15 MB, progress bar, toast. | **Works.** |
| 9 | **Update task status** | `Accept` / `Ask for more evidence` / `Decline`, each with consequence copy. | **Mostly works. BREAK (P2):** no "in progress" — between accepting and uploading, staff see nothing. |
| 10 | **See payment / payout info** | — | **BREAK (P0).** No screen. `34-portal-frontend-wiring.md` D6 DECIDED 2026-09-04 (*"yes, rows only, never a payment detail"*), unbuilt. An expert cannot see what they are owed or whether it was paid — so they ask a colleague, which is the pain. |
| 11 | **Ask a question** | — | **BREAK (P1).** The expert can `requestEvidence` and `decline` with free text; there is no general channel and no sight of the case note stream. |

---

## State coverage gaps

| Surface / screen | Loading | Empty | Error | Retry | Success | Confirm | Optimistic | Permission-aware | Notes |
|---|---|---|---|---|---|---|---|---|---|
| `components/ui/card.tsx` (internal card system) | ✅ pulse | ✅ operational copy | ✅ | ✅ optional `onRetry` | — | — | — | — | **Excellent contract**: `loading·ok·warning·error·empty·unavailable`, zero ≠ empty, `to` makes clickability structural. |
| `BoardView.tsx` | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ `QuickActionDialog` | ❌ refetch | ✅ `STAGE_ACCESS` | Good. |
| `InboxPage.tsx` | ✅ hand-rolled pulse | ✅ per-view copy | ✅ | ❌ **no retry** | — | ✅ | ❌ | ✅ | Error is a red `<p>` with no way forward. |
| `DeliveryQueuePage` / `DraftQueuePage` / `MyDraftsPage` | ✅ | ✅ | ✅ | ❌ | ✅ | ✅ | ❌ | ✅ | Same missing-retry pattern. |
| `ExpertAssignmentPage.tsx` | ❌ **no loading state** | ✅ | ✅ | ✅ | — | ✅ | ❌ | ✅ | Blank screen while loading. |
| `JobRunsPage.tsx` | ❌ | ❌ | ✅ | ❌ | ✅ | — | ❌ | ✅ GM-only | No empty state for a never-run sweep. |
| `OpportunityBoardPage.tsx` (**Sales/Marketing's only screen**) | ❌ **none** | ✅ (good two-cause copy) | ❌ **none** | ❌ | — | ❌ | ❌ | ✅ `isSales`/`isMarketing` | **Worst-covered screen in the app.** `Card state={state}` supplies loading, but the board renders no error path of its own. |
| `DealActions.tsx` | ✅ `busy` | — | ✅ inline | ❌ | ✅ inline confirmations | ❌ **no confirm on `closeDeal('lost')`** | ❌ | ✅ | Irreversibly losing a deal takes one click and no dialog — while the client portal confirms an approval in two places. |
| `CaseDetail.tsx` | ✅ text only | ❌ **no empty** | ✅ + escape link | ❌ | ✅ | ✅ | ❌ refetch | ✅ | Escape link exists **only** in the error branch. |
| `RevenueDashboard` / `CaseManagerDashboard` | ✅ | ✅ | ✅ | ✅ | — | — | — | ✅ | No tile is clickable. |
| Client portal (all pages) | ✅ `TableSkeleton`/`ListSkeleton` | ✅ `EmptyState` + icon | ✅ `ErrorState` | ✅ **every page** | ✅ `sonner` toasts | ✅ inline, consequence-stating | ❌ deliberate refetch | ✅ token-aware `NO_TOKEN` | **Best state coverage in the product.** |
| Expert portal `/case` | ✅ | ✅ | ✅ + retry | ✅ | ✅ | ⚠️ attestation gate, no dialog on `decline` | ❌ | ✅ `nameKnown` disables upload | `decline` is irreversible with no confirmation step. |

**Systemic state gaps**

1. **First-run emptiness is unhandled for Sales, Marketing and the ENM.** A new ENM sees five
   KPIs reading 0 and four lists whose empty copy congratulates them (*"Every field has five or
   more available experts"* when there are none). A new Sales user sees an empty board whose copy
   assumes the pipeline-assignment cause. Neither gets an onboarding path.
2. **No retry on internal-app errors.** Only 4 of 16 internal screens offer one; every client
   portal page does. The weaker surface is the one staff use for nine hours.
3. **No skeletons in the internal app.** The portal has `TableSkeleton`/`ListSkeleton`; the
   internal app hand-rolls `<div className="animate-pulse">` per screen, in violation of
   `ui-context.md`'s *"do not hand-roll a second version of something that already exists."*
4. **`unavailable` is being used as a tombstone.** Four cards blame units that are built or
   deleted (F5).
5. **No optimistic updates anywhere** — every mutation refetches. Correct for money and stage
   moves; conservative for notes, which the client portal already proves can be prepended safely.

---

## Interaction patterns

| Pattern | Present? | Where | Verdict |
|---|---|---|---|
| **Global search** | ❌ | `TopBar.tsx` — disabled input | **P0.** The single biggest IA gap. Nothing in the product can be found by name. |
| **Advanced filtering** | Partial | `BoardView` (deadline window, owner, service, urgent); `InboxPage` (`?view=` presets) | Good where present; absent on `/opportunities/board`, `/experts`, `/payouts`. |
| **Sorting** | ❌ | `ui-context.md` promises "dense rows, sortable"; no table implements it | Server order only. |
| **Pagination** | ❌ | nowhere internal (`shared/ui/pagination.tsx` exists, unused) | Acceptable at 50–100 cases/brand/month; will break. |
| **Saved views** | ❌ | — | `22-role-operations-ui.md` specs hardcoded presets only. Reasonable for now. |
| **Bulk actions** | ❌ | `22` §slice 1 specs "bulk select with a confirmation dialog" on `/inbox`; **not in `InboxPage.tsx`** | Specced, unbuilt. P2. |
| **Quick actions** | ✅ | `boardRules.ts` `QUICK_ACTIONS` (21 actions) + `QuickActionDialog` + `admits(action, role)` | **Excellent** — one table drives labels, fields, role gates. |
| **Contextual drawers / sheets** | Partial | `ui/dialog.tsx` (`Dialog` + `Sheet`); used by `ExpertRoster` only | The row-click-opens-a-sheet pattern is documented as a rule in `ui-context.md` and applied on exactly one screen. Should be system-wide (cases, deals, payouts). |
| **Kanban vs table vs calendar** | Kanban ✅, table ✅, **calendar ❌** | `BoardView`, `OpportunityBoardPage`; dense tables throughout | Sales books meetings with no calendar to see them in. |
| **Document preview** | ❌ | `DocumentList.tsx` and client `Documents.tsx` open a presigned URL in a new tab | Acceptable; a preview pane would matter most for the expert, who currently gets neither. |
| **Version history** | Partial | `DraftHistory.tsx` (internal) | Internal staff see draft history; **the client and the expert do not**. |
| **Approval interfaces** | ✅ | `DraftReview.tsx` (client), `DraftQueuePage` (PM), `SignPanel` (expert) | Consistently good; consequence copy at the point of action. |
| **Activity timelines** | ✅✅ but **split** | `Timeline.tsx` (case) and `DealNotes.tsx` (opportunity) | Two streams, no bridge — see F2. |
| **Status indicators** | ✅ | RAG tokens, `SIGN_TONE`, `CHECKLIST_STATUS`, `APPROVAL_STATUS`, SLA rail | Strong and semantically disciplined — the two-clocks rule (Stage SLA vs Deadline) is properly enforced. |
| **Notifications / alerts** | Internal ✅, external ❌ | `NotificationBell.tsx` + `navBadges.ts` | Staff are told; the client and the expert are told nothing. |
| **"My Tasks" / "Action Required"** | Client ✅, everyone else ❌ | `client/.../Dashboard.tsx` `needsYou` | **The target experience exists in exactly one screen, on the surface that needed it least.** |
| **Responsiveness** | Internal: desktop-only (correct per `ui-context.md`, 1366×768 reference). Portals: ✅ full mobile (`MobileNavDrawer`, `useMediaQuery`) | — | No defect. |

**Patterns that should be system-wide and are currently per-screen inventions**

1. **KPI → filtered queue** — `PmDashboard` only. `card.tsx` already makes it a `to` prop.
2. **Row click opens a sheet** — `ExpertRoster` only, though `ui-context.md` states it as a rule
   for "any record opened from a table".
3. **`?view=` filter presets** — `InboxPage` only; would serve `/board`, `/experts`, `/payouts`,
   `/opportunities/board` identically.
4. **Skeleton loading** — exists in the portal (`shared/components/common/LoadingState`),
   hand-rolled per screen internally.
5. **Error + retry** — `ErrorState` exists in the portal; the internal app re-invents a red
   paragraph per screen, usually without retry.
6. **Consequence-stating confirmation** — perfected in `DraftReview.tsx`, absent from
   `DealActions.closeDeal('lost')`, which is equally irreversible.

---

## The three experiences as one product

### Shared

- **`client-expert/shared/src`** genuinely shares primitives across the two external apps:
  `EmptyState`, `ErrorState`, `LoadingState`, `FileDropzone`, `PageHeader`, `usePortalToken`,
  `apiClient`, `portal.ts`.
- **`PortalStageProjection.forClient()/forExpert()`** is the right idea, correctly placed: one
  server-side vocabulary, two audience projections, and the SPAs hold no lifecycle enum
  (`34-portal-frontend-wiring.md` D5).
- The internal/portal token divergence (Inter + `#2563EB` vs DM Sans/DM Serif + IE navy) is
  **deliberate and correct** per `ui-context.md`, and I am not recommending unifying it.

### Divergences that are defects

**D1 — The same fact has three different names.** A case in `EXPERT_SIGNING`:
| Audience | What they are told | Source |
|---|---|---|
| Staff | `Expert Signing` column, owner = Case Manager, next action string | `boardRules.ts` `STAGE_COLUMNS` / `STAGE_NEXT_ACTION` |
| Client | a server-rendered `step` phrase | `PortalStageProjection.forClient` |
| Expert | `SIGN_STATUS` badge — `PENDING` / `SIGNED` / `OVERDUE` / `REASSIGNED` | `expert/src/lib/expertCase.ts` |
| Sales | **nothing** | — |
The first three are defensible projections of one server truth. **The fourth is the defect.**

**D2 — "Overdue" means two different things to two audiences.** Internally `DeadlineRisk.OVERDUE`
is the red band and explicitly includes *"inside 24 business hours"* (`ui-context.md` — *"past
the date **or** inside 24 business hours"*). In the expert portal `SlaStatus.OVERDUE`
(`expertCase.ts`) is the signing clock. An expert told "overdue" and a PM told "overdue" about
the same case are being told about different clocks. `ui-context.md` mandates that the two
internal clocks are *"labelled 'Stage SLA' and 'Deadline' and neither is ever substituted for the
other"* — that discipline stops at the portal boundary.

**D3 — "Needs you" exists client-side only.** The client gets an explicit `actionRequired` flag
and a "Needs you" section. The expert has the identical concept (`view.awaitingAnswer` has no
equivalent; `stateOf(view)` returns `OPEN`) and no such framing. Staff have `navBadges` but no
"Action required" section on any dashboard.

**D4 — Document status vocabulary is client-only.** `CHECKLIST_STATUS` maps
`REQUIRED/UPLOADED/APPROVED/MISSING/INCORRECT` to *Needed / Received / Accepted / Still needed /
Please replace*. The **expert** sees the same documents as bare labels with no status at all, and
the internal `DocumentList.tsx` shows raw filenames. Three renderings of one `ChecklistItemStatus`.

**D5 — Error copy diverges by surface, not by audience.** The portal's `failureMessage()` is
carefully written per HTTP status (401/403/429/502) in the reader's language; the internal app
surfaces `cause.message` from the API client. A Coordinator gets a raw server string where a
client gets *"Our document store is temporarily unavailable. Nothing was lost."*

**D6 — Two portals, two identity models.** The client now has email + password + reset (Unit 42);
the expert has a hand-pasted single-use link. Same company, same "external party", opposite
front doors. `expert/src/App.tsx` justifies this by invariant 14, **which was amended on
2026-09-11** to permit authentication mail. The justification expired; the asymmetry did not.

**D7 — The portal palette is hard-coded to one brand.** `ui-context.md` names this itself as
Unit 34 **D7**: IE navy `#003152` and crimson `#c8102e` are baked into
`shared/src/styles/globals.css`, and XpertsPortal has no home. In a product whose first
invariant is brand scoping, both external surfaces are single-brand.

---

## Findings

Each: **what / evidence / user impact / decision / priority.**

---

**F1 — Sales has no visibility of a case, so the pain the product was commissioned to remove is
architecturally preserved.**
*Evidence:* `frontend/src/features/shell/navigation.ts` (`PARAMETERIZED`, `/cases/:id` →
`PRODUCTION_ROLES`, which excludes `SALES`/`MARKETING`); `context/specs/40-sales-desk.md` §5
*"a case that exists belongs to PM/PC/CM"*; `TopBar.tsx` search disabled.
*Impact:* Every "where is my evaluation?" call becomes a Slack message to Production and a
relayed reply. The founding requirement is unmet.
*Decision:* **ADD** — a read-only case status panel for SALES, keyed on `Case.ghlOpportunityId`
(`domain/Case.java:224`), rendering `PortalStageProjection.forSales(stage)` (a third projection
beside `forClient`/`forExpert`). No stage names, no deal internals, no write access — the leak
objection in `41-client-portal-invoices.md` §10 is already answered by the projection mechanism.
*Priority:* **P0**

---

**F2 — The note stream is split at Handoff A, so shared context is not shared.**
*Evidence:* `features/opportunities/DealNotes.tsx` (`OpportunityNote`, keyed on
`ghl_opportunity_id`) vs `features/case/Timeline.tsx` (audit rows keyed on `case_id`). No query
joins them. `Case.ghlOpportunityId` and `OpportunityNote.ghlOpportunityId` hold the same value.
*Impact:* Everything Sales learned during the sale is invisible to the PM, PC and CM who now own
the work; everything Production learns is invisible to the person the client will phone. This
directly contradicts the brief's *"Notes and activity are shared opportunity/case context across
roles."*
*Decision:* **ADD** — render the opportunity note stream as the earliest segment of the case
`Timeline`, visually marked as pre-custody. One join, no schema change, no new write path.
*Priority:* **P0**

---

**F3 — The expert cannot open the documents the opinion rests on.**
*Evidence:* `expert/src/lib/expertCase.ts` — `ExpertCaseView.evidence: readonly string[]`;
`ExpertCasePortal.tsx` renders them as an unlinked `<ul>` under *"What the opinion rests on"*.
*Impact:* An expert is asked to attest to a professional opinion while able to see only the
*names* of the evidence. They must email the CM for every document — the exact pain the brief
names for experts. It is also a quality risk on a signed legal instrument.
*Decision:* **MODIFY** — return `{id,label}` and reuse the presigned-URL pattern already proven
twice (`documentService.documentUrl`, `expertPortalService.letterLink`).
*Priority:* **P0**

---

**F4 — The client journey has no beginning and no end.**
*Evidence:* Beginning — `Welcome.tsx` promises *"Start a new evaluation"* → `Start.tsx`: *"We
can't take new evaluations through the portal just yet."* (`43-client-intake-funnel.md` NOT
BUILT). End — `client/src/App.tsx`: *"`/reports` is DELETED … `PortalCaseService` filters
`SIGNED_LETTER` out of the client's documents deliberately, because delivery is a decision
nobody has taken."*
*Impact:* A prospect cannot buy, and a paying client cannot take delivery of the thing they
bought. The portal's front door advertises a road that is closed.
*Decision:* **ADD** — build Unit 43; take the delivery decision and add a completion screen.
Until Unit 43 ships, change `Welcome.tsx`'s first card to name the real route (contact/sales)
rather than advertising a placeholder.
*Priority:* **P0**

---

**F5 — Four dashboard cards permanently claim data is missing that is built or cancelled.**
*Evidence:* `CoordinatorDashboard.tsx:94` `blockedBy: 'Unit 18'` — **Unit 18 REMOVED
2026-09-02** (`00-build-plan.md:377`); `PmDashboard.tsx:204` and
`ExpertNetworkDashboard.tsx:197` `blockedBy: 'Unit 15'` — **Unit 15 BUILT**
(`progress-tracker.md:1314`); `ExpertNetworkDashboard.tsx:198` and `RevenueDashboard.tsx:104`
`blockedBy: 'Unit 16'` — **Unit 16 BUILT** (`PayoutController.java`, `/payouts` in both roles'
nav).
*Impact:* Three roles are told on their landing screen that data does not exist while a nav entry
serves it. The `unavailable` state — a genuinely good idea — is being spent as a tombstone, and
each stale card teaches users to ignore it.
*Decision:* **REMOVE** the Unit 18 card; **MODIFY** the other three to render the live data
(Unit 15 response times, Unit 16 payout totals with `to="/payouts"`).
*Priority:* **P1**

---

**F6 — The Sales/Marketing feature abandoned the design system, on the newest and most strategic
screens in the product.**
*Evidence:* `features/opportunities/` — 4 files, ~30 hardcoded Tailwind palette classes
(`slate-900`, `slate-200`, `emerald-700`, `amber-700`) instead of `--text-primary`,
`--border-default`, `--status-*`. `ui-context.md`: *"No hardcoded hex in components"* and *"RAG is
status-only and never decorative"* — `text-emerald-700` on a follow-up confirmation and
`text-amber-700` on a pending state are exactly the decorative-status use the rule forbids. Board
columns are `w-64` where the density table specifies `w-60`/240px; cards use `rounded` (4px) where
the radius table specifies `rounded-lg`. Also affects `JobRunsPage.tsx` and `PortalLinkLedger.tsx`.
*Impact:* Sales and Marketing — whose *entire workspace* is this feature — work in a screen that
looks like a different product, and the RAG language they will meet on every other screen is
pre-devalued by decorative use here.
*Decision:* **REFACTOR** to tokens; reuse `components/ui/card.tsx` rather than hand-rolled
`<article className="rounded border">`.
*Priority:* **P1**

---

**F7 — Sales can write follow-ups and meetings it can never read back.**
*Evidence:* `DealActions.tsx` — `setFollowUp()` (a GHL task) and `bookMeeting()` (a GHL calendar
event). No screen in `frontend/src` reads either. `40-sales-desk.md` §5: *"no EvalOS-side reminder
job."*
*Impact:* A write-only desk. The salesperson sets Thursday's follow-up and must open GHL to see
it — defeating the stated purpose of Units 36–41 ("so that they never open GHL"). Confirmation
toasts promise a reminder the product will never deliver.
*Decision:* **ADD** — "Today's meetings", "Upcoming meetings" and "Overdue follow-ups" widgets on
the Sales home, reading the GHL task/calendar endpoints the write path already uses. These are
reads of GHL's own data, not an EvalOS reminder engine, so `40` §5 is not violated.
*Priority:* **P0**

---

**F8 — The expert has no front door, while the client was given one.**
*Evidence:* `expert/src/App.tsx` — one route, `/case`, token in the URL fragment;
`ExpertCard.tsx` — link *"shown once"*, hand-sent; `PortalLinkLedger.tsx` — *"the likeliest way
EvalOS breaches that SLA is a link nobody sent."* Unit 42 gave the client email sign-in and
password reset; invariant 14 was amended the same day to permit authentication mail.
*Impact:* An expert who loses the tab loses the case, while a 24-hour signing clock runs. Every
recovery is a human request to a CM. The justification (no mail channel) expired on 2026-09-11.
*Decision:* **ADD** — an expert sign-in mirroring Unit 42 (`identify` → password → the same
`PortalAccess` token mint), plus a case list. This is largely a second instance of code that
already exists.
*Priority:* **P0**

---

**F9 — There is no global search, and no entity can be found by name.**
*Evidence:* `TopBar.tsx` — `<input type="search" disabled placeholder="Search — not available
yet" title="Case search has no endpoint yet">`.
*Impact:* Every lookup is a navigate-and-scan. Compounds F1 (Sales could not use a case screen
even if granted one), the ENM's linkless `/cases/:id` grant, and support work across all roles.
*Decision:* **ADD** — one brand-scoped search over case code, client name, applicant name and
expert name, returning typed results. The honest disabled control was the right interim call; it
is now the top structural gap.
*Priority:* **P0**

---

**F10 — The client's document checklist is not case-scoped.**
*Evidence:* `client/src/services/documentService.ts` — `listDocuments()` takes no case id;
`Documents.tsx` has no `CasePicker`, though `DraftReview.tsx` has one for exactly this reason
(*"a party link covering several cases asks which one rather than guessing"*).
*Impact:* A client with two cases sees one merged checklist, cannot tell which case an item
belongs to, and cannot choose where an upload lands. The wrong transcript reaches the wrong case.
*Decision:* **MODIFY** — add the `CasePicker` already written in `DraftReview.tsx`, and scope
`listDocuments(caseId)`.
*Priority:* **P0**

---

**F11 — The GM and Brand Manager have no operational view of the business they run.**
*Evidence:* `RevenueDashboard.tsx` — four money KPIs, a by-brand table, one `unavailable` card,
**zero `to=` props**. Every operational figure they need (at-risk, unassigned, capacity,
bottleneck) is already computed in `PmDashboard`/`CoordinatorDashboard`/`ExpertNetworkDashboard`.
*Impact:* The two most senior roles land on a finance summary they cannot act on or click. To
find a case in trouble a GM opens the board and scans eight columns by eye.
*Decision:* **REDESIGN** — money tiles stay (open liability remains the primary KPI, correctly),
plus an operational row: at-risk, blocked/exception, bottleneck-by-stage, delivered this period —
each with a `to` into the filtered board.
*Priority:* **P0**

---

**F12 — KPI tiles do not drill through, except on one dashboard.**
*Evidence:* `card.tsx` — *"Clickability is structural: a card takes an optional `to`."*
`PmDashboard` uses it 3×, `CoordinatorDashboard` 2×, `ExpertNetworkDashboard` 1×,
`CaseManagerDashboard` **0**, `RevenueDashboard` **0**.
*Impact:* "12 at risk" is a number the user must then go and find. The mechanism was built and
then not used.
*Decision:* **MODIFY** — every countable tile gets a `to` into its filtered population; adopt the
`?view=` preset pattern `InboxPage` already implements.
*Priority:* **P1**

---

**F13 — The ENM's KPIs have no queues behind them.**
*Evidence:* `ExpertNetworkDashboard.tsx` — `Offer turnaround` and `Acceptance rate` are computed;
no screen lists open offers. `Coverage gaps`, `Low quality scores` and `Declining two or more`
render rows with no click target. Their nav is three entries and no board
(`navigation.ts` `boardPathFor`: *"a role that can reach neither (the Expert Network Manager
today)"*).
*Impact:* The ENM watches acceptance rate fall and cannot see which offers are rotting. Their
whole job is a diagnosis with no treatment screen.
*Decision:* **ADD** an open-offers queue (expert, case, sent at, age, SLA), and **MODIFY** the
three diagnostic lists so a row opens the expert sheet.
*Priority:* **P1**

---

**F14 — The expert cannot see what they are owed.**
*Evidence:* `34-portal-frontend-wiring.md` D6 **DECIDED 2026-09-04** — *"yes, rows only, never a
payment detail"*. No screen exists; `expert/src/App.tsx` records it as still coming.
*Impact:* Experts ask Production or the ENM about their own money — a named instance of the pain
the product exists to remove. Payout data and its access rule are both already decided.
*Decision:* **ADD** — a payout rows screen in the expert portal (case ref, amount, status,
settlement date). Depends on F8 for a durable way in.
*Priority:* **P0**

---

**F15 — Three orphan files in the expert app describe a product that does not exist.**
*Evidence:* `expert/src/types/expert.ts` (`ExpertUser`, `password`, `paymentStatus`,
`previousVersions`), `expert/src/constants/expertNavigation.ts` (`EXPERT_NAV` → `/expert`,
`/expert/payments`, `/expert/profile` — all deleted routes),
`expert/src/mock/expertMockData.ts` (a demo credential). Verified: zero importers.
*Impact:* Any reader — human or agent — orienting in the expert app finds a nav constant, a user
type and mock data implying an account shell, a payments screen and a profile page. None exist.
A demo password in a shipped app directory is also a poor default.
*Decision:* **REMOVE**.
*Priority:* **P1**

---

**F16 — `/brands` is a nav entry with no screen.**
*Evidence:* `navigation.ts` lists it (`becomes: 'Brand administration'`); `App.tsx`'s `SCREENS`
has no key, so it renders `PlaceholderPage`. `navigation.ts` states the rule one entry below:
*"add the entry with the screen, never ahead of it."*
*Impact:* The GM's only dead link, in a nav whose credibility rests on every entry being real.
*Decision:* **REMOVE** the entry until Unit 22 slice 5's `/brands` screen ships.
*Priority:* **P1**

---

**F17 — The client portal duplicates a screen the internal app deliberately de-duplicated.**
*Evidence:* `client/pages/dashboard/Dashboard.tsx` and `client/pages/requests/Requests.tsx` —
same `listCases()` call, same `['portal','cases']` query key, near-identical `CaseRow`, same
`/draft/:caseId` link. Nav labels: "Home" and "My cases".
*Impact:* Two doors to one room in a 5-item nav. `navigation.ts` documents having deleted exactly
this (`/cases` beside `/board`) and why.
*Decision:* **REFACTOR** — keep `/dashboard` (it has the "Needs you" split that earns it) and
delete `/requests`, or give `/requests` a genuinely different question (history including
completed cases).
*Priority:* **P1**

---

**F18 — The client is sent to the draft screen whatever the required action is.**
*Evidence:* `Dashboard.tsx` / `Requests.tsx` — `<Link to={'/draft/' + item.caseId}>` on **every**
row, including rows badged "Needs you" because a document is missing.
*Impact:* A client told to act lands on a page reading *"The draft is not ready to read yet. We
will let you know."* The one screen in the product that correctly identifies what needs the user
then routes them away from it.
*Decision:* **MODIFY** — the server already computes `actionRequired`; have it also name the
destination (`/documents?case=…` vs `/draft/:caseId`).
*Priority:* **P0**

---

**F19 — The client portal drops its session on every refresh, and cannot be signed out of.**
*Evidence:* `PortalLayout.tsx` redirects to `/signin` when `usePortalToken()` is false; the token
lives in `apiClient` module scope, never storage (`shared/lib/portal.ts`). `PortalHeader.tsx`
justifies having no logout with *"There is no session to end"* — written before Unit 42 added a
password.
*Impact:* A refresh, a back-button or a reopened tab interrupts the client mid-journey. And on a
shared machine there is now a real session with no way to end it.
*Decision:* **MODIFY** — keep the fragment token memory-only (correct for forwarded links), but
give a *password* sign-in a `sessionStorage`-backed session and a sign-out control. Different
credential, different lifetime.
*Priority:* **P1**

---

**F20 — Marketing cannot see the marketing funnels.**
*Evidence:* `navigation.ts` — `/marketing/google-ads` and `/marketing/email` are
`roles: ['GM']`, gated because `evalos.ghl.location-id` cannot be attributed to a brand. But
`/opportunities/board` is already an accepted exception to that same rule via
`evalos.ghl.sales-brand` ("the exception narrowed rather than widened").
*Impact:* The role named after these screens cannot open them, and has no measure of its own
output. `MarketingPipelinePage` already exists and renders correctly.
*Decision:* **MODIFY** — extend the `sales-brand` exception to the two marketing funnel entries
for `MARKETING`, exactly as it was extended for the opportunity board.
*Priority:* **P1**

---

**F21 — Case detail is a cul-de-sac in every direction.**
*Evidence:* `CaseDetail.tsx` — the escape link (`way.path`) exists **only** in the `failed`
branch. `ExpertCard.tsx` shows name and tier with no link to `/experts` or `/payouts` (*"belongs
to the expert database … and to the payout ledger"*). `caseApi.ts` exposes no
`ghlOpportunityId`. No `/payouts` link.
*Impact:* From the product's most important record you cannot reach the expert's profile, the
opportunity that created it, or the payout it will generate — and on the happy path you cannot
get back to the list you came from.
*Decision:* **MODIFY** — add a persistent back link (already computed by `boardPathFor`), link
the expert name into the roster sheet, link to the payout row, and surface the opportunity note
stream per F2.
*Priority:* **P1**

---

**F22 — No optimistic or forward-looking state for irreversible sales actions.**
*Evidence:* `DealActions.tsx` — `closeDeal('lost')` and `closeDeal('abandoned')` fire on a single
click with no confirmation, while `DraftReview.tsx` (client) confirms an approval inline *and*
states its irreversibility, and `SignPanel` gates an upload behind an attestation.
*Impact:* The least-supervised role has the least-guarded destructive action.
*Decision:* **MODIFY** — reuse `ConfirmDialog` / `QuickActionDialog` with consequence copy.
*Priority:* **P2**

---

**F23 — Three context documents still describe Unit 31 as unbuilt.**
*Evidence:* `context/ui-context.md` (*"⚠ Unit 31 reverses this (SPECCED 2026-09-02, not
built)"*), `context/specs/17-dashboards.md` (status banner) and
`context/specs/22-role-operations-ui.md` all say so. The code disagrees:
`features/board/boardRules.ts` ships `STAGE_COLUMNS` — eight columns, each with an `owner: Role`
— plus `STAGE_OWNER` and `STAGE_NEXT_ACTION`, which is Unit 31 §10 exactly.
`progress-tracker.md:6434`: *"Unit 31 BUILT (backend + board)."*
*Impact:* The design system document — explicitly the source of truth for anything semantic —
describes the previous board. Any designer or agent reading it will design against five columns
and derived chips. The project's own CLAUDE.md requires that a changed decision is an edit, not
an addition.
*Decision:* **MODIFY** all three documents.
*Priority:* **P1**

---

**F24 — Internal-app error states mostly offer no way forward, and loading is hand-rolled per
screen.**
*Evidence:* Retry appears in 4 of 16 internal screens; every client portal page has it.
`ExpertAssignmentPage` and `JobRunsPage` render nothing while loading. Five screens hand-roll
`<div className="animate-pulse">`; `shared/components/common/LoadingState` (`TableSkeleton`,
`ListSkeleton`) exists only in the portal. `ui-context.md`: *"do not hand-roll a second version of
something that already exists."*
*Impact:* Staff on nine-hour shifts get weaker failure handling than a client who visits twice.
*Decision:* **ADD** `Skeleton` and `ErrorState` (with retry) to `components/ui/`, then
**REFACTOR** the five screens onto them.
*Priority:* **P2**

---

**F25 — The client portal is hard-coded to one brand.**
*Evidence:* `client-expert/shared/src/styles/globals.css` — IE navy `#003152`, crimson
`#c8102e`; `ui-context.md` records this as Unit 34 **D7**, unresolved: *"the portal's palette is
hard-coded to one brand — EvalOS is multi-brand and XpertsPortal has no home in it."*
*Impact:* In a product whose first invariant is brand scoping, both external surfaces can serve
one brand. A second brand selling means an XpertsPortal client sees International Evaluations'
branding.
*Decision:* **MODIFY** — brand name and palette travel in the portal payload; the SPA reads its
tokens from there, as D7 already recommends.
*Priority:* **P2**

---

## Rejected ideas

Widgets and screens I deliberately did **not** recommend, and why.

1. **A Sales dashboard of tile-style KPIs (revenue booked, win rate, leaderboard).** Rejected.
   `RoleDashboard.tsx`'s argument is sound — a tile page summarising one board is a second view of
   the same data. What Sales needs is *queues* (meetings, overdue follow-ups, untouched deals) and
   *case status*, not a scoreboard. A leaderboard would also be the first screen in the product
   ranking people, which is a management decision, not a UI one.

2. **Sales/Marketing access to the production board or case detail.** Rejected in favour of a
   narrow read-only status panel (F1). `00b-ghl-operational-programme.md` §4 and `40` §5 draw the
   pipeline/case boundary deliberately, and `boardRules.ts` gives Sales `'none'` on every
   production stage. Widening a tier to solve a status question would spend an invariant on a read.

3. **A "pay now" button in the client portal.** Rejected. `41-client-portal-invoices.md` is
   explicit that invoicing stays GHL's and QuickBooks does the accounting; a second payment
   surface is a reconciliation liability. I did flag (F-journey step 7) that the read-only screen
   should at least *link* to GHL's own payment link — a link is not a payment surface.

4. **In-app draft editing or version diffing.** Rejected — `22-role-operations-ui.md` slice 3
   refuses it and no revision store exists. I recommended client-visible revision *history* (what
   was asked for, when), which is audit data that already exists, not a diff engine.

5. **An expert recruitment pipeline Kanban / outreach tracker for the ENM.** Rejected — refused
   twice in `22` for the same reason I would refuse it: no schema behind it. The ENM's real gap is
   the *open offers* queue, whose data (`expert_case_offer`) already exists.

6. **A client-facing progress bar / stepper over the twelve stages.** Rejected as drawn. `34` D5
   forbids the SPA holding a lifecycle, correctly. I recommended instead that the server send an
   ordinal alongside the `step` phrase it already sends — the projection stays server-side and the
   client renders a position, not a model.

7. **A notification bell in the client portal.** Rejected. Invariant 14's amendment licenses
   authentication mail only, and an in-app bell on a surface nobody has a reason to open is
   decoration. The real fix is the journey routing (F18) plus an eventual decision about
   client-facing mail — a product decision, not a widget.

8. **"Recently contacted" for Sales.** Deliberately ranked P2 and not recommended for the first
   pass. It is a nice-to-have that answers a question ("who did I speak to") the note stream
   already answers, whereas *untouched* opportunities answer a question nothing else does.

9. **Unifying the internal and portal design tokens.** Rejected. `ui-context.md` argues the
   divergence well — a client reviewing a letter once is not a coordinator on a nine-hour shift.
   I recommended the two *semantic* rules cross the boundary (RAG is status-only; tabular
   figures), which they already do, and that brand tokens become payload-driven (F25).

10. **Sortable, paginated tables everywhere.** Rejected for now. At 50–100 cases per brand per
    month, `22`'s deferral of TanStack Table with a written trigger (virtualization) is the right
    call. Sorting on the *expert roster* is the one place I would revisit first, and even that is
    P2.

11. **A staff-facing client-accounts admin screen.** Rejected — `42-client-accounts.md` §9 already
    answers it: support unblocks a client with the mint button that exists. A CRUD screen for
    accounts nobody administers is scaffolding.

12. **Bulk actions on the Cases inbox.** Specced in `22` slice 1 and not built. Ranked P2, not
    recommended now: assignment is a judgement per case (the quick action requires a CM *and* an
    expert *and* a rationale), and bulk-assigning judgement calls is how a queue gets rubber-
    stamped. Revisit if inbox depth routinely exceeds a screen.
