# Spec 81 — PC dashboard analytics (slice 3 of the production-dashboard redesign)

**Status:** designed and built 2026-10-09 in one pass at the user's instruction ("spec, implement and go
natively") — so, unlike specs 79 and 80, this was **not reviewed before it was built**. Slices 1 and 2 are
specs 79 (PM) and 80 (CM); this one reuses `StageFunnel`, `QueueTable` and `segmentPercents` from them.

## Why

`CoordinatorDashboard` answers four things (cases collecting, median wait, delivered, ready to deliver) plus
"with the client". It cannot say **which documents are still owed**, which cases the Coordinator has not
sent a checklist for, who chased whom and when, or which cases are sitting in the Coordinator's four
stages and for how long. It also has two wording problems the brief calls out:

- "Documents outstanding" counts **cases** in `DOC_COLLECTION`, not documents.
- "Median wait" renders `0 h` when no case is collecting (the server returns 0 for an empty set) — an
  unmeasured figure shown as zero.

## Constraints

- No change to RBAC, roles, workflow or actions. Read-only. The new route uses exactly the `/coordinator`
  gate: `hasAnyRole('GM', 'BRAND_MANAGER', 'PROJECT_COORDINATOR')`, same optional `brandId` narrowing.
- One scope read: the new service takes cases from `CoordinatorMetricsService` (package-private `scoped`),
  never its own predicate. Items and chase events are batch-loaded for those case ids and their brands.
- No new `AuditAction`, migration or column. Existing `/api/metrics/coordinator` contract unchanged.
- Counts say what they count: **cases**, **checklist items**. Never mixed in one tile.

## 1. Definitions (all "now", over the caller's open cases — not `DELIVERED`/`CLOSED`)

| Metric | Unit | Definition |
| ------ | ---- | ---------- |
| Stages | cases | the four Coordinator-owned stages (`DOC_COLLECTION`, `READY_TO_SEND`, `CLIENT_REVIEW`, `READY_TO_DELIVER`): count and median business hours in stage (null when no case has a stage-entry time) |
| Awaiting verification | items | checklist items in `UPLOADED` |
| Missing or incorrect | items | `MISSING` + `INCORRECT` (`INCORRECT` is the closest the data has to "rejected") |
| Unsent checklists | cases | open cases that have an item the Coordinator has not sent (`sent_at` null) |
| Blocked | cases | `exception_state != NONE` (D79) |
| Documents-owed rows | cases | open cases with any item not `APPROVED`, or an unsent item; worst first (missing+incorrect, then longest in stage), capped at 25 |
| Last chased | per case | newest `CHASED` audit event for the case; null if never |
| Client review list | cases | cases in `CLIENT_REVIEW`, longest wait first, capped at 25 |
| Ready-to-deliver list | cases | cases in `READY_TO_DELIVER`, longest wait first, capped at 25 |

List rows use `PmOverviewService.QueueRow` (case, service, owner = the case's CM, deadline, deadline risk,
waiting business hours) so one table component draws all of them.

## 2. Backend

`GET /api/metrics/coordinator/work?brandId=` → `CoordinatorWork`, from a new `CoordinatorWorkService`:

```
CoordinatorWork {
  stages: [PmOverviewService.StageCount]                      // the four stages, in pipeline order
  blocked: int
  documents: { awaitingVerification, blockerItems, unsentCases,
               owed: [{ caseId, caseCode, cmName | null, waitingBusinessHours | null, deadlineRisk | null,
                        total, approved, uploaded, required, missing, incorrect, unsent, lastChasedAt | null }] }
  clientReview:   [PmOverviewService.QueueRow]
  readyToDeliver: [PmOverviewService.QueueRow]
}
```

No per-case queries: one items read, one unsent-ids read, one chase-events read (skipped when there are no
rows — the native `IN ()` is a Postgres syntax error), one CM roster read.

## 3. Frontend

A second, independent `useMetrics` load. New strip of four tiles (awaiting verification, missing or
incorrect, unsent checklists, blocked cases), `StageFunnel` (given a title) for the four stages, a new
`DocumentsOwed` table (mini stacked bar via `segmentPercents`, CM, waiting, last chased, "unsent" flag),
and two `QueueTable`s (client review, ready to deliver). Fixes: "Documents outstanding" is renamed **Cases
collecting documents**; "Median wait" shows its `empty` state when nothing is collecting.

## 4. Not available (not invented)

| Brief item | Status |
| ---------- | ------ |
| Outdated documents | no such checklist status |
| Rejected documents | shown as `INCORRECT` only |
| Outstanding client/internal responses | not recorded |
| Per-person checklist assignment, "my" tasks | the checklist is per case |
| Review requests | existing tile, blocked by Unit 18 |
| Full document-collection history | `last chased` only; the case page timeline holds the rest |
| Handover actions | live on `/delivery`; this page only links |

## 5. Verification

Backend service + route tests, `tsc -p tsconfig.app.json`, vitest, full backend suite. Browser check only if
a backend built from this branch can be run. Then status, Serena memory in the same step.
