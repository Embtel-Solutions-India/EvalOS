# Spec 80 — CM dashboard analytics (slice 2 of the production-dashboard redesign)

**Status:** designed 2026-10-09, **not built, awaiting review**. Slice 1 (PM) is spec 79; this slice reuses
its components. PC, GM and Expert each get their own spec.

## Why

`CaseManagerDashboard` answers "what is due" (four KPIs, a priority queue, draft status, expert signing,
client feedback). It cannot answer what else blocks a CM's cases: *which checklist items are missing or
wrong, which expert offers are open or have come back declined, and where each draft sits*. That data
exists in `document_checklist_item`, `expert_case_offer` and the case's approval columns, and none of it
reaches this screen.

## Constraints

- No change to RBAC, roles, workflow or actions. Read-only. The new endpoint is caller-scoped like
  `/api/metrics/case-manager` (the caller *is* the scope; no brand switcher) and uses the same gate.
- Append-only truth: nothing is written. No new `AuditAction`, migration or column.
- A metric with no data source renders `unavailable`, never 0 (§5).
- Counts are labelled by what they count: **cases**, **checklist items**, **offers**. Never mixed in one tile.
- Existing tiles and the `/case-manager` contract are unchanged.

## Decision recorded

**A CM sees the fee on an expert offer for a case in their scope.** The CM sets the offer (at the standard
fee) and assigns the expert, and `GET /api/cases/{id}/expert/offer` already returns the fee to any role that
can open the case (only the *edit* is gated, to GM/PM/PC/ENM). Showing it on the dashboard widens nothing.
D-record: an edit to the Unit 65 paragraph of `current-decisions.md`, not a new decision.

## 1. Definitions

| Metric | Kind | Definition |
| ------ | ---- | ---------- |
| Returned drafts | now, cases | existing `revisionsRequested` (reused) |
| Checklist blockers | now, items + cases | items of my cases in `MISSING` or `INCORRECT`; the tile also states how many cases they sit on |
| Open offers | now, offers | my cases' offers with outcome `OFFERED` |
| Rematch needed | now, cases | my cases in exception state `EXPERT_DECLINED_REMATCHING` (D81) |
| Checklist progress | now, items per case | per case: total, `APPROVED`, `UPLOADED` (waiting for approval), `REQUIRED`, `MISSING`, `INCORRECT` |
| Offer row | now | case, expert, outcome, fee, offered-at age (business hours), decline reason |
| Draft lifecycle | now, cases | my open cases bucketed: drafting (`DRAFT_IN_PROGRESS`, never returned), returned (`pmApprovalStatus == RETURNED`), with PM (`DRAFT_REVIEW`), with client (`READY_TO_SEND`/`CLIENT_REVIEW`), approved (`CLIENT_APPROVAL` onward) |

"My cases" is exactly `CaseManagerMetricsService`'s set (cases assigned to the caller, open). Offers use the
**latest** offer per case plus the open one; superseded offers are history, not shown.

## 2. Backend

`GET /api/metrics/case-manager/work` → `CaseManagerWork`, built by a new `CaseManagerWorkService` that takes
the case set from `CaseManagerMetricsService` (package-private accessor, no second scope predicate) and batch-
loads checklist items and offers by case id — **no per-case queries**.

```
CaseManagerWork {
  checklist: { blockerItems, blockerCases, cases: [{ caseId, caseCode, total, approved, uploaded, required, missing, incorrect }] }
  offers:    { open, rematch, rows: [{ caseId, caseCode, expertName, outcome, fee | null, offeredAt, ageBusinessHours, declineReason | null }] }
  drafts:    { drafting, returned, withPm, withClient, approved }
}
```

Tests: items/offers from another CM's case never appear; a case with no checklist is absent from `cases`, not
a 0-of-0 row; a blank/unpriced fee is `null`; counts reconcile (`drafts` buckets sum to my open cases).

## 3. Frontend

Reuse `Card`, `KpiCard`, `QueueTable` pattern, `useMetrics`, `emptyWhen`. A second independent
`useMetrics` load so a failed `/work` cannot blank the existing dashboard. Add: a "needs me" KPI strip
(returned drafts, checklist blockers, open offers, rematch needed), a **Checklist progress** card (stacked
bar per case, rows link to `/cases/:id`), an **Expert offers** card, and a **Draft lifecycle** card. Fee is
shown with the brand currency formatter already used by payouts (`lib/money`).

Also fix the "Due now" note ("Zero overdue is the daily goal") to say *critical*: the tile counts the red
deadline band (past the date or under 24 business hours), as D-slice 1 found.

## 4. Not available (not invented)

| Brief item | Status |
| ---------- | ------ |
| Case take-back history | No take-back action or audit record exists |
| GM assignment / "awaiting GM assignment" | The case team is PM, CM, PC, expert; there is no GM assignment step |
| Expected expert delivery date | Not stored on the offer |
| Draft version history / compare | Only `draftVersionCount` and approval statuses are stored |
| Checklist "assigned to me" / "rejected" / "approval-pending" per person | Checklist is per case, not per assignee; `INCORRECT` is the closest to rejected and `UPLOADED` to approval-pending, shown as such |
| Case handover status | Not a recorded state for the CM |
| Per-case communication history | Lives in chat; not summarised here |

## 5. Verification

`tsc -p tsconfig.app.json` clean, vitest for any pure helper, backend service + route tests (role gate: only
the roles allowed on `/case-manager`), browser check as a CM at 1440/1024/390 *if a current backend can be
run* — otherwise say it was not checked. Then `implementation-status.md`, `current-decisions.md` (the fee
sentence) and the Serena memories in the same step.
