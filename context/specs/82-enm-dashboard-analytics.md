# Spec 82 — Expert Network Manager dashboard analytics (slice 4 of the production-dashboard redesign)

**Status:** designed and built 2026-10-09 in one pass at the user's instruction ("go with option 1"), so like
spec 81 it was **not reviewed before it was built**. The brief (§5 Expert/Drafting, §6 other production roles)
has no ENM section; this is §6 applied to the ENM's actual responsibilities plus the supply-side part of §5.

## Why

`ExpertNetworkDashboard` shows roster health, coverage, acceptance rate, turnaround and the hiring pipeline. It
cannot show **which offers nobody has answered, and for how long**, or how the offers the network has made
actually resolved.

## Constraints

- **Supply side only.** `ExpertNetworkMetricsService` reads the roster and the offer ledger, never a case. Nothing
  here names a client or a case; an open offer shows the expert and the wait.
- No change to RBAC, roles or workflow. Read-only. New route has exactly the `/expert-network` gate: GM, Brand
  Manager, PM, ENM. No new table, column, migration or `AuditAction`.
- Same roster scope (`findScoped`) and same ledger as `forCaller`, so the funnel reconciles with the acceptance
  rate. As there, a caller with no brand (the GM) gets an empty answer.

## 1. Definitions

| Metric | Unit | Definition |
| ------ | ---- | ---------- |
| Offer funnel | offers | the caller's roster's offers by `OfferOutcome` (OFFERED shown as "waiting for an answer") |
| Open offers | offers | offers still `OFFERED` for a rostered expert |
| Oldest unanswered | offers | open offers, longest wait first, capped at 25; wait in calendar hours |

## 2. Backend

`GET /api/metrics/expert-network/work` → `ExpertNetworkWork { funnel, openOffers, oldestOpen[{expertId,
expertName, offeredAt, waitingHours}] }` from `ExpertNetworkMetricsService.work()` (a method on the existing
service, so no new bean). One new brand-scoped finder: `findByBrandIdAndOutcomeOrderByOfferedAtAsc`.

## 3. Frontend

Three cards on `ExpertNetworkDashboard`, on a second independent `useMetrics` load: **Offer funnel**, **Oldest
unanswered offers**, and (from the existing hiring board read) **Candidates gone quiet**.

## 4. Not built (and why)

| Idea | Status |
| ---- | ------ |
| Cases that cannot be staffed | needs a case read; breaks the supply-side axis |
| Per-expert first-pass / revision rate | needs draft rows joined to cases; same axis problem |
| "About to time out" | offers have no timeout setting; age is shown, no threshold invented |
| Payout aging | not built; payouts card already shows pending/processing/paid per month |
| Hiring-stage age | no stage-entry time held; built instead as **Candidates gone quiet** (frontend only, reuses `dealAge`): open hiring deals with no GHL change in 7+ days, oldest first. Last touched, not time in stage |
| Brand-wide funnel for the GM | existing limitation: no brand means no ledger read |

## 5. Verification

Service + route tests, `tsc -p tsconfig.app.json`, vitest, full backend suite. No browser check.
