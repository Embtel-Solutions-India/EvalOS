# Unit 16 — Payout ledger (manual)

**Phase:** 2 — Connect the seams
**Depends on:** 03 (the `payout_ledger` table), 11 (`standard_fee`, and the expert
the payout names)
**Unlocks:** 17 (money-out is half the finance dashboard), 18 (Handoff C creates
the entry in the same transaction as the outbound `case.delivered`)
**Gating open questions:** none. The payout **rail** is a resolved question — there
isn't one, and `ai-workflow-rules.md` says not to re-open it.

## Goal

Replace the WhatsApp group where expert payout details are posted today. When a
case is delivered, EvalOS opens a `PENDING` payout row against the case and the
expert; a responsible team member fills in what was actually paid, how, and when.

**EvalOS never moves money.** There is no disbursement rail, no payment platform,
no bank API. This is a ledger — a record that money was owed and later paid — and
every design choice below follows from that.

**Verifiable result:** delivering a case creates exactly one `PENDING` payout row
prefilled with the expert's standard fee; a Brand Manager can record
method/reference/amount/date and mark it `PAID` then `CONFIRMED`; the weekly batch
view lists what is owed; the expert sees their own payout's status in the portal
and nothing else; and a GM-approved refund voids a still-pending row.

## In scope

- Auto-creation of the `PENDING` row on delivery, in the delivering transaction.
- The manual payout form and the `PENDING → PAID → CONFIRMED` progression.
- The weekly batch view (what is owed, grouped).
- Expert-facing payout **status** in the Unit 15 portal, read-only.
- Deriving `total_payments_pending` rather than maintaining the dead column.

## Out of scope

- **Any disbursement rail or payment-platform integration.** Resolved; not a
  question.
- Invoicing or anything client-side money. That is GHL's (invariant 2).
- Expert **fee negotiation** or per-case rate cards. The payout amount is prefilled
  from `standard_fee` and then typed by a human; a pricing engine is not in any unit.
- Tax forms, 1099s, currency conversion. Not in v1 scope anywhere.
- The refund path itself — `RefundService` (Unit 04) already voids pending payouts
  and this unit does not change it.
- Handoff C's outbound webhook — Unit 18, which joins this unit's transaction.

## Creation on delivery

`CaseLifecycleService.deliverToClient` is the moment. It already guards
`deliveryDate == null` ("the case has already been delivered"), so a second
delivery is refused, and the payout row is created inside that same transaction —
delivered and owed are one fact, and a delivery that committed without its payout
row would be a case nobody gets paid for.

**A partial unique index on `payout_ledger (case_id) WHERE status <> 'VOIDED'`
goes in anyway.** The `deliveryDate == null` guard is a check-then-act: `Case` has
no `@Version`, so two concurrent `deliver` calls can both read null, both save, and
both create a payout row — the exact shape of the bug `V15` was written for
("a lookup followed by an insert is a check-then-act that two concurrent
deliveries can both win"). The index cannot race; the loser rolls back. Partial on
`VOIDED` so a refunded case that somehow re-delivers is not blocked by the
tombstone.

The row is created with:

- `expert_id` from the case, `brand_id` from the case, `case_id`.
- `amount` prefilled from `Expert.standard_fee` (Unit 11), **nullable** — an expert
  with no standard fee gets a row with no amount, which the form makes somebody
  fill in. A prefill of `0` would be a number somebody could mark `PAID` without
  noticing.
- `currency` from the brand's configured currency, defaulting to `USD`.
- `status = PENDING`, `due_date` = delivery + the configured payout term.
- `recorded_by` **null** — nobody has recorded anything yet. It is set when the
  form is submitted, which is what the column means ("the staff member who
  recorded the row").

**A case delivered with no expert assigned creates no payout row**, and that is not
silent: it raises a notification to the PM and Brand Manager. A case can only reach
`FINAL_DELIVERY` through `EXPERT_SIGNING`, so this should be impossible — which is
exactly why it must be reported rather than swallowed if it happens.

## Status progression

`PayoutStatus` already exists: `PENDING · PAID · CONFIRMED · VOIDED`.

| From      | To          | Who                       | Means                                             |
| --------- | ----------- | ------------------------- | ------------------------------------------------- |
| `PENDING` | `PAID`      | GM · Brand Manager        | we sent it; method/reference/amount/date required |
| `PAID`    | `CONFIRMED` | GM · Brand Manager        | the expert acknowledged receipt                   |
| `PENDING` | `VOIDED`    | `RefundService` only (GM) | the case was refunded                             |

Rules:

- **Forward only, and declared.** A small transition check in
  `PayoutService`, mirroring `CaseTransitions`' whitelist idea without building a
  second state machine for four states. `CONFIRMED` is terminal. `VOIDED` is
  terminal. `PAID → PENDING` does not exist: unsending money is not a thing, and a
  mistake is corrected by editing the _fields_, not by rewinding the status.
- **The money fields stay correctable while `PAID`**, the same reasoning `markPaid`
  uses for `deal_value`: somebody types a reference wrong and has to fix it. Every
  edit is audited, so the trail carries the correction. Once `CONFIRMED`, the row is
  frozen.
- **`VOIDED` is only ever set by the refund path.** No endpoint offers it. A
  payout is not cancelled by hand; the case is refunded, and voiding follows.
- **Only a `PENDING` row can be voided** — `RefundService` already voids only
  `PENDING`, which is correct and unchanged: money that has already left cannot be
  un-sent by a database write. A refund on a case whose payout is already `PAID`
  leaves the payout alone and reports it, because that is a real-world recovery
  problem, not a state-machine one.

### Who may record a payout, and why not the ENM

**Writes: GM and Brand Manager.** Recording that money went out is a commercial
act on the brand's books — the same gate `mark-paid` has, for the same reason, and
`RefundService` sets the precedent that a money path is re-checked in the service
and not only at the endpoint. So `PayoutService` carries its own role guard as
well as `@PreAuthorize`.

**Reads: GM, Brand Manager, ENM.** The ENM manages the expert relationship and
fields "when am I getting paid" — they need to see the ledger. They do not need to
be able to say money left.

If the business says the ENM records payouts in practice, that is a **one-line
widening of two guards** plus the test that pins them, and it should be taken as a
decision rather than assumed here. Recorded in the tracker as such.

## `total_payments_pending` is derived

`Expert.total_payments_pending` is `NOT NULL DEFAULT 0` from `V7` and, like
`current_active_count`, **nothing has ever written it**. Unit 11 established the
rule for this exact situation: derive it, do not start incrementing it.

`SUM(amount) WHERE status = 'PENDING' GROUP BY expert_id`, one batched query in
`PayoutService`, reused by the roster. A running total maintained by hand has to be
adjusted on create, on pay, on void and on every amount correction; four chances to
drift on a figure about money. The column stays dead and unread, recorded in the
tracker with the other two.

## Backend

| Method | Path                        | Auth                     | Notes                                                                                        |
| ------ | --------------------------- | ------------------------ | -------------------------------------------------------------------------------------------- |
| GET    | /api/payouts                | GM · Brand Manager · ENM | brand-scoped list; filter by status, expert, date range; the global date filter applies      |
| GET    | /api/payouts/{id}           | GM · Brand Manager · ENM | one row with its case and expert                                                             |
| PATCH  | /api/payouts/{id}           | GM · Brand Manager       | the manual form: amount, method, reference, paid date. Audited                               |
| POST   | /api/payouts/{id}/mark-paid | GM · Brand Manager       | → `PAID`; requires method, reference, amount, date                                           |
| POST   | /api/payouts/{id}/confirm   | GM · Brand Manager       | → `CONFIRMED`; terminal                                                                      |
| GET    | /api/payouts/batch          | GM · Brand Manager · ENM | the weekly view: `PENDING` grouped by week of `due_date`, with totals per expert and overall |

Reads go through `PayoutLedgerRepository.findScoped` — `payout_ledger` is a
brand-only scoped entity, so `ScopePredicate` already handles every role's tier and
**no new scoping code is written**. A `Tier.SELF` caller (CM, Coordinator) is
deliberately brand-wide on this entity by the same reasoning Unit 08 recorded — but
they are not on any of these routes, so it does not arise.

Expert portal, on Unit 15's chain:

| Method | Path                      | Auth                  | Notes                                                                  |
| ------ | ------------------------- | --------------------- | ---------------------------------------------------------------------- |
| GET    | /api/portal/expert/payout | portal token (EXPERT) | **status, amount, currency and paid date for this case's payout only** |

Read-only, deliberately. The expert confirming their own receipt would be a nicer
loop, but `CONFIRMED` is a statement on the brand's books and the expert token is a
link in an email — `PAID → CONFIRMED` stays a staff act. The portal shows **nothing
about any other case's payout** and nothing about the roster.

## Frontend deliverables

1. **Payout ledger** (`features/payouts`): the brand's rows with status, expert,
   case, amount, due date, paid date. Tabular figures on every money column, and
   the RAG treatment on overdue `PENDING` rows.
2. **The manual form**: amount, method, reference, paid date, in one dialog, with
   the amount prefilled and editable. Method is free text — EvalOS has no rail and
   should not pretend to enumerate one.
3. **Weekly batch view**: `PENDING` grouped by week with per-expert and total
   figures — the screen somebody works down on payout day.
4. **The ENM's read-only view**: the same table without the action buttons, not a
   second screen. One component, actions gated on role — the Unit 10 lesson that a
   button the server refuses is worse than no button, so the gate is the same role
   list the backend holds and a test asserts the two agree.
5. Nav: the ENM's existing **Payouts** entry stops being a placeholder; the GM and
   Brand Manager get it too, through the one `NAV_ITEMS` table.
6. Expert portal: a one-line payout status on the Unit 15 case view.

## Acceptance criteria

- [ ] Delivering a case creates exactly one `PENDING` row, in the same transaction:
      a delivery that rolls back leaves no payout row, and a payout-row failure
      rolls the delivery back.
- [ ] Two concurrent deliveries of one case produce **one** payout row — the
      partial unique index proved in real SQL, not the `deliveryDate` guard alone.
- [ ] The amount is prefilled from `standard_fee`, and an expert with no standard
      fee produces a row with a **null** amount that cannot be marked `PAID` until
      one is entered.
- [ ] `mark-paid` requires method, reference, amount and date; a request missing any
      is refused.
- [ ] `CONFIRMED` and `VOIDED` are terminal; `PAID → PENDING` is refused; fields
      stay editable while `PAID` and frozen once `CONFIRMED`. Every change is
      audited.
- [ ] A GM-approved refund voids a `PENDING` row and **leaves a `PAID` row alone,
      reporting it** rather than silently voiding money already sent.
- [ ] No endpoint can set `VOIDED`.
- [ ] A Case Manager and a Project Manager get 403 from every payout route; the ENM
      gets 200 on the reads and **403 on all three writes**, checked in the service
      as well as at the route.
- [ ] A Brand Manager sees only their brand's payouts; the GM sees all brands.
- [ ] `total_payments_pending` shown on the roster equals the derived sum while the
      column in the database is still `0`.
- [ ] An `EXPERT` portal token reads that case's payout status only — asserted
      against a second case for the same expert.
- [ ] `npm run build` green; `./mvnw verify` green, with the new index exercised
      DB-gated against local Postgres.

## Invariants honored

Brand isolation through `findScoped` on every read (1); EvalOS records but never
collects or disburses — no invoicing, no rail (2); role + ownership before every
write, re-checked in the service on the money path (3); `payment_detail` is **not**
shown on the payout form, in the ledger, in the batch view or in the expert's
portal view — an ENM who needs it looks nowhere, because there is no read path
(4); a `PENDING` payout is the money-out side of the open-liability figure, and a
refund voids it (5); thin controllers, the rules in `PayoutService` (6); EvalOS is
the system of record for payouts (7); an append-only audit entry on every creation,
edit and status change (13); no email — the expert learns their status in the
portal (14).

## Files touched

**Created.** Backend: `service/PayoutService.java`, `web/PayoutController.java`
(+ DTOs: ledger row, batch group, form request). Migration
`V<next>__payout_case_unique.sql` (the partial unique index only). Frontend:
`frontend/src/features/payouts/*` (`PayoutLedger`, `PayoutForm`, `BatchView`,
`payoutApi`) + a payout-rules test.

**Modified.** `service/CaseLifecycleService.java` — the payout row created inside
`deliverToClient`'s existing transaction. `repository/PayoutLedgerRepository.java`
— the batch and sum projections. `domain/PayoutLedger.java` — accessors and the
setters the form needs; `case_id`/`expert_id`/`brand_id` stay `updatable = false`.
`web/ExpertPortalController.java` (Unit 15) — the one payout-status route.
`frontend/src/features/shell/navigation.ts`,
`frontend/src/features/expert-portal/ExpertCaseView.tsx`.

**Not touched.** `service/RefundService.java` — its voiding behaviour is already
correct. **No new table** — `payout_ledger` is Unit 03's, and `V8` is not edited
(invariant 9). `service/ScopePredicate.java`.
