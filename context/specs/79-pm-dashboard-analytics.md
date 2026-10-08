# Spec 79 — PM dashboard analytics (slice 1 of the production-dashboard redesign)

**Status:** built 2026-10-09 (browser check not done — see `implementation-status.md`). No decision in `current-decisions.md` changes; open
questions are in `.claude/open-decisions.md` (Q20–Q22). Later slices (CM, PC, GM, Expert) each get their
own spec and reuse the components named in §4.

## Why

The PM dashboard (`PmDashboard.tsx`) is six tiles fed by `GET /api/metrics/pm`. It cannot answer the PM's
daily questions: *where is the work sitting, how long has it sat, what is waiting on me, and who is
overloaded*. The brief asks for a stage funnel, aging, review/QC queues, workload with overdue work and
throughput/rework trends — with every figure drilling into the cases behind it.

## Constraints (from the brief and CLAUDE.md)

- No change to RBAC, roles, workflows, transitions or actions. The new endpoint uses the PM's existing
  scope (brand, plus assignee where it applies) — the same `forCaller` path as `PmMetricsService`.
- Brand-scoped by default; audit and assignment history are read, never written.
- Existing tiles and the `/api/metrics/pm` contract stay as they are; the new data is additive.
- A metric with no data source renders **"Not tracked yet"**, never 0, and is listed in §5.

## 1. Stage vocabulary

The pipeline is the 11-stage `Stage` enum (plus `CLOSED`, excluded from the funnel). The brief's names
map as follows; the funnel shows **all 11** so the total reconciles with "active cases".

| Brief                | Stage                            | Funnel label       |
| -------------------- | -------------------------------- | ------------------ |
| Document Collection  | `DOC_COLLECTION`                 | Document collection |
| Expert Assigned      | `PM_REVIEW` (expert + CM picked here) | PM review      |
| Draft In Progress    | `DRAFT_IN_PROGRESS`              | Draft in progress  |
| Draft Review         | `DRAFT_REVIEW`                   | Draft review       |
| —                    | `READY_TO_SEND`                  | Ready to send      |
| Client Review        | `CLIENT_REVIEW`                  | Client review      |
| —                    | `CLIENT_APPROVAL`                | Client approval    |
| Expert Signing       | `EXPERT_SIGNING`                 | Expert signing     |
| Final QC             | `FINAL_QC`                       | Final QC           |
| Ready to Deliver     | `READY_TO_DELIVER`               | Ready to deliver   |
| Delivered            | `DELIVERED`                      | Delivered          |

Labels come from one frontend table (extend `boardRules.ts` `STAGE_COLUMNS`/labels, do not fork).
`DELIVERED` is period-bound (delivered in the window); the other ten are live.

## 2. Definitions (each tile says which kind it is)

| Metric | Kind | Definition |
| ------ | ---- | ---------- |
| Active | now | scoped cases not `DELIVERED`/`CLOSED` |
| Unassigned | now | existing `PmMetricsService.unassigned` (reused) |
| At risk | now | existing `atRiskNow` (`DeadlineRiskCalculator`, excludes READY_TO_DELIVER+) (reused) |
| Blocked | now | `exception_state != NONE` (on hold awaiting client, expert declined/rematching, refund requested) — see Q20 |
| Awaiting my review | now | cases in `DRAFT_REVIEW` |
| Awaiting QC | now | cases in `FINAL_QC` |
| Ready to deliver | now | cases in `READY_TO_DELIVER` |
| Stage count / median age | now | count per stage; age = `now - stage_entered_at` on the business calendar, median per stage |
| Delivered, on-time % | period | existing `onTime` (reused) |
| Throughput | period | delivered per day (≤31 days) or per ISO week, from `deliveryDate` (the field `onTime` already reads) |
| Rework rate | period | existing `revisionRateByCm`, summed across CMs |
| Overdue per CM | now | scoped open cases of that CM whose `DeadlineRisk == OVERDUE` |

Case counts, never task/draft/checklist counts: tile subtitles say "cases". The period filter applies
only to *period* rows; *now* tiles carry the note "Right now — ignores the period" as `atRiskNow` does.

## 3. Backend

One new read endpoint, `GET /api/metrics/pm/overview` (`MetricsController`), delegating to a new
`PmOverviewService` in the same package as `PmMetricsService`. It takes the same `range`/`from`/`to` and
`brandId` params and the same caller scoping; it returns:

```
PmOverview {
  stages:   [{ stage, count, medianAgeBusinessHours | null }]      // 11 rows, live (DELIVERED = period)
  active, blocked, awaitingReview, awaitingQc, readyToDeliver: int
  queues: { draftReview: [QueueRow], finalQc: [QueueRow] }          // capped 25 each, oldest first
  throughput: [{ bucket, delivered }] | null
}
QueueRow { caseId, caseCode, serviceType, ownerName, deadline, risk, waitingBusinessHours }
```

- **Workload** is not in this payload: `overdue` was added to `CmWorkload` on `/api/metrics/pm` (additive), so there is one workload source.
- **Reuse:** scope loading, `DeadlineRiskCalculator`, business-calendar helper, `CmWorkload` source —
  move shared private helpers to package-private rather than copying them.
- **Query shape:** one scoped case load, grouped in memory (the same pattern `PmMetricsService` uses);
  no per-case queries. Queue rows come from that same list.
- **First-pass QC rate** is *not* in the payload (Q21).
- Tests: stage counts sum to `active`; a case outside the caller's scope never appears; empty scope
  returns zeroed stages and `throughput: []`, not null; brand A's PM never sees brand B's rows.

## 4. Frontend

Reuse `Card`, `KpiCard`, `ChartCard`, `CapacityBar`, `useMetrics`, `pmMetricsApi.ts` (new
`fetchPmOverview` beside the existing fetchers; the overview and the existing `/pm` call are two
requests fired once each via `useMetrics`). New shared pieces, built to be reused by later slices:

- `StageFunnel` — horizontal bars, one per stage, count + median age, each a link to
  `/inbox?stage=<STAGE>` (the inbox had no stage filter; `?stage=` was added to `inboxQueue`/`InboxPage`). The Delivered row does not link: `/api/cases/board` omits delivered cases.
- `QueueTable` — case code, service, owner, deadline chip (risk colour), waiting time; rows link to the
  existing case page / `DraftQueuePage`. No new actions.
- `ThroughputCard` — line chart over buckets. `TrendCard` is GM/money-specific (`GmTrend`, `formatMoney`), so a small new card, not an extension.
- Workload bars gain an "overdue" segment on the existing `CapacityBar`.

Page order: KPI strip (now | period groups, labelled) → `StageFunnel` → two `QueueTable`s → workload →
throughput + rework. States per card via the existing `CardState`: loading skeleton, `empty` note,
`error` with retry, and `unavailable` ("Not tracked yet") — a failed overview must not blank the
existing six tiles, so the two loads are independent.

## 5. Not available / dependencies (not invented)

| Brief item | Status |
| ---------- | ------ |
| First-pass QC rate, QC outcome breakdown | No per-case QC decision record exists (Q21) — shown "Not tracked yet" |
| Capacity for PC and Expert | Only CM capacity is configured — PC/Expert workload shows counts without a capacity bar |
| Per-task / checklist counts | Out of this slice (CM/PC specs) |
| Cases "requiring reassignment" | No such state; shown only as blocked `EXPERT_DECLINED_REMATCHING` (Q22) |
| Stage-age history (past aging) | Only current `stage_entered_at` exists; aging is "now", not trend |

## 6. Verification

`tsc` clean; vitest for the stage-label table and any new pure helpers; backend tests above; browser
check at 1440/1024/390 as PM and as a role that must not see the route (direct URL → existing guard).
After building: update `implementation-status.md`, `workflows.md` if the page flow changes, and the
matching Serena memory in the same step.
