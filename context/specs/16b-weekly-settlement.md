# Unit 16b — Weekly settlement: one payment, many drafts

> **Current truth for how a payout is paid.** Supersedes the *payment* half of
> `16-payout-ledger.md`: its per-row manual form, its `method`/`reference`/`paid_date`
> columns, and its per-row `confirm`. Everything else in spec 16 stands as written —
> the row is still created on delivery inside `deliverToClient`'s transaction, still
> prefilled from `Expert.standard_fee`, still voided only by `RefundService`, still
> derives `total_payments_pending`. Read 16 first; this document is the delta.

**Phase:** 2 — Connect the seams
**Depends on:** 03 (`payout_ledger`), 04 (`deliverToClient`, `RefundService`),
11 (`standard_fee`)
**Unlocks:** 17 (money-out tiles, now sourced from two tables), 18 (Handoff C joins
the delivery transaction that opens the row)
**Gating open questions:** none. The payout **rail** is still a resolved question —
there isn't one.

---

## Why spec 16's payment model was wrong

Spec 16 assumed the unit of payment is the unit of work: one delivered case, one
payout row, one form, one reference. That is not how the business pays.

**The expert charges per draft and is paid once a week.** Dr. Smith delivers three
drafts between Monday and Friday and is owed $350 + $350 + $400. On payout day she
receives **one Zelle transfer of $1,100**, carrying **one reference**. Spec 16 would
have that recorded as three rows each independently marked `PAID` with the same
string typed three times — three chances to fumble a digit, three dates that can
disagree, and no object anywhere that corresponds to the thing that actually left the
bank.

So the correction is one idea: **a payment is its own record, and payout rows point
at it.**

| | Spec 16 | This unit |
|---|---|---|
| Unit of work | one delivered case | unchanged |
| Unit of payment | one delivered case | **one transfer, covering N cases** |
| Where `reference` lives | the payout row | **the payment** |
| What `CONFIRMED` means | this row was acknowledged | **this transfer was acknowledged** |
| Who may record it | GM · Brand Manager | GM · Brand Manager · **ENM** |

**What did *not* change, and this matters:** the ENM stays client-blind. An earlier
reading of this requirement had experts on weekly *retainers* stationed on client
accounts, which would have forced client identity onto the ENM's screens and rewritten
the supply-side-axis rule in `project-overview.md`. Per-draft-charged-weekly-settled
needs none of that. There is no retainer, no `weekly_rate`, no expert-to-client
assignment, and **no new concept of a "client" anywhere in this unit.** "Weekly" is a
grouping of `due_date`, not a pay period.

## Goal

Replace the WhatsApp group where payout details are posted. A case is delivered, a
`PENDING` row opens against the expert. On payout day the ENM works down a week,
ticks the drafts they are about to pay for, sends the money outside EvalOS, and
records **one payment** that settles all of them at once.

**EvalOS never moves money.** There is no disbursement rail, no payment platform, no
bank API, and no stored bank credential. This is a ledger.

**Verifiable result:** delivering a case opens exactly one `PENDING` row prefilled
with the expert's standard fee; the ENM selects that expert's pending rows for a week
and records one payment with amount, method, reference and date; all selected rows
flip to `PAID` together and point at that payment; the payment appears in history and
opens to a detail view naming every draft it covered; and a GM-approved refund voids a
still-pending row but reports rather than voids one already attached to a payment.

## In scope

- The `payout_payment` table and the `payment_id` link from `payout_ledger`.
- The settle operation: N pending rows → one payment, in one transaction.
- The weekly batch screen, the expert page, payment history, payment detail.
- Correcting a `PENDING` row's amount before it is settled.
- `CONFIRMED` at the payment, cascading to its rows.
- The two `brand` settings spec 16 reads but that were never added.
- Widening the write guard to the ENM.

## Out of scope

- **Any disbursement rail or payment-platform integration.** Resolved; not a question.
- **Retainers, weekly rates, expert-to-client assignments.** Considered and rejected
  above; the business charges per draft.
- **Per-case rate cards.** The amount is prefilled from the single
  `Expert.standard_fee` and then typed by a human. Spec 16 ruled a pricing engine out
  by name and that stands.
- **Reports and CSV export.** Real and wanted, in no unit, and deliberately not
  smuggled in here — it is its own decision.
- A human-readable payment code (`PAY-000124`). The ENM's own `reference` is already
  the handle they quote; a second identifier is a second thing to keep in step. Three
  lines if it is ever asked for.
- Invoicing and anything client-side money. GHL's, by invariant 2.
- Tax forms, 1099s, currency conversion.
- The dashboard tiles that count these figures — Unit 17's, fed by this unit's
  queries. A second dashboard here would be a second thing to keep in step.

## Schema

One migration, `V<next>__payout_payment.sql`.

### New: `payout_payment`

```sql
CREATE TABLE payout_payment (
    id          uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    brand_id    uuid          NOT NULL REFERENCES brand (id),
    expert_id   uuid          NOT NULL REFERENCES expert (id),
    amount      numeric(12,2) NOT NULL CHECK (amount > 0),
    currency    text          NOT NULL,
    method      text          NOT NULL,
    reference   text          NOT NULL,
    paid_date   timestamptz   NOT NULL,
    notes       text,
    confirmed_at timestamptz,
    recorded_by uuid          NOT NULL REFERENCES team_member (id),
    created_at  timestamptz   NOT NULL DEFAULT now()
);

CREATE INDEX idx_payment_brand_expert ON payout_payment (brand_id, expert_id);
CREATE INDEX idx_payment_brand_paid ON payout_payment (brand_id, paid_date);
```

`amount > 0`, not `>= 0`: a payment of nothing is not a payment, and it would sum
into every total on the finance dashboard while looking like a settled week.

`expert_id` is `NOT NULL` — unlike `payout_ledger`, where it is nullable because a
row can theoretically open on a case with no expert. A payment with no payee is not a
thing that can be recorded.

`notes` lives here rather than on the row because it describes the transfer, not one
draft.

`PayoutPayment` extends **`ScopedEntity`**, like `PayoutLedger` — which supplies `id`,
`brand_id` and `created_at`, and refuses at `@PrePersist` to write a row with no brand.
That hook is the last line of defence for isolation and this table gets it for free;
the entity declares neither field.

### Changed: `payout_ledger`

```sql
ALTER TABLE payout_ledger
    ADD COLUMN payment_id uuid REFERENCES payout_payment (id);

ALTER TABLE payout_ledger
    DROP COLUMN method,
    DROP COLUMN reference,
    DROP COLUMN paid_date;

CREATE INDEX idx_payout_payment ON payout_ledger (payment_id);
```

**Dropping the three columns is a deliberate departure from the precedent set when the
inbound HMAC was removed**, where `brand.ghl_webhook_secret` and
`webhook_event.signature_verified` were left in the schema unread rather than dropped.
Two things differ. First, that decision turned on not writing a migration *at all* for
two dead columns; here a migration is being written regardless, so dropping them costs
one statement and no churn. Second, and the real reason: these three columns sit on a
**money** table and read as load-bearing. A future reader who finds
`payout_ledger.reference` will reasonably assume a row carries its own reference, and
write code that half-works. Leaving a convincing trap on the payout path is worse than
leaving two inert columns on a webhook archive.

Nothing has ever written them — Unit 16 was never built — so the drop cannot lose data.
`V8` itself is not edited (invariant 9).

### Also in this migration: the two settings spec 16 assumes

Spec 16 reads "the brand's configured currency" and "the configured payout term" at
row-creation time. **Neither exists.** `V2__brand.sql` has `name`, `slug`, `active`,
`webhook_endpoint_token`, `created_at` and nothing else. This was a latent hole in
spec 16 that only surfaced when the schema was checked against it.

```sql
ALTER TABLE brand
    ADD COLUMN currency text,
    ADD COLUMN payout_term_days int NOT NULL DEFAULT 7;

UPDATE brand SET currency = 'USD' WHERE currency IS NULL;

ALTER TABLE brand
    ALTER COLUMN currency SET NOT NULL;
```

`currency` ends `NOT NULL` **with no default** on the column, backfilled once so the
constraint can land on existing rows. Spec 16's reasoning is upheld and is worth
restating because it is the one guess in this unit that spends real money: an expert
on a GBP agreement paid a USD number is wrong twice, in the amount and in the record
of what was owed. A brand added later must state its currency; there is no column
default to fall back on.

`payout_term_days` takes a default because a wrong *due date* is a scheduling
annoyance, not a wrong payment — it is visible on every screen and correctable.

### The concurrency guard from spec 16, still needed

```sql
CREATE UNIQUE INDEX uq_payout_per_case
    ON payout_ledger (case_id) WHERE status <> 'VOIDED';
```

Unchanged in purpose from spec 16: `deliverToClient` guards on
`getDeliveryDate() == null`, which is a check-then-act, and `Case` has no `@Version`,
so two concurrent deliveries can both read null and both open a payout row. The index
cannot race; the loser rolls back. Partial on `VOIDED` so a refunded case that
re-delivers is not blocked by the tombstone.

## Settlement

```
POST /api/payouts/settle
  { expertId, payoutIds[], amount, method, reference, paidDate, notes }
```

One transaction: insert the `payout_payment`, then attach every named row to it.

### The rules, and why each one is there

A request is refused unless **all** of these hold:

1. **`payoutIds` is non-empty.** A payment settling nothing is a payment for nothing.
2. **Every row is in the caller's brand**, resolved through
   `PayoutLedgerRepository.findScoped` — not by trusting the ids in the body. An id is
   a guess anyone can make; scoping is what makes it not one.
3. **Every row names `expertId`.** One transfer, one payee. Settling two experts'
   drafts under one reference is a record that cannot be reconciled against the bank.
4. **Every row is `PENDING`.** See the race note below.
5. **Every row has a non-null `amount`.** Spec 16 leaves the amount null when the
   expert has no `standard_fee`, precisely so somebody has to decide it. A null in a
   sum is how a `PAID` row for an unspecified amount happens.
6. **Every row shares one currency**, and the payment records it.
7. **`sum(row amounts) == amount` exactly.**

Rule 7 is the load-bearing one. If the ENM sent a number that is not the sum, the fix
is to correct the row amounts first — they are editable while `PENDING`, through
`PATCH /api/payouts/{id}`, and every edit is audited. A payment whose amount is not
what it settled is a ledger that disagrees with the bank, and it disagrees *silently*:
nothing downstream can detect it, because both numbers look reasonable on their own.
Tolerating a mismatch would also make the finance dashboard's money-out figure
ambiguous — the sum of payments and the sum of settled rows would be two different
answers to one question.

### The race, and the one statement that closes it

Rules 2–5 are read, then written. Two ENMs (or one ENM and two browser tabs) settling
overlapping selections could both read `PENDING` and both attach — the same
check-then-act shape spec 16 wrote its partial unique index for.

The attach is therefore **one conditional statement**, not a read followed by saves:

```sql
UPDATE payout_ledger
   SET payment_id = :paymentId,
       status     = 'PAID',
       recorded_by = :actor
 WHERE id = ANY(:ids)
   AND brand_id = :brandId
   AND status = 'PENDING'
```

and the service asserts the affected-row count equals `ids.size()`. Anything less means
another transaction won a row; the whole settlement rolls back, including the payment
insert, and the ENM is told which rows moved. No new column, no `@Version`, no explicit
lock — the database decides, and it cannot decide twice.

`recorded_by` on the row keeps its spec 16 meaning, "who last recorded something about
this row", and is set again by an amount correction. The payment carries its own
`recorded_by` for who sent the money.

## Status progression

`PayoutStatus` is unchanged: `PENDING · PAID · CONFIRMED · VOIDED`.

| From | To | Who | Means |
|---|---|---|---|
| `PENDING` | `PAID` | GM · BM · ENM | settled by a payment; never set alone |
| `PAID` | `CONFIRMED` | GM · BM · ENM | the expert acknowledged the **transfer** |
| `PENDING` | `VOIDED` | `RefundService` only (GM) | the case was refunded |

- **`PAID` is only ever reached through `settle`.** There is no endpoint that marks a
  single row paid, because there is no such event in the business — money leaves in
  transfers, not in drafts.
- **`CONFIRMED` is set on the payment and cascades to its rows.** One transfer gets one
  acknowledgement. `POST /api/payments/{id}/confirm` stamps `confirmed_at` and moves
  every attached row `PAID → CONFIRMED` in the same transaction. A row cannot be
  confirmed on its own; the endpoint does not exist.
- **Forward only.** `CONFIRMED` and `VOIDED` are terminal. `PAID → PENDING` does not
  exist: unsending money is not a thing.
- **What stays correctable, and what freezes.** A `PENDING` row's amount is editable.
  Once settled, the row is frozen — its amount is part of a payment's sum and changing
  it would break rule 7 after the fact. The **payment's** `method`, `reference` and
  `notes` stay editable while it is unconfirmed, for the same reason spec 16 gave:
  somebody types a reference wrong and has to fix it. `amount`, `paid_date` and the set
  of attached rows do not change — correcting those means the payment as recorded did
  not happen, which is a void-and-re-record, not an edit. Every change is audited.
- **`VOIDED` is only ever set by the refund path.** No endpoint offers it.

### Refunds are unchanged and `RefundService` is not touched

`RefundService` voids `payouts.findByCaseIdAndStatus(caseId, PENDING)` and leaves
anything else alone. That is already exactly right under this design: a row attached to
a payment is money that left the bank, and a database write cannot un-send it. The
refund reports it instead — a real-world recovery problem, not a state-machine one.

**The only new wrinkle is a partially-settled week**, and it needs no code: a refund
voids that case's pending row, and the rows already settled in earlier payments are
untouched because they are not `PENDING`. Voiding a row that is not attached to any
payment also cannot disturb rule 7, since no payment's sum includes it.

## "Overdue" is derived

`PENDING AND due_date < now`, computed on read. It is **not** a fifth `PayoutStatus`
value, for the same reason `total_payments_pending` is not a maintained column: a
status that has to be flipped by a clock is a status that is wrong between ticks, and
it would need a job (Unit 19, unbuilt) to be wrong less often. It drives the RAG
treatment from `ui-context.md` and nothing else.

## Weeks

Mon–Sun in `BusinessCalendar.ZONE` (`America/Los_Angeles`), reusing `DateWindow`'s
**half-open** contract — `endInstant()` exclusive. Both halves matter. The zone,
because a payout day is the business's day and a UTC week boundary puts Sunday
afternoon's delivery in next week for a California ENM. The half-open bound, because
PR #18 already found the inclusive version putting a midnight instant in two adjacent
windows, and a draft counted in two weeks is a draft that can be paid twice.

A row's week is the week of its **`due_date`**, not its delivery date: the batch screen
answers "what is owed by when", and `due_date` is delivery + `brand.payout_term_days`.

## Backend

| Method | Path | Auth | Notes |
|---|---|---|---|
| GET | `/api/payouts` | GM · BM · ENM | brand-scoped list; filter by status, expert, week, overdue; the global date filter applies |
| GET | `/api/payouts/{id}` | GM · BM · ENM | one row with its case, expert, and payment if settled |
| GET | `/api/payouts/batch` | GM · BM · ENM | a week's `PENDING` rows grouped by expert, with per-expert subtotals and a week total |
| PATCH | `/api/payouts/{id}` | GM · BM · ENM | amount only, `PENDING` only. Audited |
| POST | `/api/payouts/settle` | GM · BM · ENM | the seven rules above; creates one payment |
| GET | `/api/payments` | GM · BM · ENM | payment history, brand-scoped, filterable by expert and date |
| GET | `/api/payments/{id}` | GM · BM · ENM | one payment with every row it settled |
| PATCH | `/api/payments/{id}` | GM · BM · ENM | method, reference, notes; unconfirmed only. Audited |
| POST | `/api/payments/{id}/confirm` | GM · BM · ENM | → `CONFIRMED`, cascading. Terminal |

`payout_payment` is brand-only scoped, exactly like `payout_ledger`, so
`PayoutPaymentRepository` declares `ScopePredicate.Fields.brandOnly("brandId")` and
**no new scoping code is written**. A `Tier.SELF` caller is brand-wide on both
entities by the reasoning Unit 08 recorded, and is on none of these routes anyway.

### The ENM may now record payments — recorded as a decision

Spec 16 restricted writes to GM and Brand Manager and gave the ENM reads only,
reasoning that "recording that money went out is a commercial act on the brand's
books". It also said, in as many words, that if the business has the ENM doing it in
practice then that is a one-line widening **to be taken as a decision rather than
assumed**. It has been taken: **the ENM sends the Zelle transfer, so the ENM records
it.** GM and Brand Manager retain every power they had.

The guard lives in **`PayoutService` as well as `@PreAuthorize`** — unchanged from spec
16, and for the reason `RefundService` set: a money path is re-checked in the service,
not only at the route. A Project Manager, Case Manager or Coordinator gets 403
everywhere in this unit, at both layers.

Because the ENM now writes, `recorded_by` becomes the field that says which of three
roles sent the money, and the audit entry names them. That is the accountability spec
16 was protecting, preserved without the restriction.

## Frontend deliverables

Four screens. The seven mocked screens collapse: the "Expert Payouts" list and the
"Weekly Payout Batch" view are the same data, the same week and the same actions, and
building both would be building it twice.

1. **Weekly batch** (`features/payouts/PayoutBatch.tsx`) — a week picker, then that
   week's `PENDING` rows grouped by expert: draft count, subtotal, method hint, and a
   **Record payment** action per expert group. Header totals: due, paid, remaining.
   Tabular figures on every money column; RAG on overdue rows.
2. **Record payment dialog** (`PaymentForm.tsx`) — **multi-row**. The expert's pending
   drafts are listed with checkboxes, all ticked by default; the amount field is the
   live sum of what is ticked and is **read-only**, because rule 7 makes any other
   number a refusal and a field the server will always reject is worse than no field.
   Then method, reference, paid date, notes. Unticking a draft changes the total in
   front of the user, which is the whole point of settling by selection.
   Method is an `<input list>` datalist — Zelle and whatever else has been used before,
   suggested but not enumerated. EvalOS has no rail and should not pretend to have one.
3. **Expert payouts page** (`ExpertPayouts.tsx`) — one expert: their pending drafts,
   then their payment history. Reached from the batch screen and from the roster.
4. **Payment detail** (`PaymentDetail.tsx`) — amount, week, method, reference, date,
   who recorded it, notes, status, and **every draft it settled**, each linking to its
   case.

Three things that are not screens:

- **One component, actions gated on role** — not a second read-only screen. The Unit
  10 lesson is that a button the server refuses is worse than no button, so the gate is
  the same role list the backend holds and a test asserts the two agree.
- **Nav**: `/payouts` stops being a placeholder for the ENM and gains GM and Brand
  Manager, through the one `NAV_ITEMS` table.
- **Expert portal** (when Unit 15 lands): the one-line payout status on the case view,
  read-only, exactly as spec 16 wrote it. **Status and amount only — never the
  payment's reference**, which names a bank transfer and belongs to the brand's
  records, not the expert's case view.

## Acceptance criteria

- [ ] Delivering a case opens exactly one `PENDING` row in the same transaction: a
      delivery that rolls back leaves no payout row, and a payout-row failure rolls the
      delivery back.
- [ ] Two concurrent deliveries of one case produce **one** row — `uq_payout_per_case`
      proved in real SQL, not the `deliveryDate` guard alone.
- [ ] The amount is prefilled from `standard_fee`; an expert with no standard fee gets
      a **null** amount, and a settlement naming that row is refused until one is entered.
- [ ] Settling three of an expert's pending rows creates **one** `payout_payment`,
      flips all three to `PAID`, and points all three at it.
- [ ] A settlement whose `amount` is not the exact sum of its rows is refused, and
      **no payment row is left behind** — asserted by count, not just by response code.
- [ ] A settlement naming two different experts is refused; naming another brand's
      payout id is refused as not-found, not as forbidden.
- [ ] A settlement naming an already-`PAID` row is refused and rolls back whole.
- [ ] **Two concurrent settlements overlapping by one row: one succeeds, one fails, and
      the failed one leaves no payment and no partially-attached rows.** The conditional
      `UPDATE` + affected-count assertion, proved against real Postgres.
- [ ] Confirming a payment moves every attached row `PAID → CONFIRMED`; there is no
      route that confirms a single row.
- [ ] `CONFIRMED` and `VOIDED` are terminal; `PAID → PENDING` is refused; a settled
      row's amount is frozen; an unconfirmed payment's reference is editable and a
      confirmed one's is not. Every change is audited.
- [ ] A GM-approved refund voids a `PENDING` row and **leaves a settled row alone,
      reporting it** — asserted on a case whose expert has other rows in the same
      payment, which must be untouched.
- [ ] No endpoint can set `VOIDED`.
- [ ] A Case Manager, Project Manager and Coordinator get 403 from every route in this
      unit; the **ENM gets 200 on every read and every write**, checked in the service
      as well as at the route.
- [ ] A Brand Manager sees only their brand's payouts and payments; the GM sees all.
- [ ] A brand with no currency cannot be inserted; a payout row's currency is its
      brand's, and a settlement mixing currencies is refused.
- [ ] Week grouping is Mon–Sun in `America/Los_Angeles` and half-open: a delivery whose
      `due_date` is exactly a week boundary instant appears in **one** week, asserted
      against both adjacent weeks.
- [ ] `total_payments_pending` shown on the roster equals the derived sum while the
      column in the database is still `0`.
- [ ] `npm run build` green; `./mvnw verify` green, with the new indexes and the
      concurrent-settlement test exercised DB-gated against local Postgres.

## Invariants honored

Brand isolation through `findScoped` on every read and inside the settle `UPDATE` (1);
EvalOS records but never collects or disburses — no rail, no stored bank credential,
no invoicing (2); role re-checked in the service on every money write (3);
`payment_detail` is **not** shown on the batch screen, the payment form, the payment
detail, the expert page or the portal — an ENM who needs it looks nowhere, because
there is no read path (4); a `PENDING` payout is the money-out side of open liability
and a refund voids it (5); thin controllers, the rules in `PayoutService` (6); EvalOS
is the system of record for payouts (7); `V8` and `V2` are not edited — the changes are
a new migration (9); an append-only audit entry on every creation, settlement, edit,
confirmation and void (13); no email — the expert learns their status in the portal (14).

**Unchanged by design:** the ENM remains client-blind. Nothing in this unit puts a
client name, case content, document, draft, note or deal value on an ENM screen. The
supply-side-axis rule in `project-overview.md` needs no amendment.

## Files touched

**Created.** Backend: `domain/PayoutPayment.java`,
`repository/PayoutPaymentRepository.java`, `service/PayoutService.java`,
`web/PayoutController.java`, `web/PaymentController.java` (+ DTOs: ledger row, batch
group, settle request, payment summary, payment detail). Migration
`V<next>__payout_payment.sql`. Frontend: `frontend/src/features/payouts/*`
(`PayoutBatch`, `PaymentForm`, `ExpertPayouts`, `PaymentDetail`, `payoutApi`) + a
settlement-rules test.

**Modified.** `service/CaseLifecycleService.java` — the payout row opened inside
`deliverToClient`'s existing transaction. `domain/PayoutLedger.java` — drop the
`method`, `reference` and `paidDate` fields, add `paymentId`; `case_id`/`expert_id`/
`brand_id` stay `updatable = false`. `repository/PayoutLedgerRepository.java` — the
batch projection, the derived pending sum, and the conditional settle `UPDATE`.
`domain/Brand.java` — `currency`, `payoutTermDays`.
`frontend/src/features/shell/navigation.ts`.

**No seed migration.** The `UPDATE brand SET currency = 'USD'` backfill inside the new
migration already covers the two locally-seeded brands, so `V900`/`V901` are neither
edited (invariant 9) nor joined by a third.

**Not touched.** `service/RefundService.java` — its voiding behaviour is already
correct under this design and the reasoning is recorded above rather than changed in
code. `service/ScopePredicate.java` — brand-only scoping already covers both entities.
`V8__payout_ledger.sql`, `V2__brand.sql` (invariant 9).
