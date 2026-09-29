# 62 — Expert payouts view (reverses D59's "no payouts view", 2026-09-29)

> **Superseded in part by `63-enm-workspace.md` Phase 3 (same day):** the page is no longer
> read-only — the expert confirms receipt of a transfer, and the labels are Pending / Processing /
> Paid.

**Decision.** D59 is edited: the Expert Network Manager still settles expert payments by hand on
the staff payout screens, and the expert portal now shows the expert a **read-only** view of what
has been recorded for them. Nothing in the portal pays, requests or disputes a payout.

## Backend

- `GET /api/portal/expert/payouts` → `ExpertPortalService.payoutRows`, restored from before
  `ee9f684`: the ledger for the token's expert in the token's brand
  (`PayoutLedgerRepository.findByBrandIdAndExpertIdOrderByCreatedAtDesc`, covered by
  `idx_payout_brand_expert`), newest first.
- The row is the whitelist `ExpertPayoutRow(caseReference, amount, currency, status, settledOn)`.
  **Never `payment_detail`** (invariant 4); a test asserts on the serialized form.
- A token naming no expert is refused (403), not widened.

## Expert portal

- Sidebar entry **Payouts** → `/payouts`.
- Totals by currency: owed (`PENDING`) and paid (`PAID` + `CONFIRMED`); `VOIDED` rows are listed
  but not counted.
- A table: case, amount, status, date paid. Copy says who to ask: the Expert Network Manager.

## Out of scope

Payment method, bank details, invoices from the expert, or any write.
