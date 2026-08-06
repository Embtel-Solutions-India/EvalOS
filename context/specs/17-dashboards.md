# Unit 17 — Dashboards (read models)

**Phase:** 2 — Connect the seams — final unit
**Depends on:** 04 (the lifecycle and its timestamps), 11 (the roster), 16 (money
out)
**Unlocks:** 20 (anomaly detection reads these numbers)
**Gating open questions:** three, all listed in the tracker and none of them
blocking the metrics the design actually names.
1. **Sales/marketing dashboards** — assumed **GHL's, not built here**. This unit
   builds production-side dashboards only. Confirm; the default is the assumption.
2. **StatCommand** — undefined. The standing instruction is not to build an
   integration for it until it is specified, and this unit does not.
3. **Review capture** cannot be fully measured inside EvalOS — see the metric
   definition below. This one changes what the tile can honestly say.

## Goal

`features/dashboards/RoleDashboard` has been a placeholder since Unit 07. This unit
fills it: each role sees the numbers their job turns on, scoped to what they can
see, and the GM sees them across brands.

**Verifiable result:** each of the six roles opens `/dashboard` and gets figures
that are correct for their scope and that **agree with the screens beside them** —
the board's case counts, the checklist board's queue, the payout ledger's totals.
A Brand Manager's numbers cover their brand; the GM's cover all brands and narrow
with the brand switcher; the global date filter applies to every time-bounded
figure.

## In scope

- The four metric families the design names, defined precisely enough to be
  testable.
- The role dashboards, scoped and date-filtered.
- The storage decision below.

## Out of scope

- **Sales, marketing, lead, ad-attribution and quoting metrics** — GHL's
  (invariant 2). Not built, not proxied, not embedded.
- **StatCommand.** Undefined; no integration.
- Custom report building, CSV export, scheduled emailed reports (no mail server —
  invariant 14).
- Any new source of truth for money. Every figure here is derived from
  `evalos_case` and `payout_ledger`; nothing is typed into a dashboard.

## The storage decision — read models vs. computing live

The build plan and `architecture.md` both say "precomputed read models refreshed on
events". **Stated as a decision to take at build time, with a recommendation,
because the scale in the NFRs argues the other way.**

`architecture.md`'s own non-functional target is **50–100 cases per brand per
month** — a few thousand rows in `evalos_case` after a couple of years, with two
brands. Every metric below is a `GROUP BY` over a few thousand rows on indexed
columns. Postgres answers that in single-digit milliseconds.

Against that, an event-refreshed read model buys latency nobody needs and costs:

- **A second source of truth for money.** A read model that misses one event
  reports the wrong open liability, and the whole point of invariant 5 is that this
  figure is right.
- **The staleness class of bug the tracker already has three instances of** — the
  header contradicting the instrument (`allInsideSla`), the chip contradicting the
  transition, the "N ready for the PM" count. A cached metric beside a live board is
  the same failure with a longer fuse.
- Refresh, backfill and invalidation code, none of which exists yet.

**Recommendation: compute live, in one service, with the aggregates pushed into
SQL** (`GROUP BY`, not Java loops over fetched rows). Add a materialized layer
**when a measurement shows it is needed**, not before — and if it is added, it is a
cache in front of these same functions, so the live query stays the definition.
Marked in the tracker as a deviation from the build plan's wording, with this
reasoning, so it is a decision on the record rather than a shortcut.

If the answer is "build the read models anyway", the metric definitions, the API
and the UI in this spec are unchanged — only where the numbers come from changes.

## The metrics

Every figure is computed from the caller's **already-scoped** case read, so a
dashboard cannot see further than the board does.

**Each metric names its own date column and its own scope, because they do not share
one.** "The date filter bounds figures by the timestamp named in each definition" was
the whole instruction and no definition below named one, which leaves the most
consequential choice on the screen — what "this month's revenue" means — to whoever
writes the query. Two figures on one tile keyed to different timestamps will not add
up, and nobody will be able to say why. So, per figure:

| Source | Date column | Scope path |
| --- | --- | --- |
| Money in (`evalos_case`) | `paid_at` — when the money arrived, not `created_at`. A case created in March and paid in April is April's revenue | the caller's scoped case read |
| Delivered / recognized (`evalos_case`) | `delivery_date` — recognition is a delivery event, so Recognized and Collected legitimately fall in different months for one case, and that gap **is** the open liability | same |
| Cycle time (`audit_event`) | the `STAGE_CHANGED` row's `created_at` | brand-scoped **through the case**, never through `audit_event.brand_id` — that column is null for every action the GM takes |
| Money out (`payout_ledger`) | `due_date` for what is owed, the `PAID` transition's date for what went out. Never one column for both | the ledger's own `brand_id` |

A figure with no rows renders **0**, not blank and not "—"; an empty month is an
answer, and a tile that goes blank reads as broken.

**Stale, and to be settled when this unit is actually built:** the `NOT paid` figures
below predate Case Creation v2.0. Unit 05b creates every case **paid** — spec 19 already
states "every case is paid" — so "Unpaid pipeline" describes a population that no longer
exists. Either it is always zero and should come off the tile, or the business wants
quotes-not-yet-won on this screen, and those live in GHL and are not EvalOS's to report.

### 1. Money in vs. delivered (open liability)

Invariant 5: revenue is recognized on **paid *and* delivered**.

| Figure | Definition |
| --- | --- |
| Collected | `SUM(deal_value)` where `paid AND NOT refunded` |
| Recognized | `SUM(deal_value)` where `paid AND delivery_date IS NOT NULL AND NOT refunded` |
| **Open liability** | `SUM(deal_value)` where `paid AND delivery_date IS NULL AND NOT refunded` — money taken for work not yet delivered, i.e. refund exposure |
| Unpaid pipeline | `SUM(deal_value)` where `NOT paid` — **shown separately and never added to the other three.** Since Unit 05a a case can be worked with no money behind it, and a quote is not revenue |
| Refunded | `SUM(deal_value)` where `paid AND refunded` — **its own figure, and in none of the three above.** Money that came in and went back out |
| Money out | `SUM(amount)` from `payout_ledger` by status (Unit 16) |

**Collected excludes refunds, and that is a correction to an earlier draft of this
table.** With `Collected = SUM where paid` and both of the figures under it
excluding refunds, a refunded case sat in Collected and in neither Recognized nor
Open liability — so the three did not add up, while the line about unpaid pipeline
"never added to the other three" implies they do. Worse, it read money that had
been handed back as still collected, which is the opposite of invariant 5's "a
GM-approved refund reverses recognition." With the filter added,
**Collected = Recognized + Open liability** exactly, and Refunded is shown beside
them rather than hidden inside one of them.

`deal_value` is role-restricted: `CaseController.SEES_DEAL_VALUE` is the package-
private list Unit 08 made shared so the board and the detail could not disagree.
**The dashboard projects through that same list** — a third copy is how a Case
Manager ends up seeing brand revenue on one screen. A role not on it gets the
count-based tiles and no money tiles at all, not zeroes.

### 2. Cycle time by stage

Median and p90 business hours spent in each stage, from the audit trail's
`STAGE_CHANGED` rows — **not** from `stage_entered_at`, which only remembers the
current stage.

**How an interval is built.** A `STAGE_CHANGED` row marks a boundary, not a duration,
so the figures come from *pairs* and the pairing rules have to be stated or every
implementation will differ:

- One interval runs from the row that **entered** a stage to the next `STAGE_CHANGED`
  on that case, whatever stage it names.
- The **current stage is open and excluded.** A case sitting in `DRAFT_GENERATION`
  right now has no closing row; counting "now" as the end mixes finished work with
  work in progress and makes the median drift every time the page is refreshed.
- The **first stage has no entering row** — a case is created at `INTAKE` and the
  trail's first `STAGE_CHANGED` is the one that *leaves* it. Use the case's
  `created_at` as that interval's start.
- **A stage can be entered more than once** (`EXPERT_DECLINED_REMATCHING` sends a case
  back). Each visit is its own interval and they are **not** summed into one — a case
  that passed through `EXPERT_ASSIGNMENT` three times contributes three data points,
  because that is three separate waits and the median should feel all of them.
- Rows are read **oldest first** and paired in that order;
  `findByObjectTypeAndObjectIdOrderByCreatedAtAsc` already returns them that way.

- On `BusinessCalendar`, so "48 hours" means two working days and a case that sat
  over a long weekend is not reported as slow.
- **Median, not mean.** One case stuck in `ON_HOLD_AWAITING_CLIENT` for three
  months drags a mean into meaninglessness, which is exactly when somebody stops
  trusting the tile.
- Time in an exception state is **excluded from the stage's figure and reported
  separately**, matching `SlaCalculator`, which stops the clock in an exception
  state. A stage's cycle time should measure EvalOS's work, not how long a client
  took to answer.

### 3. Expert utilization & acceptance rate

| Figure | Definition |
| --- | --- |
| Utilization | active cases per expert / roster capacity, from `ExpertLoadService` (Unit 11) — **never `current_active_count`** |
| Acceptance rate | `ACCEPTED / (ACCEPTED + DECLINED + TIMED_OUT)` from `expert_case_offer` (Unit 12), the same expression the scorer uses |
| Turnaround | median business hours from offer to signature |
| Roster health | counts by `Availability`, and how many experts hold each `FieldTag` — a field with one available expert is a single point of failure and worth showing before it bites |

The acceptance-rate expression is **imported from `ExpertMatchService`, not
re-written**. Two definitions of an expert's acceptance rate is how a dashboard and
a shortlist come to disagree about the same person.

### 4. Review capture — and what it honestly cannot say

`evalos_case` has `google_review_requested` and `google_review_requested_at`, and
Handoff C (Unit 18) is what asks GHL to start the review sequence.

**EvalOS knows how many reviews it asked for. It does not know how many arrived** —
the review lands on Google and the campaign runs in GHL (invariant 2). So:

- **Built:** review *request* rate — delivered cases for which the request fired,
  over delivered cases. That is EvalOS's own instrumentation of the #2 health
  metric and it is fully measurable here.
- **Not built:** reviews actually captured. That needs a number from GHL, and
  reading it back would be a new inbound integration nobody has specified.
- The tile is **labelled for what it measures** ("review requests sent"), not
  "review capture rate". A tile that names a metric it cannot compute is the
  header-contradicting-the-instrument failure again. Open question 3 is whether GHL
  should report captures back; until it does, the tile does not imply it.

### 5. Portal links ledger — client and expert

**Why this exists, and it is not a reporting nicety.** EvalOS sends no mail
(invariant 14), so a portal link reaches the person it admits because a staff member
copied it out of `PortalLinkPanel` and sent it by hand. Touchpoints **T1/T5 (client)
and T6 (expert)** are all *decision pending* in `process-automation.md`, which means
**the delivery step has no instrumentation at all**: nothing anywhere records that a
minted link was actually sent, and nothing notices that it was not.

That is survivable for a client, who waits. It is **gap G15** for an expert, because
dropping the signature provider removed the thing that used to email a signing link,
and the **20h/24h signing clock runs whether or not the expert ever received theirs**.
The most likely way EvalOS breaches that SLA today is a link nobody sent, and no
screen would show it.

So the ledger is the compensating control for an undecided channel. It answers four
questions per case, per audience: **is there a live link, was it ever opened, when
does it die, and is the clock running against an unopened one.**

#### What it reads — all of it already stored

`portal_access` (`V21`, `V23`) carries `brand_id`, `case_id`, `audience`,
`expires_at`, `revoked_at`, `last_seen_at`, `created_at`. **Revoked rows are kept**,
so the history of links issued for a case is already on disk; `retirePrevious` stamps
`revoked_at` on every superseded row rather than only live ones.

**No migration. No new column.** In particular:

- **Not `sent_at`.** It is tempting and it would be a lie: EvalOS cannot observe a
  staff member pasting a URL into someone else's mail client. A column recording a
  fact the system cannot witness is worse than an absent one, because a dashboard
  would then report it. `last_seen_at` is the honest proxy — it is evidence the link
  *arrived*, which is the thing actually worth knowing. If the email decision ever
  lands on EvalOS sending these, `sent_at` becomes real and belongs in that unit.
- **Not `minted_by`.** See the decision below.

#### Deliberate limitation: "who issued it" is not a column on this tile

The mint writes `AuditAction.PORTAL_LINK_ISSUED` against the **case**
(`objectType = "CASE"`), with the audience and expiry in the snapshot's free-text
note — `"client portal link issued, expires …"`. So the actor is recorded, but not in
a form joinable to one `portal_access` row: matching would mean parsing that string.

**The tile therefore does not show an issuer, and links to the case timeline instead**,
which already answers it precisely. This is the `paid_by` / `uploaded_by` decision
again — the audit trail records who, and a second copy is a second thing that can
disagree — but note the precondition is weaker here, and say so rather than pretend
otherwise: the audit row is about the *case*, not the *token*.

Two upgrade paths if an issuer column is ever genuinely wanted, in preference order:

1. Point the mint's audit row at the token (`objectType = "PORTAL_ACCESS"`,
   `objectId = token.id`). Then the trail records who issued *which* token, with no
   new column and no duplicated fact. Cost: the mint stops appearing on the case
   timeline, which is currently useful — so this needs a second, case-level row or a
   timeline that reads both.
2. Add `portal_access.minted_by`. Cheapest to query, and the one that duplicates a
   fact. Take it only if (1) proves worse in practice.

#### The rows

One row per **(case, audience)** pair, not per token — a case with six superseded
client links is one line, and its re-mint count is a column on that line rather than
six rows of noise. Newest token decides the row's state.

| Column | Source |
|---|---|
| Case | `case_code`, links to `/cases/{id}` |
| Client | batched contact-name lookup, as `CaseBoardService` already does |
| Stage | so an unopened expert link at `EXPERT_SIGNING` reads as urgent |
| Audience | `CLIENT` / `EXPERT` |
| State | RAG, see below |
| Opened | `last_seen_at`, or "never" |
| Expires | `expires_at`, relative ("in 3 days") with the absolute on hover |
| Re-mints | count of rows for that pair minus one; blank when zero |

#### RAG semantics, per `ui-context.md`'s single source of truth

This tile's bands must be derived, not invented, and the rule is **"is a clock running
against a link nobody has opened"**:

| Condition | Band |
|---|---|
| Stage needs this audience and there is **no live link at all**; or live, **never opened**, and the stage SLA is `OVERDUE` | `--status-red` |
| Live, never opened, stage SLA `AT_RISK`; or live and opened but **expires inside 48h** while the case is still open | `--status-amber` |
| Live and opened, comfortably in date; or the audience is not needed at this stage | `--status-green` |

**"Needs this audience" is derived from the stage, not stored.** A client link is
needed once a draft has gone out for client review (`DRAFT_GENERATION` with
`client_approval_status` pending); an expert link is needed at `EXPERT_SIGNING`.
Anywhere else, an absent link is correct and must read green — a tile that shows red
for every case in `DOC_COLLECTION` is a tile people learn to ignore.

**A case holding an exception state runs no clock** (`SlaCalculator` returns null), so
it can be neither red nor amber on the *unopened* rule. This is the `slaMix`
`unknown` band lesson from the board's visual pass: do not colour a paused case as
healthy, and do not colour it as breaching either. It gets its own muted state and the
count is stated in the header, never folded into green.

#### Scope — read this before writing the query

**Build it on the case read, not on `PortalAccessRepository.findScoped`.** That
repository is `brandOnly("brandId")` by deliberate design, because "the person this
row admits is its subject and not a principal who can read it". Query it directly and
**a Case Manager sees links for every case in the brand, including cases that are not
theirs** — which is a scope regression, not a feature, and precisely the failure
`CaseBoardService` avoids by building on `CaseLifecycleService.list` rather than
issuing a second scoped query.

So: take the caller's cases first, then load tokens for those case ids. Same reasoning,
same shape, and the brand filter can still only ever narrow.

Visibility matches minting — GM, Brand Manager, PM, Case Manager (`MAY_MINT` in
`PortalLinkController`). The Coordinator is deliberately excluded: they do not mint
links today, and this tile is an instrument for the people who do. If that turns out
wrong in practice, widen `MAY_MINT` and this together — they are one audience.

#### The one write: revoke

The ledger is where "kill that link now" belongs, and today there is **no way to do
it**. `PortalLinkController` exposes only `GET` and `POST` (mint); revocation happens
solely as a side effect of re-minting. That covers "the link doesn't work" and does
**not** cover "it went to the wrong address" — re-minting there leaves you holding a
live credential you also do not want.

`DELETE /api/cases/{id}/portal-link?audience=` — stamps `revoked_at`, same `MAY_MINT`
gate, one audit row (`PORTAL_LINK_REVOKED`, a new value in the open `AuditAction`
vocabulary, so no migration). After it, `resolve` already answers empty, and
"unknown, expired and revoked are one answer" still holds, so nothing is learnable
from the refusal.

**Cut this first if the unit is running long.** It is the only write on an otherwise
read-only tile, and re-minting is a workable if inelegant substitute. Everything else
here is a query over data that already exists.

## Role dashboards

Same components, different selections — one dashboard definition table keyed by
role, in the `NAV_ITEMS` / `STAGE_ACCESS` / `NotificationListeners` spirit: a
role's tile set is a data row, not a branch.

| Role | Sees |
| --- | --- |
| GM | everything, **cross-brand**, with per-brand breakdown and the brand switcher narrowing it |
| Brand Manager | everything for their brand |
| Project Manager | cycle time, SLA breaches, their team's throughput, expert utilization, **and the money tiles** — the PM *is* on `SEES_DEAL_VALUE` |
| Project Coordinator | doc-collection queue age, chase counts, delivery confirmations |
| Case Manager | their own docket: cases by stage, drafts awaiting them, SLA on their cases |
| ENM | roster health, utilization, acceptance rate, turnaround, payout status counts. **No case content** — the supply-side axis (`architecture.md`) |

The row above used to say the opposite — "no money tiles, not on `SEES_DEAL_VALUE`" —
with this paragraph correcting it underneath. Both statements then sat on the page and
whichever one a reader hit first was the one they believed, so the table has been fixed
at source and the contradiction removed rather than annotated. `CaseController` grants
deal value to GM, Brand Manager **and PM**. **Read `SEES_DEAL_VALUE` at build time and
follow it** — do not re-derive it from this table, and if the two ever disagree again,
the code is right and this file is stale.

## The per-role operational contract (Production Process v2.0)

The table above says what each role *sees* in terms of metric families. The business
then specified the actual screens, KPIs and buttons, which is a different and more
demanding list. Both are below; where they differ, this section is the requirement
and the table above is the summary.

Status tags: **built** · **specced** (elsewhere, unbuilt) · **gap** (nothing yet) ·
**GHL** (not EvalOS's).

Two things that apply to every role:

- **Capacity colours are already decided.** `ui-context.md` fixes green `<70%`,
  amber `70–90%`, red `>90%` as the RAG capacity contract. Any workload indicator
  uses those; do not invent thresholds.
- **Every figure derives.** No counter columns. `expert.current_active_count`,
  `total_cases_completed` and `total_payments_pending` exist, have never been
  written, and are the standing warning — `ExpertLoadService` is the pattern.

### Project Manager — all production cases

| Widget | Status |
|---|---|
| **Cases inbox** — newly won cases arriving from sales: client, client type, service type, deadline, documents-received status, sales notes | **gap** — and note two things: "payment confirmed" is now always true (spec `05b` creates the case paid), so it is a column of yeses and should be dropped rather than rendered; and **no field carries GHL's sales notes** today, so either intake starts carrying one or the column comes out |
| **Production board (Kanban)** — 8 columns | **built** as 5 stage columns + sub-status chips. The 8-column reading is a derived grouping — see `08-production-board.md` |
| **Case manager workload** — cases per CM, capacity RAG, unassigned flagged | **gap**. One grouped count over `evalos_case` by `assigned_cm`, the `ExpertLoadService` shape |
| **Expert assignment board** — cases waiting for an expert, expert availability, responses overdue >24h in red with a reassign prompt | **partly** — `AvailabilityBoard` and the Unit 12 shortlist are built; "cases waiting" and the overdue flag are **gap** (the flag needs Unit 15) |
| **Deadline view** — every case by deadline, overdue/today/this week, filter by CM or service | **gap** |
| **Draft review queue** — drafts awaiting this PM, oldest first | **gap** |

| KPI | | Status |
|---|---|---|
| Cases delivered on time % | PRIMARY | **gap** — the largest tile, per `ui-context.md` |
| Cases at risk right now — deadline within 24h and not yet ready to deliver | PRIMARY | **gap** |
| Unassigned cases — must be zero | PRIMARY | **partly** — the pool lane counts it on the board |
| Avg case completion by service type | SECONDARY | **specced** — cycle-time family |
| Draft revision rate per CM | SECONDARY | **gap** — derivable from `draft_version_count` + `DRAFT_RETURNED` audit rows |
| Expert response time avg, flag >36h | TRACKING | **specced** as turnaround. **Derive from `expert_case_offer`, never from `expert.avg_response_hours`** — that column has no writer and is permanently null |

Quick actions — **built**: assign CM + expert, PM approve, PM return with reason, QC
approve, write strategy notes. **Gap**: mark case urgent / change deadline; reassign
a case between Case Managers after assignment (`assign-cm` is declared on
`EXPERT_ASSIGNMENT` only, so mid-draft reassignment has no path).

Not visible: sales pipeline lead detail, marketing data, expert recruitment,
financial data beyond the case's own deal value.

### Project Coordinator — client-facing: documents, comms, delivery

| Widget | Status |
|---|---|
| **Document checklist board** | **built** (Unit 10) |
| **Pending document chases** — no client response in 24h / 48h, with a chase prompt | **built** — `needsChase()` |
| **Cases awaiting client review** — how long since sent, opened / not opened, revisions received | **partly** — Unit 14 tracks read receipts; the queue view is **gap** |
| **Delivery queue** — PM-approved and ready to send, one-click delivery | **gap → being reinstated.** `/delivery` was deleted as an empty nav entry and its absence is asserted in `navigation.test.ts`; the business asked for it twice, so it comes back with a real screen and that assertion flips |
| **Client communication log** — every message threaded per case, sendable from the view | **gap, and architecturally GHL's.** EvalOS holds no message entity and sends nothing. The nearest truth is the audit timeline (`CHASED`, `PORTAL_LINK_ISSUED`). A real threaded log would be a new **inbound** integration pulling GHL conversations — out of scope, in the Gap Register |
| **Retention follow-up queue** | **GHL** — end to end, including the 7-day review request |

| KPI | | Status |
|---|---|---|
| Cases with incomplete docs >48h | PRIMARY | **partly** — the checklist board's aging bands |
| Cases delivered today / this week | PRIMARY | **gap** |
| Avg time to complete doc collection | SECONDARY | **specced** — cycle time for `DOC_COLLECTION` |
| Client review turnaround, flag >48h no response | SECONDARY | **specced** |
| Google review requests sent vs received | TRACKING | **sent** is specced; **received lives in GHL** and the tile must say "requests sent" |

Quick actions — **built**: mark docs complete, deliver, close. **Specced**: send the
checklist (the event exists; delivery is an undecided touchpoint). **GHL**: message
the client, send retention follow-up.

### Case Manager — their own docket only

| Widget | Status |
|---|---|
| **My active cases** | **built** — `/my-cases` |
| **PM notes panel** | **built** — `StrategyNotes` |
| **Priority queue by deadline**, RAG by urgency | **gap** |
| **Draft status board** — submitted / approved / returned, revision history | **partly** — chips + timeline; no dedicated board |
| **Client feedback log** — what the client asked to change | **partly** — revision reasons are in the audit trail |
| **Expert signing status**, reassign prompt past 24h | **specced** (Unit 15) |

| KPI | | Status |
|---|---|---|
| Cases completed on time % | PRIMARY | **gap** |
| Draft revision rate, flag consistently >30% | PRIMARY | **gap** |
| Client revision request rate | SECONDARY | **gap** |
| Cases due today / tomorrow | SECONDARY | **gap** |

Quick actions — **built**: open case and documents, submit draft, revise. **Specced**:
send the signed letter to the expert (Unit 15). **Gap**: flag a case issue to the PM.
**Note**: "reassign expert if no response in 24h" is *not* the CM's to fire — Unit 15
gates `EXPERT_TIMED_OUT` to PM and above; the CM sees the prompt.

Not visible: other CMs' cases, deal values (the CM is **not** on `SEES_DEAL_VALUE`),
sales pipeline, expert payment data, other clients.

### Expert Network Manager — supply side only

| Widget | Status |
|---|---|
| **Expert database** — the roster with tier, quality score, availability, agreement and payment status | **built** (Unit 11) |
| **Availability board** — grouped by field, available vs at-capacity vs inactive | **built** |
| **Coverage gap alert** — any field below threshold | **gap**. The business threshold is **fewer than 5 available experts in a field** |
| **Payment tracker** — pending payouts, overdue in red, total outstanding | **specced** (Unit 16) |
| **Performance flags** — response >24h, 2+ declines, low quality, PM-flagged | **partly** — the column and the display exist; **nothing writes it**, and declines are better read from `expert_case_offer` than from a flag |
| **Recruitment pipeline** — Identified → Contacted → Agreement Sent → Signed → Active | **GHL** |
| **Outreach activity** — calls, emails, response rate, prospects cold >7 days | **GHL** |

| KPI | | Status |
|---|---|---|
| Available vs at-capacity; any field with <5 available flags immediately | PRIMARY | **gap** — the coverage alert above |
| New experts onboarded vs target | PRIMARY | **gap, but trivial** — one count over `expert.date_onboarded`, which already exists. The *target* needs somewhere to live (config, not a table) |
| Payments overdue >7 days | PRIMARY | **specced** (Unit 16) |
| Avg expert response time, fleet-wide and by tier, flag >36h | SECONDARY | **specced** — from `expert_case_offer`, not the dead column |
| Coverage gaps by field | SECONDARY | **gap** |
| Quality score trend, this month vs last | TRACKING | **gap** — `quality_score` is human-entered and unversioned, so a trend needs either history or an accepted limitation. Flag it rather than fake it |

Quick actions — **built**: add expert, update availability and tier. **Specced**: log
a payout (Unit 16). **GHL**: send the agreement, move a prospect along. **Gap**: flag
an expert underperforming (no writer for `performance_flags`).

Not visible: case content, draft letters, client identity, sales pipeline, revenue,
other departments.

### GM — and the "Head of Eval dashboard"

**The build spec's "Head of Eval" is the GM.** There is no Head-of-Evaluations role
in EvalOS and none is being added, so every Head-of-Eval instruction in the business
spec resolves here: the day-3 document escalation (A09) is flagged on the GM
dashboard, the unassigned-after-4h escalation alerts the GM, and A21's "revenue
confirmed" is a GM tile.

The GM sees everything, cross-brand, with a per-brand breakdown and the brand
switcher narrowing rather than widening it — as the role table already says. The GM
is the only role that can compare brands.

### Brand Manager

Everything for their own brand: the same tiles as the GM, one brand's worth. The GM's
cross-brand comparison is the only thing withheld.

## Backend

| Method | Path | Auth | Notes |
| --- | --- | --- | --- |
| GET | /api/dashboard | any authenticated staff role | the caller's tile set, scoped and date-filtered. `brandId` optional and **narrowing only**, exactly as `GET /api/cases/board` treats it |

**One route, not one per metric.** A dashboard is one screen making one request;
six requests would be six chances for the tiles to disagree about the date range,
and the tiles have to be internally consistent above all — that is what every
staleness defect in this project's history has been about.

No `@PreAuthorize` on the route, deliberately, and for the reason
`NotificationController` gives: every staff role has a dashboard and none can see
another's, so **identity narrows the response** and a role gate would be the wrong
tool. What each role gets is decided in the service, by the tile table.

`brandId` is applied **after** the scoped read, so it can only ever narrow — a Brand
Manager naming another brand gets their own empty figures, not that brand's. The
same property `CaseBoardServiceTest.theBrandFilterOnlyEverNarrows` pins for the
board, asserted again here.

Reads compose rather than fork: the case aggregates go through the same scoped path
`CaseLifecycleService.list` and `CaseBoardService.forCaller` use, and payouts
through `PayoutLedgerRepository.findScoped`. **No new scoped query, no second
scoping path** — the rule Units 08, 10 and 12 all follow.

## Frontend deliverables

1. **`features/dashboards/RoleDashboard`** stops being a placeholder: a tile grid
   driven by the response, so a role with no money tiles renders a shorter grid
   rather than empty boxes.
2. **Money tiles**: collected / recognized / **open liability** / refunded / money
   out, with unpaid pipeline visually separated so it cannot be read as revenue.
   Collected, recognized and open liability are shown as a set that balances;
   refunded sits beside them, not inside them.
3. **Cycle-time chart** by stage — median with p90, and the exception time shown
   as its own band rather than folded in, the same honesty `slaMix` applies to its
   `unknown` band.
4. **Expert tiles**: utilization, acceptance rate, turnaround, and the
   field-coverage warning.
5. **Review requests sent**, labelled as what it is.
6. The **global date filter** (Unit 07's `TopBar`) drives every time-bounded tile,
   and each tile states the range it covers — a number with no period is not a
   number.
7. **Empty and partial states**: a brand with no delivered cases says so rather
   than rendering `0%`. Zero and no-data are different facts, which is the same
   distinction `slaMix` keeps `unknown` for.
8. Every figure keeps tabular figures (`ui-context.md`), and no tile is
   colour-only — the RAG treatment carries a label, as `SlaRail` does.

## Acceptance criteria

- [ ] Each of the six roles gets a dashboard, correctly scoped: a Brand Manager's
      figures cover their brand, a Case Manager's cover their own docket, the ENM
      gets roster figures and **no case content**.
- [ ] Money tiles appear for exactly the roles on
      `CaseController.SEES_DEAL_VALUE` — asserted against that list, not against a
      copy of it. A role not on it receives **no money fields in the response at
      all**, not zeroed ones.
- [ ] Open liability equals paid-and-not-delivered-and-not-refunded, proved against
      a fixture holding one of each: unpaid, paid-undelivered, paid-delivered,
      refunded. The unpaid case is in **no** money tile except unpaid pipeline.
- [ ] **The money tiles add up on that same fixture: `Collected` equals
      `Recognized + Open liability`, and the refunded case is in `Refunded` and in
      none of the other three.** Asserted arithmetically, because a refunded case
      counted as collected is the defect this criterion was added for.
- [ ] The GM's cross-brand total equals the sum of the per-brand figures, and the
      brand switcher narrows it to one brand's.
- [ ] `brandId` only ever narrows: a Brand Manager naming another brand gets their
      own empty result, never that brand's data.
- [ ] Cycle time is on the business calendar — a case that crossed a weekend
      reports working hours, not wall-clock — and exception time is reported
      separately.
- [ ] The acceptance rate on the dashboard equals the one
      `ExpertMatchService` computes for the same expert. Asserted directly, because
      two copies of this expression is the defect the shared import prevents.
- [ ] Utilization is the derived load while `current_active_count` is still `0`.
- [ ] The dashboard's case counts **agree with the production board** for the same
      caller, brand filter and date range. This is the acceptance criterion that
      matters most: every display defect in this project's history has been two
      surfaces disagreeing about one dataset.
- [ ] The review tile is labelled "requests sent" and claims nothing about captures.

**Portal links ledger (metric 5).**

- [ ] **A Case Manager sees links only for cases they hold.** Asserted with a fixture
      holding one link on their case and one on a colleague's in the same brand: the
      second is absent. This is the criterion the tile exists to not fail — querying
      `PortalAccessRepository.findScoped` directly would pass every other criterion
      here and still leak, because that repository is brand-only by design.
- [ ] One row per (case, audience), not per token: a case re-minted five times shows
      one row with a re-mint count of 4, and the row's state comes from the newest.
- [ ] A revoked-and-replaced link reads as live, and a revoked-and-not-replaced one
      reads as no live link. Both proved against `retirePrevious`, which stamps
      superseded **and** expired rows.
- [ ] A case at `DOC_COLLECTION` with no links at all is **green**, not red: absent is
      correct until the stage needs the audience.
- [ ] An unopened expert link on a case at `EXPERT_SIGNING` whose stage SLA is
      `OVERDUE` is **red**, and the same link on a case in an exception state is
      neither red nor amber — it is the muted no-clock state, and the header states
      that count separately rather than folding it into green (the `slaMix` rule).
- [ ] `last_seen_at` drives "opened", and a link opened once then not again still
      reads opened — this is not a freshness metric.
- [ ] **No token, no hash, and no URL appears in any response.** Asserted on the
      payload, because the whole point of `V21` storing only a SHA-256 is that a read
      yields no working link, and a dashboard is a read.
- [ ] No issuer column, and each row links to the case timeline where the
      `PORTAL_LINK_ISSUED` row names the actor.
- [ ] If revoke ships: `DELETE` is refused for a role outside `MAY_MINT`, writes one
      `PORTAL_LINK_REVOKED` audit row, and a subsequent portal request with that
      token is answered exactly as an unknown one — no new distinguishable state.
- [ ] `npm run build` green; `./mvnw verify` green.

## Invariants honored

Brand isolation, with the GM the only cross-brand reader and `brandId` narrowing
only (1); no sales, marketing or attribution metric is computed or displayed (2);
role decides which figures exist in the response, not which are hidden client-side
(3); `payment_detail` appears nowhere — no dashboard aggregates it, and there is no
read path (4); **paid *and* delivered is recognition, and collected-but-undelivered
is shown as open liability** (5) — this unit is where invariant 5 becomes visible to
the business; reads are aggregates, not long-lived work (6); every figure derives
from the system of record, none is entered (7); read-only unit, so no audit rows and
no transitions (13); no export by email (14).

## Files touched

**Created.** Backend: `service/DashboardService.java` (the metric functions and
the role→tile table), `service/CycleTimeService.java` (the audit-trail derivation
on `BusinessCalendar`), `web/DashboardController.java` (+ DTOs). Frontend:
`frontend/src/features/dashboards/*` (`MetricTile`, `MoneyPanel`,
`CycleTimePanel`, `ExpertPanel`, `dashboardApi`, `dashboardRules` + its test).

For the links ledger specifically: `service/PortalLinkLedgerService.java` — its own
service rather than a method on `DashboardService`, because it is the one tile that
reads a different table and the one with a scope trap worth isolating. Frontend
`features/dashboards/PortalLinkLedger.tsx`, and the state derivation in
`dashboardRules` (a display branch that wrong is a display branch worth testing —
`boardRules.allInsideSla` is the precedent).

**Modified.** `frontend/src/features/dashboards/RoleDashboard.tsx` (the
placeholder becomes the real screen). `repository/PortalAccessRepository.java` — one
batched finder over a set of case ids, `findByBrandIdInAndCaseIdInOrderByCreatedAtDesc`
— brands as well as ids, matching what the checklist and chase batch reads were changed
to on 2026-08-06. The caller passes the distinct brands of the rows its scoped read
returned, never a request parameter (null for the GM scopes nothing). The older
"do not call it with ids from a request" convention is retired: a comment is not a scope.
`domain/AuditAction.java` + `web/PortalLinkController.java` +
`service/PortalAccessService.java` only if revoke ships. `repository/CaseRepository.java` and
`repository/AuditEventRepository.java` — aggregate projections, the audit one a
**read** added to the whitelist in
`DomainInvariantsTest.theAuditRepositoryCannotChangeHistory`, which is what that
test is for. `service/ExpertMatchService.java` — the acceptance-rate expression
extracted so both callers share it, not copied.

**Not touched.** No migration if the live-aggregate recommendation is taken. No
new source of truth. `service/ScopePredicate.java`,
`CaseController.SEES_DEAL_VALUE` (read, never re-declared).
