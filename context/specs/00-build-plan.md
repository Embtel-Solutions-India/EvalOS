# EvalOS — Build Plan

The complete, ordered unit list. Each unit produces one visible, verifiable
result, stays inside one system boundary, and builds only on units before it.
Ordering follows: dependencies first, security before functionality, backend
before frontend wiring, UI shells before real data, install just-in-time.

Stack: Java 21 + Spring Boot + PostgreSQL (Spring Data JPA) backend, React +
Vite + Tailwind frontend, monorepo `backend/` + `frontend/`. Multi-brand:
row-level tenancy by `brand_id`, brand-scoped queries throughout.

Sequence maps to the roadmap: Units 01–10 = Phase 1 (structure the data),
11–17 = Phase 2 (connect the seams), 18–20 = Phase 3 (close the loop).

The `## Phase 3` heading below used to sit above Unit 17, contradicting that line
and putting Dashboards in a different phase depending on which part of this file
you read. The heading moved to Unit 18 rather than the sentence changing, because
the sentence is the one quoting the roadmap. Unit order is unaffected either way —
what the boundary decides is when Unit 17's open questions (dashboard ownership,
StatCommand) become blocking.

Generate a `specs/NN-name.md` for a unit just before building it.

---

## Execution sequence for v2.0 — what to build next, and why

The phase order above is the *dependency* order and is still correct. This section is
the **schedule**, and it differs, because the remaining units are not equally
blocked. Five of them wait on somebody outside this repo; four do not.

### Step 0 — ask for the five external things now, in parallel

None of these is code, all have lead time, and everything else queues behind them.
Requesting them is the highest-value action available and costs a day of somebody's
attention:

| Needed | Blocks | Note |
|---|---|---|
| ~~Google service account for Drive~~ **AWS credential + bucket for the S3 document store** | **Unit 13's last criterion, Unit 21, Unit 15** | **Unit 30 replaced the blocker rather than working around it.** One credential, three units — still the most valuable thing to chase, but it is now an AWS one the business already controls rather than a Google service account that never arrived. See `30-s3-document-store.md`; its open questions (b) key format, (d) PDF, (f) the portal contract should be answered before code starts |
| GHL outbound contract — subscriber URL, signing secret, and *whether GHL can send a client-facing transactional message on an EvalOS event* | **Unit 18**, and the whole email decision | That last clause is the one that decides invariant 14 |
| The real `opportunity.won` payload, signature header name, HMAC encoding | **Unit 05b's live run** — not its code | Build and unit-test 05b without it; only the end-to-end firing needs it |
| Anthropic key **plus a decision to send case data to a third party at all** | Unit 20's AI half | A compliance call, not a technical one. The anomaly half needs neither |

### Track A — buildable today, nothing external. Do these in order.

**A1 · The missing QC notification.** Gap **G2** in the register: `qc.approved` is
published and `qc-approve` is built, but `NotificationListeners.ROUTES` has no entry
for it, so a Coordinator learns a case is ready to deliver by looking at the board.
This is a **live operational hole in shipped code**, it is one route plus a test, and
it needs no spec. Highest value per line in the whole plan — do it first.

**A2 · Unit 05b — Case Creation v2.0.** Already specced. Until it lands, the context
docs describe a trigger the code does not implement, and that divergence is recorded
as deliberate but costs more the longer it stands. Code and tests need no external
input; the live hand-fired run waits on the payload confirmation from Step 0.
Re-read `05b` first — it gained two corrections after review (the `V24` index must be
scoped to open cases, and `refresh()` must overwrite `deal_value`).

**A3 · Unit 16 + 16b — payout ledger and weekly settlement.** The only substantial unit
with **zero** external dependency: a manual ledger, its own endpoints, no integration.
It also unblocks Unit 17's money-out tiles, so it comes before dashboards rather than
after. **Read `16b-weekly-settlement.md` alongside `16`** — the business charges per
draft and settles weekly, so a payment is its own table and 16's per-row form is
superseded. 16b also carries two things 16 assumed but that do not exist: `brand.currency`
and a payout term. Build them as one unit; splitting them would ship a payment model
that is already known to be wrong.

**A4 · Unit 17a — dashboards without charts.** The biggest remaining unit, and it
grew: it now carries the per-role operational contract and gaps **G3–G12** (delivery
queue, CM workload, deadline view, draft-review queue, coverage-gap alert,
onboarded-vs-target, the two dead-column traps, and the two missing quick actions).
Split it: **17a is every tile and view that needs no chart.**

**A5 · Unit 17b — the cycle-time chart.** Split out because it is the only part
blocked on the charting-library decision (`ui-context.md`), and that decision should
not hold up the other eleven widgets.

That is a lot of runway with no waiting. Track A is the default: **work A1→A5 and
interleave Track B as blockers clear**, rather than idling on a credential.

### Track B — slot in the moment its blocker clears

| When this arrives | Build |
|---|---|
| AWS credential + bucket | **Unit 30 first** (the S3 client, the key format, the presigned read), then **Unit 13's last criterion**, then **Unit 15** (expert portal + signed-letter upload). **Unit 21 changed shape**: the client upload now happens in the separate Client Portal, so what remains for EvalOS is *reading* a prefix and reconciling it against the checklist. This chain can interrupt Track A at any point |
| GHL outbound contract | **Unit 18** (outbound dispatcher, Handoff C) — and the email-channel decision resolves here |
| 10, 15 and 18 all done | **Unit 19** (background jobs) — genuinely last, because it is the clock behind hooks those units install. Re-read it: the advisory lock must be **session-scoped**, and the client chases are **wall-clock** while the escalation is business hours |
| 17 done | **Unit 20's anomaly half** (no AI needed). The AI half only if the compliance decision says yes |

### Why not simply follow the phase order

The phase order would put Unit 15 next, which is what the tracker said before this
plan. Two things changed. First, 05b, 16 and 17 are not blocked at all, so following
the numbering strictly would leave them idling. Second — and this is new — **Unit 15
stopped being blocked on its own dependency**: dropping the signature provider removed
the account, key, template and callback secret it was waiting on, and left it needing
only the storage credential that Units 13 and 21 already need. It is now a
one-credential unit that shares that credential with two others.

**Unit 30 changed which credential that is** — an AWS one instead of a Google service
account — and changed what Unit 21 still owes: the upload moved to the separate Client
Portal, so 15 no longer "reuses 21's upload path wholesale". It streams to
`case/{caseId}/signed/` through the S3 client that Unit 30 builds.

Dependencies still constrain: 16 before 17, 21 before 15, 18 before 19. Nothing here
reorders a real dependency; it only stops the schedule being decided by whichever unit
happens to be numbered next.

**Units 01–10 followed that rule; Units 11–20 did not.** All ten remaining specs
were written in one pass at the start of Phase 2, by decision, so the whole
remaining shape is on paper at once. The rule stays as written because it is the
right default — a spec written ten units early is written against code that does
not exist. So specs 11–20 are **drafts to be re-read and revised at the start of
their own unit**, not settled contracts, and 18–20 say so in their own headers.
**Several already carry corrections** — found while writing later specs, while
building the units they depend on, and in review — which is the failure mode the
just-in-time rule exists to avoid, caught early rather than at build time. Deliberately
not counted here: this sentence said "two" and named Unit 11's derived load and Unit
16's payout uniqueness, and was out of date by the next unit. A correction is marked in
the spec that carries it; that is the record, and a tally beside it is just a second
thing to keep in step.

---

## Phase 1 — Structure the data (the spine)

### Unit 01 — Project scaffold & config
Builds: Spring Boot (Maven, Java 21) service with a health-check endpoint,
PostgreSQL via Spring Data JPA, Flyway wired for migrations, `application.yml`
profiles + externalized config; React/Vite + Tailwind frontend with the design
tokens from `ui-context.md`; monorepo layout; `./mvnw verify` and `npm run
build` both green.
Depends on: nothing.

### Unit 02 — Multi-tenancy + Auth & RBAC/ABAC (security foundation)
Builds: the Brand entity + tenancy plumbing (mandatory `brand_id` on scoped
entities, a query-layer scoping mechanism that injects `brand + team + assignee`
predicates); Spring Security + JWT for internal staff; the six roles as
authorities (`GM`, `BRAND_MANAGER`, `PROJECT_MANAGER`, `PROJECT_COORDINATOR`,
`CASE_MANAGER`, `EXPERT_NETWORK_MANAGER`); method-security (`@PreAuthorize`);
and a reusable brand/ownership-check helper in the service layer. No feature
endpoints yet — just the guard rails. (No Head-of-Evals role, no interns.)
Depends on: 01.

### Unit 03 — Domain model & migrations
Builds: JPA entities + repositories + Flyway migrations for Brand, TeamMember,
ContactSnapshot (read-only), Case, Expert, PayoutLedger, DocumentChecklist,
Notification (in-app), and the append-only AuditTrail. `Stage` / `PayoutStatus`
/ `ServiceType` / `VisaCategory` / `Role` enums. Every scoped entity carries
`brand_id` + an audit hook. Field-level encryption `AttributeConverter` for the
single optional expert `payment_detail`. Compound indexes on
`(brand_id, team_id, assigned_to, stage)`, `(brand_id, deadline)`,
`(brand_id, sla_status)`.
Depends on: 02.

### Unit 04 — Case lifecycle service (state machine)
Builds: the 8-stage internal state machine (`DOC_COLLECTION → EXPERT_ASSIGNMENT
→ DRAFT_GENERATION → EXPERT_SIGNING → FINAL_DELIVERY → CLOSED`, plus exception
states `ON_HOLD_AWAITING_CLIENT`, `EXPERT_DECLINED_REMATCHING`,
`REFUND_REQUESTED`); declared-transition-only enforcement; `@Transactional`
transition methods; an audit entry on every transition; the pool→PM→CM
assignment model; SLA-status computation on the Pacific business calendar; the
GM-only refund transition (revenue reversal + pending-payout void + GHL signal);
and the brand-scoped case REST controller. Each transition publishes an internal
domain event for the outbound dispatcher (Unit 18).
Depends on: 02, 03.

### Unit 05 — Inbound webhook gateway + GHL opportunity handler (Handoff A)
Builds: the reusable inbound gateway (secret verification, **per-brand endpoint
→ brand_id resolution**, idempotency on the **source event id** scoped by brand,
raw-payload archival, handler routing, fast ack) and its first handler — GHL
`opportunity.won` → contact-snapshot sync, brand-tagged **paid** case creation at
`DOC_COLLECTION` in the brand pool, document-checklist open, PM/Coordinator pool
notification. **One inbound source, GHL** — this gateway was going to be reused by a
signature provider in Unit 15, and dropping that provider means it stays
single-source. This is the only path that
may create a case.
Depends on: 03, 04. (Confirm GHL payload + per-brand signing secret first.
`refund.requested` / `contact.updated` / `contact.created` recognized but deferred
or deliberate no-ops.)

The trigger has moved twice, and **Case Creation v2.0** is current: it is
**`opportunity.won`**, and the webhook *is* proof of payment, because GHL invoices
and collects before the opportunity is marked Won. The case is created **paid**, in
the PM/Coordinator pool; `contact.created` is a recognized no-op and no staff action
sets `paid`. See **`context/specs/05b-opportunity-won-intake.md`**.

Superseded readings of this paragraph, for anyone reading old code or commits: the
original draft said `payment.confirmed` and keyed idempotency on the invoice id;
Unit 05a said "contact created, not payment confirmed" with an unpaid case and a
`mark-paid` staff act. Both are history. `architecture.md`'s Handoff A and spec 05b
are what the code implements.

### Unit 06 — In-app notification center
Builds: the Notification service + brand-scoped staff notification center
(create/list/mark-read), fed by domain events (assignment, SLA breach,
escalation, KPI flag). Client-facing notifications are emitted as domain events
for GHL to deliver — EvalOS sends no email.
Depends on: 04.

### Unit 07 — App shell + role/brand-scoped dashboard routing (UI shell)
Builds: the internal React app shell (left nav, top bar with global date filter,
brand switcher — all-brands/filter for GM, locked for everyone else, notification
bell), role-scoped routing, and empty/placeholder dashboard states.
Depends on: 02, 06.

### Unit 08 — Production Kanban board
Builds: the stage-column board wired to the case API (EvalOS stages: Doc
Collection · Expert Assignment · Draft/Report · Expert Signing · Final Delivery,
plus exception lanes), RAG deadline badges, brand + role-filtered views (pool +
unassigned queue for GM/BM/PM; own docket for CM).
Depends on: 04, 07.

### Unit 09 — Case detail page
Builds: the two-column case view — documents (S3, opened via a presigned link — Unit 30;
was a Drive link) / draft / expert on
the left, the timeline/audit trail on the right — with stage-action controls,
PM strategy notes, and the draft sub-status chips (PM review / client review).
Depends on: 04, 08.

### Unit 10 — Document checklist board + Coordinator flow
Builds: the Coordinator's checklist board (required/uploaded/missing status against the
objects under the client's own S3 prefix — Unit 30; was the Drive link),
mark-docs-complete → push to PM, and the doc-collection
SLA/reminder hooks. Client chase messages are emitted as domain events for GHL
to send (no EvalOS email).
Depends on: 04, 09.

---

## Phase 2 — Connect the seams

### Unit 11 — Expert database (ENM) + sheet upload
Builds: the Expert entity/repository detail (brand-scoped; field-tag taxonomy,
letter types, tier, availability, quality score, fee, turnaround/decline
history), the optional encrypted `payment_detail`, ENM CRUD endpoints, the
availability board, and **bulk sheet upload (CSV/XLSX import mapped to fields)**
as the roster's primary maintenance path.
Depends on: 03.

### Unit 12 — Match scoring engine (assist mode)
Builds: the rule-based ranked top-3 shortlist service (field match + letter-type
experience + acceptance rate + current load), brand-scoped, surfaced to the PM
at assignment. Suggests only; a human confirms. (AI-enhanced ranking/anomaly
detection is Phase 3.)
Depends on: 11, 04.

### Unit 13 — Redacted CV generation — **REMOVED (2026-09-02)**
Deleted: `RedactedProfileService`, `ExpertProfileController`, `RedactedProfilePanel`,
`redactionRules`, the `REDACTED_PROFILE` document kind and the client portal's
`expertProfile` / `expertReference` fields. `V33` narrows `case_document`'s kind CHECK.
**`AuditAction.EXPORTED` is deliberately kept** — the audit trail is append-only, its rows
can never be rewritten, and an enum that cannot read a value some historical row carries
would fail on read. A retired audit action stays readable forever.

**The client is now told nothing about the expert at all**, which is the stronger position:
there is no redaction to get wrong and no generated document to keep anonymous. The portal
test asserts it by putting a very identifiable name on the case and grepping the wire.

**It kills Unit 30's open question (d).** Drive's export produced the PDF for free and S3
converts nothing — but the redacted profile was the only thing that needed converting, so
removing this unit removes the PDF problem rather than solving it.

`mayMintPortalLink` moved to `client-portal/portalRules.ts` with its test. It was Unit 14's
rule and only shared a file with this one.

### Unit 14 — Client draft-review portal
Builds: the separate, scoped filter chain for passwordless client access (link
delivered via GHL), the single-page draft view, approve / request-revisions
actions, and read-receipt tracking. Draft-review only — no source-doc upload.
Depends on: 02 (separate auth surface), 04.

### Unit 15 — Expert portal + Handoff B + sign-off
Builds: the separate scoped filter chain for expert access (CM-shared link), the
single-column assigned-case view (draft + evidence + goal), the accept /
request-evidence (opens client task) / decline (→ `EXPERT_DECLINED_REMATCHING`)
paths, **the sign step — download the letter, upload it back signed** (Unit 21's
upload path with `audience = 'EXPERT'`, PDF only, attestation required), the
provenance record (hash sent + hash received + attestation + `EXPERT` audit row), and
the 20h/24h SLA alerting + the human-fired reassign operation.
Depends on: 12, 14, **21** (the upload path it reuses).
**No e-signature provider** — that decision removed this unit's account, API key,
template, callback secret, inbound handler and SDK dependency, and with them its two
gating questions. It now needs only the Google service account that 13 and 21 need.

### Unit 16 — Payout ledger (manual)
Builds: payout entry auto-created (status Pending) when a case reaches
Delivered, tied to case + invoice; a **manual form** for the responsible team
member to record method/reference/amount/date and mark Paid/Confirmed; status
tracking; the weekly batch view; and expert-facing payout status in the portal.
Ledger only — no disbursement rail, no payment-platform integration.
Depends on: 03, 11.

### Unit 17 — Dashboards (read models)
Builds: the production-side role dashboards (GM cross-brand; Brand Manager, PM,
Coordinator, Case Manager, ENM within brand) — money-in vs. delivered (open
liability), cycle time by stage, expert utilization & acceptance rate, review
capture — reading precomputed read models refreshed on events.
Depends on: 04, 11, 16.

---

## Phase 3 — Close the loop

### Unit 18 — Outbound dispatcher + Handoff C — **REMOVED (2026-09-02)**
Never built: no `webhook_delivery` table, no dispatcher, nothing to delete.

**There are two handoffs now, not three.** A (GHL → EvalOS, inbound) and B (internal,
client approval → expert). **EvalOS emits nothing outbound at all** — its integration
surface is inbound GHL webhooks and read-only pulls of GHL's funnels. Domain events still
publish, but in-process only, and the notification centre is their sole consumer.

**What survives:** the payout ledger entry is still created on delivery, inside
`deliverToClient`'s transaction. That was always Unit 16's.

**What is genuinely lost, and must not be discovered later:** nothing tells GHL a case was
delivered, so the review sequence, the referral track and the suppression-list sync are
started **by hand in GHL**. And the document chase and "your draft is ready" have no
automated route to the client — either done by hand, or they become states the client sees
in the portal, which is a product decision still open.

**Invariant 14's "sends no email" stops being a pending decision** and becomes the
architecture: there is no outbound channel to argue about.

### Unit 19 — Background jobs consolidation
Builds: the full `job` package backed by the `scheduled_job` **run ledger** —
`@EnableScheduling`, a Postgres advisory lock per sweep so two instances cannot
double-fire, doc-collection reminders (24h/48h), the day-3 escalation, stage-SLA
escalations, expert sign 20h/24h alerts (which **prompt**, never reassign), and the
outbox sender absorbed from Unit 18 — on the Pacific business calendar.
Depends on: 05, 10, 15, 18.
**Five sweeps, not six**: retention/countdown timers left this unit — GHL owns
retention and the post-delivery review end to end.

### Unit 20 — AI widgets — **REMOVED (2026-09-02)**
Never built. Removed rather than deferred, and the difference matters: a deferred unit
invites "we were going to do this anyway", and this one will be proposed again.

**Now invariant 15** in `architecture.md`: no AI makes a production decision, and there is
no AI in the system. Verification, expert selection, drafting, review, client approval,
reassignment and QC are human judgements. Notifications, transitions, timestamps,
versioning, audit and dashboard arithmetic are unaffected.

**Unit 12's match engine is not an exception** — four declared factors, inspectable
arithmetic, never auto-assigns, no model. Making it "smarter" by asking something to reason
about a case is a reversal of invariant 15 and needs writing down first.

### Unit 21 — Client document upload (A07)
**⚠ Reshaped by Unit 30 and never built in its original form.** The upload no longer
happens in EvalOS: clients upload in the **separate Client Portal**, which writes to the
S3 document store under the client's own id. What remains for EvalOS is the *reading*
half — list `client/{clientId}/`, let the Coordinator associate an arrived object with a
checklist item, and review what came in. Unit 10's checklist statuses and Unit 14's
portal token model are unchanged and still do the work.

**The upload trust boundary moves with the upload** — content-sniffed allowlist, size cap,
rate limit, generated filenames — and is now the Client Portal's to enforce. That is a
real transfer of responsibility and must be confirmed with whoever builds that portal
(open question (f)), not assumed because the requirement moved it off our side.
Depends on: 10, 14, 30.

### Unit 23 — Case notes, and routing intake to the PM
Builds: the Project Manager as the front door for incoming work — the pool lane
leaves the GM's board, `/inbox` and `/checklists` leave their sidebar (nav only,
no backend gate narrowed), and `assign-pm` admits the PM so they claim a pooled
case from their own inbox and then staff the coordinator and case manager. Plus
**notes on a case**: any caller the case scope admits appends free text to the
append-only trail as `NOTE_ADDED`, and `Timeline` becomes *Notes & timeline*. The
GHL won-opportunity payload carries an optional `notes` onto the `CREATED` row, so
a case arrives with what sales wrote on it. Adds one column-equivalent of scope
(`ScopePredicate.Fields.unteamedVisible`, set on cases only) and **no new table** —
a note is an audit row for the reasons in `architecture.md`'s storage model.
**23a** then removed `GM_OR` from `draft/pm-approve` / `draft/pm-return` and made
`/drafts` PM-only — the one place the GM is *excluded* from a transition rather
than added to it, because approving a Case Manager's draft is the judgement of the
PM who assigned it.
Depends on: 04, 08, 09, 22.

### Unit 24 — Marketing: the Google Ads funnel (GM)
Builds: the GM's one view of GHL's front of house — the **Google ADS Pipeline** as
a chevron funnel (deals and value per stage, share of pipeline) with the sources
behind it. First **read** integration against GHL's public API: two calls, a
five-minute cached payload, `opportunities.readonly` and no write method anywhere.
**Resolves the open question this list has carried since Unit 17** — sales/marketing
dashboards were defaulted to GHL-native, and the answer is now *one read-only GM
screen in EvalOS, everything else stays in GHL*.
Invariant 2 is intact: EvalOS reads the funnel, it does not run marketing. Nothing
is persisted — there is no `ghl_opportunity` table and there must not be.
**The one screen in EvalOS that is not brand-scoped, deliberately**: it reads one
GHL location that the brands share, so no `brand_id` predicate exists that could
narrow it — hence GM-only, hence no `brandId` parameter, hence the Brand Manager
is excluded. See `24-marketing-google-ads-funnel.md` for the full argument and for
the process note that this spec was written **after** the code.
Depends on: 07, 17, 22.

### Unit 26 — Marketing: the email funnel (GM)
Builds: a second GM screen over the same GHL location — **Shivangi's Email
Marketing**, the email acquisition channel — through Unit 24's client, service,
cache and card system. A `Funnel` enum (`ADS`, `EMAIL`) keys into configured
pipeline names; the cache key becomes `(funnel, range)` so two identically shaped
payloads can never answer for each other; one React component serves both screens.
**Answers the question Unit 24 explicitly left open** — "a second marketing screen
is a new question" — as *yes for a second reading of a pipeline in the location
EvalOS already reads, on the same terms*. Invariant 2 is intact: still no write, no
persistence, no `ghl_opportunity` table. Still GM-only and still not brand-scoped,
for Unit 24's reason unchanged; **Unit 25a re-scopes all three screens together** (Unit 27 added the third).
Also fixes a defect this pipeline exposed: it holds ~11.4k opportunities a year
against a 5,000-row page cap, so a truncated read now reports `truncated` and the
screen says every figure is a floor, instead of stating 5,000 as the total.
See `26-marketing-email-funnel.md`.
Depends on: 24.

### Unit 27 — Sales: the sales pipeline (GM)
Builds: the third GHL pipeline read — the sales team's own working funnel
(*Aditya's pipeline*) — under a **new `Sales` nav group**, GM-only.
`GET /api/marketing/sales-pipeline`, `evalos.ghl.sales-pipeline-name`, and
`Funnel.SALES`. **Total cost: a property, an enum constant, a route method, a nav
entry and a union member — no new class on either side**, which is the return on
Unit 26's shape and was the explicit prediction left in `application.yml`.
Under **Sales** rather than Marketing on purpose: the other two are campaign
funnels, this is a salesperson's pipeline and carries stages they do not
(`Meeting booked`, `Invoice sent`, `Refund` — all `OPEN`, no special cases). The
API route stays under `/api/marketing/` — a stated naming debt, smaller than
splitting one integration across two controllers.
**The substantive finding is a defect this pipeline exposed**: GHL stores its name
with **two spaces**, so the single-space spelling a human types into config did
not match and the screen answered 502 — a failure whose cause is invisible in
both places anyone would look. `GhlPipelineClient` now collapses whitespace runs
before matching, in the *shared* client so all three funnels benefit; a name
differing by a real character still fails loudly, which is the point of matching
by name at all. Verified live: the single-space name resolved to the real
pipeline with all nine stages.
Invariant 2 intact — no write, no persistence, no `ghl_opportunity` table.
Still GM-only and not brand-scoped, for Unit 24's reason unchanged;
**Unit 25a now re-scopes three screens, not two.**
See `27-sales-pipeline.md`.
Depends on: 24, 26.

### Unit 28 — Dashboard date filters (calendar, completed, custom)
Builds: the shell's period control becomes four **calendar-to-date** buttons
(Today / This week / This month / This year), a dropdown for **Last month** and
**Last year**, and a **date-to-date** range on two native date inputs.
`range=last-month|last-year|custom` plus `from`/`to` on `/api/metrics/pm` and all
three `/api/marketing/*-pipeline` routes.
**The substantive change is a split, not an addition.** This one value was read in
two opposite directions — backwards by the dashboards and the GHL screens, forwards
by `BoardView` through `dueBeforeFor` — a collision `ui-context.md` had recorded for
two units and that had already left the production board unfiltered once. Every new
option breaks the sharing outright: `last-month` as a "due before" cutoff returns
every open case, and an interval is two edges where a cutoff needs one. So the board
now owns `DeadlineWindow` (`week|month|year`, forward) and the shell owns `DateRange`
(seven periods, backward) — **enforced by the type**, not by a comment.
`DateRange` also stopped carrying `int days`: a to-date period has no fixed width and
`last-month` does not end today, so `DateWindow` resolves a name into inclusive days
and owns that arithmetic alone.
**V26 re-keys `ghl_funnel_cache` on the resolved window** (`window_key`), because every
custom period is *named* `custom` and name-keyed rows would serve one period's figures
for another — undetectable on screen, identical payload shapes. Rows are deleted rather
than translated: which window a row covered depends on the day it was written, which
the row never recorded.
**Behaviour change on live screens:** every dashboard figure moves — `month` was the last
30 days and is now since the 1st. The labels were the half that was already lying.
See `28-dashboard-date-filters.md`.
Depends on: 08, 17, 24, 26, 27.

### Unit 29 — Sales desk — **BUILT (2026-08-29), REMOVED (2026-09-02)**
The sales desk and the `SALES_EXECUTIVE` role are gone from the codebase. Deleted:
`SalesController`, `SalesBoardService`, `GhlSalesClient`, `features/sales/`, the
`/sales/board` nav entry, `Role.SALES_EXECUTIVE`, `team_member.ghl_user_id` and its
mapping endpoint, and the V906 seed login. `V30__drop_sales_executive.sql` reverses V29's
two constraint rewrites and drops the column; V29 itself stays, because an applied
migration is never edited or deleted.

**What the removal cost, and what it did not.** It cost one migration and no data
reconciliation at all — the unit's central decision was that **nothing is stored here**,
no `ghl_opportunity` table and no sales column anywhere, so there was nothing to unwind.
The decision that made a write safe is the same one that made the write cheap to remove.

**Invariant 2 reverts.** Unit 29 was the only unit that has ever cost an invariant:
"EvalOS never runs sales" died and the boundary moved from read-vs-write to custody. Both
come back. `GhlHttp` now exposes no `post`, `put` or `delete` — EvalOS reads GHL and
writes nothing to it, asserted by `GhlHttpTest` rather than claimed by a comment.

**What survives the unit.** `GhlHttp` itself stays extracted: it was pulled out to hold
one shared rate-limit pacer, and that limit is a property of the GHL *location*, not of
whoever is reading it this month. Folding it back into `GhlPipelineClient` is how the next
client silently gets a pacer of its own, so the shared-pacer test now runs across two
instances of the one remaining client. The PII widening reverts with the board that needed
it: `SalesOpportunity` carried a contact's name, email and phone under `/api/sales/**`, and
that endpoint tree no longer exists.

`29-sales-desk.md` is kept as the record of a decision that was made, shipped and undone.
Unit 27's `/sales/pipeline` — the GM's *read* of the same funnel — is untouched.

### Unit 30 — S3 document store + shared client identity
**SPECCED 2026-09-02, not built.** Replaces Google Drive with an S3 bucket for every
document: client uploads (scans/images), draft PDFs, the redacted expert profile and the
expert's signed letter. The **Client Portal is a separate frontend whose backend is
EvalOS** — it holds no AWS credential and uploads by calling EvalOS's portal API, which
streams to S3. Reads are 5-minute presigned URLs issued after the case's scope check.
**One client across GHL, the Client Portal and EvalOS**, keyed on GHL's contact id, with
email consistent alongside but still a fallback key only (V27). **Amends invariant 14 and
deletes "No object storage".** Unblocks Units 13, 15 and 21, all stuck on a Google service
account that never arrived. Two capabilities Drive was quietly providing must be repaid:
PDF conversion, and A12's inline draft comments.
See `30-s3-document-store.md`. Open before code: (b) per-brand key format, (d) PDF, (f) the
portal contract.
Depends on: 02, 04, 10, 14.

### Unit 31 — Production lifecycle v2 (twelve stages, one owner each; eight board columns)
**SPECCED 2026-09-02, not built. All six of its open questions are answered.** The
five-stage pipeline with sub-status chips becomes **twelve explicit stages**, each with one
owner, one primary action, one event and one next owner — drawn as **eight board columns**,
since two stages share one only where they share an owner. Adds the two transitions the workflow needs and the state machine lacks: **`qc-fail`**
(a failed final QC currently has nowhere to go) and **`send-to-expert`** (which is what
should start the 24-hour signing SLA — today it runs from stage entry, so an expert sent
the letter late is charged for the delay). Adds a `case_document` version history; a draft
may not be submitted without a file and no version is ever overwritten. **Manual by
decision — no AI in any production decision.**
**Reverses spec 08's derived-grouping decision**, and says why: a chip states what state
work is in, not whose turn it is.
See `31-production-lifecycle-v2.md`. **Amends 04, 08, 09, 10, 14, 15, 17, 22** — it is a
state-machine change and the blast radius is wide.
Depends on: 04, 30.

### Unit 32 — PM notes panel + draft status board
**SPECCED 2026-09-02, not built.** Small — both surfaces exist in part and neither needs a new
subsystem. Two gaps: the PM's **expert selection rationale** becomes its own column (different
lifetime from case strategy — it is rewritten per expert and Unit 31 made reassignment a normal
path; different audience — the ENM reads it and the CM does not; and it is the evidence for "why
this expert" that gets asked for after something goes wrong), and the PM's return comment is
**stamped on the draft version** (`case_document.review_comment`) instead of living only in the
audit trail. Angle and key points stay one field: they are one act of writing.

**Closes A12 in the sense EvalOS can.** Comments *per version* — yes. Comments *positioned inside
the document* — no; that was Drive's feature and needs a viewer, not a migration. The register now
says "partly" with the distinction written out.

**Do not join a comment to a version by timestamp.** Two rapid review rounds would attach the wrong
comment to the wrong version, silently and plausibly. The transition writes the column.
See `32-pm-notes-and-draft-status.md`.
Depends on: 09, 22, 23, 31.

### Unit 25 — GHL OAuth connection (per brand)
Builds: the GM connects a brand to a GHL sub-account from inside EvalOS, replacing
Unit 24's hand-pasted Private Integration Token. A brand-scoped `ghl_connection`
row holds the grant; the refresh token is the **second encrypted column in EvalOS**
and that needs sign-off, because `code-standards.md` currently states there is only
one. `state` nonce on the callback (single-use, constant-time compare, brand taken
from the state row and never from the request), refresh serialized with
`SELECT … FOR UPDATE` because **GHL rotates the refresh token** and two instances
refreshing concurrently would otherwise retire each other's grant.
**No dual path** — the PIT is deleted, which is free only because Unit 24 has never
run live. One permitted path in `SecurityConfig`, no third filter chain.
Its live connection also closes **Unit 24's** one outstanding acceptance item.
The follow-on (**25a**) re-scopes the funnel — **all three GHL screens: Unit 24's,
Unit 26's and Unit 27's**: `brandId` becomes legal, the Brand Manager is admitted, and invariant 1's stated exception is removed. (It was four while the sales desk existed, and that screen's re-scoping was the one that changed shape rather than just gaining a parameter. The desk is gone; three again.) Deliberately not
in 25 — a credential's lifecycle and a screen's role list are different boundaries.
Depends on: 02, 24.

### Unit 33 — The full record: applicant, case discipline, expert dossier — BUILT
Builds: the record EvalOS holds becomes the record the business holds. An audit of
`IE_Case_Sample_Data.xlsx` and `IE_Expert_Sample_Data.xlsx` against V1–V34 found
**19 of 36 expert facts absent** — degree, position, affiliation type, location,
LinkedIn, supported visa categories, publications, citations, h-index, patents,
awards, memberships, editorial roles, languages, rush capability, `IE-EXP-###`,
turnaround — and **no applicant name on any case**, which is a correctness gap the
moment a client is a law firm rather than an individual. Nineteen columns widen
`expert` (**no child table** — 1:1, no history, no second implementation);
`evalos_case` gains `applicant_name`, `field_of_expertise` and `rfe_date`.
**It reverses Unit 12's documented omission** of a case field tag: the objection was
to an *intake source*, not to persistence, and the tag the PM types at match time is
now stored by the assignment. `FieldTag` gains 11 values — its 28 were drawn for
credential-evaluation degree fields and **10 of the 22 disciplines in the expert
sheet cannot be spelled**, so those experts score zero on a 40-point factor.
`ServiceType`, `LetterType`, `ClientType`, `VisaCategory` gain the values the sheets
use; **the V18 CHECKs move with the enums** or the app writes rows the database
rejects. `last_active_date` is **derived** from `expert_case_offer`, never stored.
List stays lean, detail shows everything. Still no payment column, ever.
See `33-case-and-expert-dossier.md`.
Depends on: 11, 12, 31.

---

## Notes

- Automation rules from the CRM spec are covered across Units 04–21, and
  **`context/process-automation.md` is the register** — it maps every A-number to
  the event, the recipients, the owning unit and whether it is built yet. Read that
  rather than re-deriving coverage from this list. Rules A01–A06 (lead / sales /
  marketing) and A21's post-delivery scheduling are GHL's and out of scope.
- **Multi-tenancy is not a unit — it is a property of every unit.** From Unit 02
  onward, every scoped query filters by `brand_id`; brand resolution at Handoff A
  is by per-brand endpoint token. **The three GHL reads (Units 24, 26, 27) are the only
  screens that are not scoped, and they are an exception with a stated reason rather than a
  gap**: they read a GHL location EvalOS cannot attribute to a brand, so no `brand_id`
  predicate exists that could narrow them — which is why all three are GM-only and take no
  `brandId`. **Unit 27's separate `Sales` nav heading does not change this**: a different
  heading over the same `location-id` is still unattributable. Read the rule as *every query
  over EvalOS rows*, and treat a new unscoped query over EvalOS rows as the defect it still is.
  **25a's re-scoping sweep covers these three.** It briefly covered a fourth, Unit 29's sales
  board, which has been removed along with the `SALES_EXECUTIVE` role that worked it.
- **Object storage as of Unit 30, and EvalOS still hosts nothing in it.** Documents are
  **S3 object keys**, read through 5-minute presigned URLs. Client documents are written by
  the **separate Client Portal** under `client/{clientId}/` — EvalOS's credential is
  read-only there — and EvalOS's own artefacts live under `case/{caseId}/`. The line here
  used to say "no object storage": that half is now false and the half that matters,
  **EvalOS stores no bytes**, is unchanged and still a test. The one upload EvalOS still
  accepts is the expert's signed letter (Unit 15), which **streams**. Staff alerts are
  in-app (Unit 06); clients are reached through GHL and experts through a scoped portal
  link. **No mail server** — whether EvalOS ever sends mail is still an **open decision**
  (`context/process-automation.md`); until it is taken, no mail dependency.
- **Webhook subsystem spans units**: inbound gateway built once in Unit 05 and
  **stays single-source (GHL)**; outbound dispatcher built once in Unit 18
  and delivers domain events published from Unit 04 onward.
- Open questions gate specific units (see `progress-tracker.md`): the **full
  brand list**, **StatCommand**, the **GHL webhook/API contract** (per-brand inbound
  secret + payload for Unit 05; outbound subscriber URL + secret and client-
  message capability for Unit 18), and **staff SSO** (optional/later). Resolve each
  before starting the gated unit. *(The Dropbox Sign callback secret was on this list
  until the signature provider was dropped; Unit 15 no longer has a gating question.)*
