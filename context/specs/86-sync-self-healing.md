# 86 — Sync heals itself: conflict-safe pushes, automatic recovery, drift that closes on absorb

2026-10-28. Requested by the business: "every admin will not sit and push this button — automate it and
resolve the conflict between both." Edits D43's surroundings and the outbox rules of Unit 45; D43 itself
(no human clears a drift row) stands.

## 1. Problem

1. **Last push wins, blindly.** An EvalOS edit is pushed up to ~2 minutes later without looking at GHL. A
   value someone set in GHL inside that window is overwritten.
2. **A dead push waits for a person.** `EMPTY_RESPONSE` and exhausted retries stay dead until an
   Administrator presses Retry. Nobody will.
3. **Drift rows outlive the fix.** A `MISSING_LOCALLY` row stays open until the next nightly audit, even
   after `MIRROR_DELTA` has absorbed the deal.

## 2. Conflict-safe push (three-way, per field)

When a desk edits a shared field (name, amount, stage), the opportunity remembers the **base** — the value
the mirror held before the first unconfirmed edit of that field (`opportunity.ghl_base`, V96, JSON
field → value). A later edit of the same field keeps the original base.

Before an `UPSERT` reaches GHL, the outbox reads the deal's current GHL copy (the opportunity search by
contact — already used live, returns `updatedAt` and every field). Per edited field:

| GHL now | Decision |
|---|---|
| equals EvalOS's value | Already agree. Not sent. |
| equals the base | GHL did not touch it. EvalOS's value is sent. |
| differs from both | **Conflict.** The newer edit wins: GHL's `updatedAt` vs EvalOS's `local_updated_at`. If GHL is newer, EvalOS takes GHL's value and does not send it; otherwise EvalOS's value is sent. |

No base (an edit queued before V96): a GHL value that differs from EvalOS's is decided by the timestamp
rule alone. A deal GHL no longer returns is pushed as before (GHL refuses; it dead-letters, and the audit's
`MISSING_IN_GHL` is the human's).

Every conflict is audited (`GHL_OPPORTUNITY`, `UPDATED`, `{"conflict": field, "winner": "GHL"|"EVALOS", …}`)
and logged. Cost: one extra GHL read per push.

`CLOSE` gets the agree-check only: if GHL already holds the status, nothing is sent. A won close is never
discarded by timestamp — it is what opens the case.

## 3. Automatic recovery of dead pushes

Each drain first re-queues dead pushes, without a person, when **all** hold:

- dead within the last 7 days and at least **1 hour** ago, not already retried;
- the failure is one a later attempt can fix: `NO_ANSWER`, `UPSTREAM_ERROR`, `RATE_LIMITED` (attempts ran
  out) or `EMPTY_RESPONSE` (safe now that §2 reads before writing);
- fewer than **3** dead rows for the same entity + intent in the last 7 days — after that it is left for a
  person, so a permanently broken push cannot loop.

`REFUSED` (a 4xx, or an EvalOS bug) is never retried automatically: the same request fails again. The
Administrator's Retry button stays for that case, after the cause is fixed. An automatic retry stamps
`retried_at` with no `retried_by`; the page shows "Retried automatically".

## 4. Drift closes when the mirror catches up

When the mirror absorbs a deal (`absorb`, `absorbForContact`), any open `MISSING_LOCALLY` row for that GHL
id is resolved at once. Field mismatches still close at the nightly audit. Nothing here repairs from the
audit itself — the detector still never repairs (45 §2B.2).

## 5. Not in scope

- Status conflicts beyond the agree-check (§2).
- Notes: already one-way per author with their own reference matching (Unit 54).
- A per-field conflict screen. The audit trail and the log carry each decision.
