# 63 — The Expert Network Manager's workspace (2026-09-29)

**Decision (business, 2026-09-29).** The ENM runs the whole expert lifecycle in EvalOS: hiring,
the directory, case tracking and payouts. This reverses the two written refusals `00d` §7 names
(`22` slice 4, `17`'s *GHL* rows) and replaces `00d` Phase 5's EvalOS-owned `expert_application`
design: **the hiring pipeline is a GHL pipeline**, so it is synchronised by the mirror EvalOS
already runs (Units 44–46) rather than by a second sync.

Most of what the request lists already exists — roster, profile, credentials fields, fee,
availability, workload counts, quality score, payout ledger, weekly batch, payment history, ENM
dashboard. This unit is mostly assembly; the new pieces are listed per phase.

## Phase 1 — hiring pipeline (GHL-synchronised)

- **`PipelinePurpose.EXPERT_HIRING`** (`V76` widens the CHECK). A GM creates the pipeline in GHL
  with the stages *New Lead, Meeting Scheduled, Meeting Done, In Process, Onboarded, Dropped* and
  tags it on the existing pipelines screen. **EvalOS hard-codes no stage**: the mirror carries
  GHL's stages, so a stage renamed or added in GHL shows up on the next sweep.
- **The ENM's pipeline set is the live `EXPERT_HIRING` pipelines they are granted** (edited 2026-10-09 — it was every live
  `EXPERT_HIRING` pipeline of their brand, derived, with no grant). The Administrator ticks them on the Staff screen, over the
  same `team_member_pipeline` grant routes the desks use; `PipelineAssignmentService.grant` refuses a pipeline that is not
  `EXPERT_HIRING` or is of another brand. `V89` granted every existing ENM the hiring pipelines they held, so deploy
  changes nobody's access. A hiring pipeline nobody holds notifies nobody (`HiringPipelineNotifier` tells only its holders).
- **The ENM uses the desk machinery unchanged**: the board (`/api/opportunities/board`), stage moves
  (`PUT /api/sales/opportunities/{id}/stage` — mirror write + outbox push, D44), candidate create
  (`POST /api/marketing/leads` — the upsert on contact + pipeline, so one candidate is one card),
  the deal page's contact and notes. Each gate gains `EXPERT_NETWORK_MANAGER`; `PipelineScope`
  still proves every id is on the caller's own pipeline. `requireVisible`'s SUPPLY arm delegates to
  `requireMine`, as PIPELINE does.
- **A hiring opportunity never opens a case.** `CaseIntakeService` refuses a won opportunity whose
  mirrored pipeline is `EXPERT_HIRING` (invariant 8 — Handoff A is for clients). The GHL workflow
  should not fire for that pipeline anyway; this is the second lock.
- **Onboarded → the expert database.** On a hiring deal the ENM gets *Add to expert database*,
  which opens the existing create form pre-filled with the candidate's name, email and phone and
  `recruitmentSource = "Hiring pipeline"`. Nothing is created automatically: a roster row needs a
  fee and fields a stage move cannot supply.
- Staff nav: **Hiring pipeline** (`/hiring`) and **Add candidate** (`/hiring/new`), ENM only.
- Not built: booking a GHL meeting from the hiring deal (the stage move records it; Sales' booking
  form stays Sales'), follow-up tasks, an outreach log (`00d`: *the stage move is the record*).

## Phase 2 — directory: credential verification, case history, retake

- **Credentials verified** — `expert.credentials_verified_at` (`V76`); who checked is the actor on
  the `CREDENTIALS_VERIFIED` audit row. `POST /api/experts/{id}/credentials-verified` (ROSTER_WRITE). The profile shows
  *Verified on … by …* or a *Mark verified* button.
- **Case history** — `GET /api/experts/{id}/cases` (ROSTER_READ): every offer this expert got, newest
  first, with a work status derived from the offer and the case, never stored:

  | Status | When |
  |---|---|
  | Offered | offer `OFFERED` |
  | Accepted | offer `ACCEPTED`, letter not signed |
  | Submitted | signed; case in `FINAL_QC` / `READY_TO_DELIVER` |
  | Delivered | case `DELIVERED` / `CLOSED` |
  | Rejected | offer `DECLINED` / `TIMED_OUT` |
  | Reassigned | offer `SUPERSEDED` |

  **Rejected is read off the case, not the offer** (found in a local run-through, 2026-09-29): an
  expert who accepted and then declined keeps an `ACCEPTED` offer, so the latest row of a case
  that is waiting for a rematch naming this expert is Rejected whatever the offer says.
  Workload and quality stay the figures the profile already shows (`ExpertLoadService`, quality
  score, performance flags).
- **Retake (D62)** — an expert who declined or timed out on a case may be offered it again.
  **Eligible:** the case is still waiting for a rematch (`EXPERT_SIGNING`,
  `EXPERT_DECLINED_REMATCHING`), still names this expert, and the expert is `AVAILABLE`.
  **Permitted by** whoever may reassign (GM, PM, ENM, CM): `POST /api/cases/{id}/expert/retake`. It
  opens a fresh offer to the same expert and clears the exception — the reassignment path with the
  "that is the expert who declined" guard lifted, audited as the reassignment is, with a *retake*
  note. The history row shows
  *Offer again* when eligible.

## Phase 3 — payouts: three states, the expert confirms

The business set the states on 2026-09-29: **Pending → Processing → Paid, and the expert confirms
receipt, because every entry is manual.** The stored enum is unchanged; only the words and the
confirming party change.

| Stored | Shown | Means |
|---|---|---|
| `PENDING` | Pending | owed; opened at delivery with the expert's fee |
| `PAID` | Processing | the ENM recorded a transfer; waiting for the expert |
| `CONFIRMED` | Paid | **the expert** confirmed they received it |
| `VOIDED` | Voided | listed, never counted |

- **The expert confirms, the staff do not.** `POST /api/portal/expert/payments/{paymentId}/confirm`
  on the portal chain, scoped to the token's expert and brand, cascades to every draft the
  transfer settled (as `PayoutService.confirm` did), audited with actor `EXPERT`. The staff
  `POST /api/payments/{id}/confirm` and its button are **removed** — an ENM confirming their own
  manual entry is the thing the business is guarding against.
- **Expert portal Payouts** (spec 62) gains the transfer's id (never its method or reference —
  those are the brand's records — and never `payment_detail`) and a *Confirm received* button on
  each Processing transfer; the sidebar counts transfers waiting for them.
- **The ENM pays weekly** — the existing batch on `/payouts` is one week of due payouts, grouped by
  expert, one recorded transfer each.
- **Reports: weekly, monthly, yearly, with export** (edited 2026-09-29, the business) —
  `GET /api/payouts/summary?period=WEEK|MONTH|YEAR&from=&to=` (PAYOUTS): per period and currency,
  the count and total Pending / Processing / Paid. **Grouped by due date**, so a weekly report is
  the same week the batch paid it in. Two exports on the Summary panel: *Export totals* (the table,
  client-side) and *Export rows* — `GET /api/payouts/export?period=…` (PAYOUTS), a `text/csv`
  download of every payout row with its period, case, expert, amount, currency, state word and
  due date (voided rows included and named). Fields are quoted, and a leading `= + - @` is
  neutralised so a name cannot run as a spreadsheet formula.
- Not built: an approval step, a failed state, disputes — the business chose three states.

## Phase 4 — notifications

In-app, through the existing `notification` table (D37); three new `NotificationType`s:

| Type | To | When |
|---|---|---|
| `HIRING_PIPELINE_UPDATED` | the brand's ENMs | the mirror sees a hiring candidate arrive or change stage **from GHL** (a move made in EvalOS is the ENM's own and is not echoed) |
| `PAYOUT_DUE` | the brand's ENMs | a payout opens at delivery |
| `PAYOUT_CONFIRMED` | who recorded the transfer | the expert confirms it |

An expert declining in the portal (`EXCEPTION_RAISED`) and `EXPERT_SIGN_OVERDUE` also reach the
brand's ENMs — the decline may be a retake. The expert portal has no bell;
its Payouts badge is its pending action.

## Phase 5 — the ENM dashboard

`ExpertNetworkDashboard` gains a **Hiring pipeline** strip (cards per stage, from the board read)
and replaces the dead *Payments* card with Pending / Processing totals (from the batch read) and a
link to the Summary tab. No new endpoint.

## RBAC and audit, in one place

| Route | Roles |
|---|---|
| board, stage move, candidate create, deal contact/notes | + `EXPERT_NETWORK_MANAGER` (own brand's hiring pipelines only) |
| `POST /api/experts/{id}/credentials-verified` | ROSTER_WRITE (GM, BM, ENM) |
| `GET /api/experts/{id}/cases` | ROSTER_READ |
| `POST /api/cases/{id}/expert/retake` | GM, PM, ENM, CM (the reassign gate) |
| `GET /api/payouts/summary`, `GET /api/payouts/export` | PAYOUTS (GM, BM, ENM) |
| `POST /api/portal/expert/payments/{id}/confirm` | the expert, own payments only |

Every write above records an `audit_event`; the stage move and candidate create already do
through `SyncOutboxService` / `GhlHttp`.
