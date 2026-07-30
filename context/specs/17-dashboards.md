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
dashboard cannot see further than the board does. The date filter bounds figures by
the timestamp named in each definition.

### 1. Money in vs. delivered (open liability)

Invariant 5: revenue is recognized on **paid *and* delivered**.

| Figure | Definition |
| --- | --- |
| Collected | `SUM(deal_value)` where `paid` |
| Recognized | `SUM(deal_value)` where `paid AND delivery_date IS NOT NULL AND NOT refunded` |
| **Open liability** | `SUM(deal_value)` where `paid AND delivery_date IS NULL AND NOT refunded` — money taken for work not yet delivered, i.e. refund exposure |
| Unpaid pipeline | `SUM(deal_value)` where `NOT paid` — **shown separately and never added to the other three.** Since Unit 05a a case can be worked with no money behind it, and a quote is not revenue |
| Money out | `SUM(amount)` from `payout_ledger` by status (Unit 16) |

`deal_value` is role-restricted: `CaseController.SEES_DEAL_VALUE` is the package-
private list Unit 08 made shared so the board and the detail could not disagree.
**The dashboard projects through that same list** — a third copy is how a Case
Manager ends up seeing brand revenue on one screen. A role not on it gets the
count-based tiles and no money tiles at all, not zeroes.

### 2. Cycle time by stage

Median and p90 business hours spent in each stage, from the audit trail's
`STAGE_CHANGED` rows — **not** from `stage_entered_at`, which only remembers the
current stage.

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

## Role dashboards

Same components, different selections — one dashboard definition table keyed by
role, in the `NAV_ITEMS` / `STAGE_ACCESS` / `NotificationListeners` spirit: a
role's tile set is a data row, not a branch.

| Role | Sees |
| --- | --- |
| GM | everything, **cross-brand**, with per-brand breakdown and the brand switcher narrowing it |
| Brand Manager | everything for their brand |
| Project Manager | cycle time, SLA breaches, their team's throughput, expert utilization. **No money tiles** — not on `SEES_DEAL_VALUE`… *(see the note below)* |
| Project Coordinator | doc-collection queue age, chase counts, delivery confirmations |
| Case Manager | their own docket: cases by stage, drafts awaiting them, SLA on their cases |
| ENM | roster health, utilization, acceptance rate, turnaround, payout status counts. **No case content** — the supply-side axis (`architecture.md`) |

The PM **is** on `SEES_DEAL_VALUE` (`CaseController` grants deal value to GM, Brand
Manager and PM). So the PM does get money tiles; the row above is corrected here
rather than written from the role hierarchy, because the authority on who sees a
figure is the list the code already shares. **Read `SEES_DEAL_VALUE` at build time
and follow it** — do not re-derive it from this table.

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
2. **Money tiles**: collected / recognized / **open liability** / money out, with
   unpaid pipeline visually separated so it cannot be read as revenue.
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

**Modified.** `frontend/src/features/dashboards/RoleDashboard.tsx` (the
placeholder becomes the real screen). `repository/CaseRepository.java` and
`repository/AuditEventRepository.java` — aggregate projections, the audit one a
**read** added to the whitelist in
`DomainInvariantsTest.theAuditRepositoryCannotChangeHistory`, which is what that
test is for. `service/ExpertMatchService.java` — the acceptance-rate expression
extracted so both callers share it, not copied.

**Not touched.** No migration if the live-aggregate recommendation is taken. No
new source of truth. `service/ScopePredicate.java`,
`CaseController.SEES_DEAL_VALUE` (read, never re-declared).
