# Unit 65 — The case fee on the offer, and Payouts as its own module

**Decided 2026-09-30 by the business.** **Status: SPECCED 2026-09-30, not built** (branch
`feature/unit-65-case-fee-payouts`).

Edits decision **D59** (expert payments). Builds on **`62-expert-payouts-view.md`** and the payout
half of **`63-enm-workspace.md`**; supersedes neither — Pending → Processing → Paid and the expert's
*Confirm received* stay exactly as Unit 63 left them.

## 0. Today, before this unit

- **Nobody sets what a case pays.** An offer (`expert_case_offer`) records who, when and the outcome
  — no amount. It is written in three places in `CaseLifecycleService`: `assignCaseManager` (the PM
  names the CM and the expert), `reassignExpert` (GM / PM / ENM / CM), `retakeExpert` (D62).
- At delivery `PayoutService.openForDelivery` opens the `payout_ledger` row at the expert's
  **`standard_fee`**, read at that moment. The GM / BM / ENM may **correct** that amount while the
  row is `PENDING` (`PATCH /api/payouts/{id}`, `ExpertPayouts.tsx`).
- The expert sees no amount until the payout appears on their portal Payouts page.
- Staff payouts are **one screen**, `/payouts`: one week's pending rows grouped by expert, the
  record-payment form, and the weekly / monthly / yearly summary with two CSV exports. Two pages
  hang off it (`/payouts/experts/:id`, `/payouts/payments/:id`), neither in the nav. There is no
  view of **every expert's position at once**, and no register of cases and what each is worth.

## 1. The decision

1. **Every offer carries the amount that case pays the expert** — the *offered amount*, in the
   brand's (single) currency. The dialog's Fee field left blank means the expert's `standard_fee`;
   a retake keeps the declined offer's fee. An offer with neither is refused (*"Set a fee for this
   case — this expert has no standard fee."*).
2. **Who sets it: GM, PM, PC, ENM.** A CM who makes an offer (a reassignment) offers at the expert's
   standard fee and cannot change it; if that expert has no standard fee, the CM's offer is refused
   with *"Ask a PM, PC or ENM to set the fee for this case."*
3. **Editable only while the offer is open.** Accept, decline, timeout or supersede freezes it. A reassignment
   or retake is a new offer with its own amount.
4. **The expert sees the amount before accepting**, and accepting is agreeing to it.
5. **No change after acceptance, and no adjustments.** No bonus, deduction, advance or correction:
   the business wants *offered amount, status, done or not* — not bookkeeping. The pending-payout
   **correct-amount action is removed** — except filling in an amount that is missing (§2).
6. **At delivery the payout opens at the accepted offer's amount**, not the standard fee.
7. **Status per case**, derived, one word: **Offered → Accepted** (committed) **→ Pending**
   (delivered, not sent) **→ Processing** (ENM recorded the transfer) **→ Paid** (expert confirmed —
   *done*). Or **Declined / Timed out / Superseded**, which end that offer. Payment stays manual (D59).
8. **Everything is logged with who, date and time, before → after**: amount set, amount edited,
   accepted, declined, timed out, payout opened, transfer recorded, expert confirmed. The log is the
   existing append-only `audit_event`; no second log table.
9. **Payouts is its own module** in the staff app, for the GM, BM and ENM (the same `MAY_RECORD`
   set as today) — four screens, §4.

## 2. Data

One Flyway migration in the main tree, **`V79__offer_fee.sql`**:

```sql
ALTER TABLE expert_case_offer
    ADD COLUMN fee        numeric(12,2) CHECK (fee >= 0),
    ADD COLUMN fee_set_by uuid REFERENCES team_member (id),
    ADD COLUMN fee_set_at timestamptz;

-- Open and accepted offers get the fee they would have been paid at delivery, so none is
-- left unpriced and today's payouts appear in the register.
UPDATE expert_case_offer o
   SET fee = e.standard_fee, fee_set_at = now()
  FROM expert e
 WHERE o.outcome IN ('OFFERED', 'ACCEPTED') AND e.id = o.expert_id;

-- An accepted offer that already has a payout takes the payout's amount: that is what was agreed
-- in practice (the standard fee, possibly corrected before this unit).
UPDATE expert_case_offer o
   SET fee = p.amount, fee_set_at = now()
  FROM payout_ledger p
 WHERE o.outcome = 'ACCEPTED' AND p.case_id = o.case_id AND p.expert_id = o.expert_id
   AND p.status <> 'VOIDED' AND p.amount IS NOT NULL;
```

- **Nullable in the schema**, required by the service on every new offer: closed historical offers
  have no fee and never will, and an open offer whose expert has no standard fee stays null until a
  PM / PC / ENM sets one — the portal shows it as *"Fee not set yet"* and **Accept is refused while
  it is null**.
- The currency is the brand's; the offer does not repeat it. `fee_set_by` / `fee_set_at` hold who
  set the current amount and when (null `fee_set_by` = the migration); the history is in the log.
- `ExpertCaseOffer` gains `fee`, `feeSetBy`, `feeSetAt` and `setFee(BigDecimal, UUID actor)` that
  throws `IllegalTransitionException` unless `outcome == OFFERED`.
- **Seed trees:** `V914__seed_local_offer_fee.sql` and `V953__seed_testprod_offer_fee.sql` repeat
  the backfill `WHERE fee IS NULL`, because on a fresh database the seeds insert their offers after
  `V79` has run.
- **Existing `payout_ledger` rows are untouched.** A delivery whose accepted offer has no fee (an
  offer accepted before `V79` whose expert had no standard fee) falls back to `standard_fee`, as
  today — which may be null.
- **A payout with no amount can have it set once.** `PATCH /api/payouts/{id}` stays, narrowed: it
  sets the amount only while the row is `PENDING` **and its amount is null**, otherwise 409. Without
  it such a row could never be settled (`settle` refuses a row with no amount) and the expert would
  never be paid. Correcting an amount that exists stays removed (rule 5).
- No new table. No change to `payout_ledger`, `payout_payment` or `PayoutStatus`.

## 3. API

| Change | Endpoint | Roles | Rule |
|---|---|---|---|
| Amount on each offer | `fee` added to the bodies of `POST /api/cases/{id}/assign-cm`, `/reassign-expert`, `/expert/retake` | unchanged | optional `fee`: blank → standard fee (retake: the declined offer's fee); neither → 409; a CM's value must be blank or equal the standard fee, else 403 |
| Edit an open offer's amount | `PATCH /api/cases/{id}/expert/offer/fee` `{fee}` | GM, PM, PC, ENM | the case's open offer only, else 409; scoped load (a PC only on cases assigned to them) |
| Register | `GET /api/payouts/cases?status&expertId&from&to&q` | GM, BM, ENM | one row per offer that has a fee **or** a non-voided payout (so no payout owed today is missing); amount = the payout's when one exists, else the offer's fee; brand-scoped |
| Register CSV | `GET /api/payouts/cases/export` (same params) | GM, BM, ENM | through the existing `csvField` (quoted, formula-safe) |
| One offer's log | `GET /api/payouts/cases/{offerId}/history` | GM, BM, ENM | `audit_event` rows for the offer, its payout and its payment, oldest first |
| Per-expert totals | `GET /api/payouts/experts` | GM, BM, ENM | committed / pending / processing / paid + oldest pending due date, per expert and currency |
| Overview | `GET /api/payouts/overview?from&to` | GM, BM, ENM | the four tiles + the attention list (§4.1) |
| Current offer on a case | `GET /api/cases/{id}/expert/offer` → `{offerId, fee, currency, outcome, feeSetByName, feeSetAt, log}` | every production role (scoped case load) | for the case page's expert card |
| Expert accepts | `POST /api/portal/expert/accept` gains query param `fee` (beside `caseId`) | expert token | must equal the open offer's current fee, else **409** *"The fee for this case changed — review it."* |
| Expert case read | `ExpertCaseSummary` and `ExpertCaseView` gain `offeredFee`, `currency` | expert token | the open or accepted offer's amount |
| **Narrowed** | `PATCH /api/payouts/{id}` → *set a missing amount* (`PayoutService.setMissingAmount`, was `correctAmount`) | GM, BM, ENM | `PENDING` and amount null only, else 409; audited |

**Audit.** New `AuditAction`s are not needed: `CREATED` / `UPDATED` on entity type `OFFER` with
`{fee}` before → after, and the accept / decline / timeout already written by the lifecycle gain
`fee` in their snapshot, so the log shows *which* amount was agreed.

**Status derivation** lives in one place, `PayoutRegisterService.status(outcome, payoutStatusOrNull)`: the
offer's outcome until a payout exists, then the payout's status mapped through the existing
`PENDING / PAID→Processing / CONFIRMED→Paid` labels. `VOIDED` payouts are ignored, as
`uq_payout_per_case` already does.

**Every query is brand-scoped** through `findScoped` / `ScopedRepository`; the register and totals
are one query each (offers full-joined to the non-voided payout by case and expert), not per-row lookups.

## 4. Staff screens — the Payouts module

The single `/payouts` nav entry becomes a **Payouts** group in `navigation.ts` (GM, BM, ENM):

### 4.1 Overview — `/payouts`

*Where does the money stand?* A range menu (this week / month / year / custom). Four tiles, each a
count and an amount: **Committed** (accepted, not delivered), **Pending**, **Processing**, **Paid**.
A **Needs attention** list: pending past its due date; processing with no confirmation after
**7 days** (a constant, `CONFIRM_NUDGE_DAYS`). Below, the existing `PayoutSummary` and both exports
move here unchanged.

### 4.2 Cases — `/payouts/cases`

*What is each case worth, and is it done?* The register: case, expert, offered amount, set by +
when, status chip, due date, sent date, confirmed date, a ✓ in *Done*. Filters for status, expert,
date range, and a search over case code and expert name; filters live in the URL so a view can be
shared. Clicking a row opens a **side panel** with the offer's facts and its **log** (§3 history):
each line *who · date and time · what · old → new*. *Export CSV* exports exactly what is filtered.

### 4.3 Experts — `/payouts/experts`

*Who is owed how much?* One row per expert: Committed, Pending, Processing, Paid, oldest pending.
Sortable by any column. A row opens the existing `/payouts/experts/:id` page, whose amount input now
appears **only on a row with no amount** (§2) and keeps pending items + payment history.

### 4.4 Pay run — `/payouts/pay`

The existing `PayoutBatch` weekly screen, moved, otherwise unchanged. `/payouts/payments/:id`
stays where it is.

### 4.5 Where the amount is entered (outside the module)

- **Fee** field in the assign and reassign quick actions (`boardRules.ts`, rendered by
  `QuickActionDialog`, also reached from `ExpertAssignmentPage`) — *optional: blank uses the
  expert's standard fee*. The retake stays one click and keeps the declined offer's fee; any
  change is made with **Edit** on the case while the offer is open. A CM never gets an editable fee.
- The case page's expert card shows the amount and its status, **Edit** while the offer is open
  (GM / PM / PC / ENM), and *History* opening the offer's log. **Built by Unit 66** (spec `66` §3.7),
  which redraws the case page; this unit builds the fee field in the dialogs and the module.

**Design.** Existing tokens (`tokens.css`) and the dashboard tile / table patterns; charts, if any,
stay inline SVG. Built with the frontend-design skill and Chrome-checked at 1440 and 390 wide.

## 5. Expert portal

- `/new`: each offered case shows **Fee for this case: {amount}** above Accept / Ask for evidence /
  Decline (`components/Answers.tsx`); *Fee not set yet* disables Accept.
- Accept sends the fee it displayed; a 409 reloads the case and says the fee changed.
- `/case` fact strip shows the agreed fee. Payouts page and *Confirm received* unchanged.

## 6. Tests — one per rule

Backend:
- `CaseLifecycleServiceTest#anOfferCannotBeMadeWithoutAFee`
- `#aCaseManagerOffersAtTheStandardFeeOnly`
- `#theFeeCanBeEditedWhileTheOfferIsOpenAndNotAfter` (accepted, declined, timed out, superseded → 409)
- `ExpertPortalServiceTest#acceptingAStaleFeeIsRefused`, `#acceptingWithNoFeeIsRefused`
- `PayoutServiceTest#aMissingAmountCanBeSetOnceAndAnExistingOneNever`
- `PayoutServiceTest#deliveryOpensThePayoutAtTheAcceptedFee`, `#anOfferAcceptedBeforeV79FallsBackToTheStandardFee`
- `#everyFeeSetEditAndAcceptIsAudited` (before → after, actor, time)
- `#theRegisterDerivesOneStatusPerOffer`, `#expertTotalsAddUpByStatus`, `#theOverviewFlagsOverdueAndUnconfirmed`
- `PartyScopedPortalAccessTest` / a scoped-read test: register, experts, overview never return another brand's rows
- `PayoutControllerTest`: the new routes on the role matrix; `PATCH /api/payouts/{id}` refuses a row that already has an amount (409)
- `LocalPostgresIntegrationTest`: `V79` applies, backfills open and accepted offers, and an accepted offer with a payout takes the payout's amount

Frontend: `payoutRules.test.ts` (status derivation and labels), `navigation.test.ts` (the Payouts
group and its roles), and in `client-expert` a fee-display test beside `expertCase.test.ts`.

## 7. Not built, by choice

Adjustments of any kind (bonus, deduction, advance, correction), a running balance, a change after
acceptance, expert counter-offers, an approval step, a failed state, disputes. Any of these is a new
decision.
