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
| Google service account for Drive | **Unit 13's last criterion, Unit 21, and now Unit 15 too** | One credential, three units — which makes it the single most valuable thing to chase. Unit 13 has been code-complete and stuck on it |
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

**A3 · Unit 16 — payout ledger.** The only substantial unit with **zero** external
dependency: a manual ledger, its own endpoints, no integration. It also unblocks Unit
17's money-out tiles, so it comes before dashboards rather than after.

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
| Google service account | **Unit 13's last criterion**, then **Unit 21** (client document upload), then **Unit 15** (expert portal + signed-letter upload) — 15 reuses 21's upload path wholesale, so build them back to back. This chain can interrupt Track A at any point |
| GHL outbound contract | **Unit 18** (outbound dispatcher, Handoff C) — and the email-channel decision resolves here |
| 10, 15 and 18 all done | **Unit 19** (background jobs) — genuinely last, because it is the clock behind hooks those units install. Re-read it: the advisory lock must be **session-scoped**, and the client chases are **wall-clock** while the escalation is business hours |
| 17 done | **Unit 20's anomaly half** (no AI needed). The AI half only if the compliance decision says yes |

### Why not simply follow the phase order

The phase order would put Unit 15 next, which is what the tracker said before this
plan. Two things changed. First, 05b, 16 and 17 are not blocked at all, so following
the numbering strictly would leave them idling. Second — and this is new — **Unit 15
stopped being blocked on its own dependency**: dropping the signature provider removed
the account, key, template and callback secret it was waiting on, and left it needing
only the Google service account that Units 13 and 21 already need. It is now a
one-credential unit that shares that credential with two others, and it shares most of
its code with Unit 21.

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
Builds: the two-column case view — documents (Drive link) / draft / expert on
the left, the timeline/audit trail on the right — with stage-action controls,
PM strategy notes, and the draft sub-status chips (PM review / client review).
Depends on: 04, 08.

### Unit 10 — Document checklist board + Coordinator flow
Builds: the Coordinator's checklist board (required/uploaded/missing status
against the Drive link), mark-docs-complete → push to PM, and the doc-collection
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

### Unit 13 — Redacted CV generation
Builds: template-based redacted profile generation (name/institution/contact
stripped), generated on demand, with full-profile release on payment. No object
storage — output is served on demand (or written to the case's Drive folder).
Depends on: 11.

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

### Unit 18 — Outbound webhook dispatcher + Handoff C (delivered)
Builds: the reusable outbound dispatcher (subscribes to the domain events
published since Unit 04; subscriber registry; HMAC-signed payloads; retry with
backoff; dead-letter; delivery log + replay) and its first live events —
`case.delivered` → GHL's inbound automation URL to start the review + referral
track and stamp closed value; client-notification triggers → GHL. Creates the
payout ledger entry in the same transaction and syncs delivered/active contacts
to GHL's suppression list.
Depends on: 04 (domain events), 16. (Confirm GHL subscriber URL + secret first.)

### Unit 19 — Background jobs consolidation
Builds: the full `job` package backed by the `scheduled_job` **run ledger** —
`@EnableScheduling`, a Postgres advisory lock per sweep so two instances cannot
double-fire, doc-collection reminders (24h/48h), the day-3 escalation, stage-SLA
escalations, expert sign 20h/24h alerts (which **prompt**, never reassign), and the
outbox sender absorbed from Unit 18 — on the Pacific business calendar.
Depends on: 05, 10, 15, 18.
**Five sweeps, not six**: retention/countdown timers left this unit — GHL owns
retention and the post-delivery review end to end.

### Unit 20 — AI widgets (later)
Builds: KPI anomaly detection (>15% vs 4-week rolling avg) and AI-enhanced expert
suggestion layered on top of the Unit 12 rule-based shortlist. Assist-only.
Depends on: 12, 17.
AI review of uploaded documents is **not** here and is not deferred — it is ruled
out; the Coordinator reviews uploads.

### Unit 21 — Client document upload (A07)
Builds: an upload control on the client portal, one file per checklist item,
streamed straight into the case's Drive folder — so the client puts their own
documents in and the Coordinator reviews what arrives. Reuses Unit 14's portal
token model, Unit 13's Drive client and Unit 10's checklist statuses; adds no
infrastructure and no new auth surface. Carries the upload trust boundary
(content-sniffed allowlist, size cap, per-token rate limit, generated filenames,
bytes never persisted by EvalOS).
Depends on: 10, 13, 14.

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

---

## Notes

- Automation rules from the CRM spec are covered across Units 04–21, and
  **`context/process-automation.md` is the register** — it maps every A-number to
  the event, the recipients, the owning unit and whether it is built yet. Read that
  rather than re-deriving coverage from this list. Rules A01–A06 (lead / sales /
  marketing) and A21's post-delivery scheduling are GHL's and out of scope.
- **Multi-tenancy is not a unit — it is a property of every unit.** From Unit 02
  onward, every scoped query filters by `brand_id`; brand resolution at Handoff A
  is by per-brand endpoint token.
- **No object storage, no mail server.** Documents are Drive links and file ids —
  including the signed letter, which the expert uploads into the case's Drive folder;
  staff alerts are in-app (Unit 06); clients are reached through GHL and experts
  through a scoped portal link. Two refinements: EvalOS **accepts** files (Units 21
  and 15) and streams them to Drive without storing any, and whether it ever sends
  mail itself is an **open decision** — see `context/process-automation.md`. Until it
  is taken, no mail dependency.
- **Webhook subsystem spans units**: inbound gateway built once in Unit 05 and
  **stays single-source (GHL)**; outbound dispatcher built once in Unit 18
  and delivers domain events published from Unit 04 onward.
- Open questions gate specific units (see `progress-tracker.md`): the **full
  brand list**, whether EvalOS **builds the sales/marketing dashboards** (default
  no — GHL), **StatCommand**, the **GHL webhook/API contract** (per-brand inbound
  secret + payload for Unit 05; outbound subscriber URL + secret and client-
  message capability for Unit 18), and **staff SSO** (optional/later). Resolve each
  before starting the gated unit. *(The Dropbox Sign callback secret was on this list
  until the signature provider was dropped; Unit 15 no longer has a gating question.)*
