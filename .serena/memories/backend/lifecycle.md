# backend/ — Case lifecycle, payment, SLA & refunds

Unit 04 built the state machine; 05a added payment to it as a transition, and **Case Creation v2.0
(spec `05b`) moved payment out of it again** — `paid` is now set once, by intake, from a won GHL
opportunity. `Stage`:
`DOC_COLLECTION → EXPERT_ASSIGNMENT → DRAFT_GENERATION → EXPERT_SIGNING → FINAL_DELIVERY → CLOSED`
(EvalOS owns stages 3–7 of the 8-stage business pipeline; GHL owns 1–2 and 8).

## The transition table is the only authority

`service/CaseTransitions` declares `(from, action) → to` as a **whitelist**. Never branch on stage
inside a service method to decide legality — ask the table. Each `Action` carries its own
`CaseEvents.Type` and `AuditAction`, so a transition cannot be logged as one thing and published as
another.

- An **exception state is not a stage.** `ON_HOLD_AWAITING_CLIENT`, `EXPERT_DECLINED_REMATCHING`,
  `REFUND_REQUESTED` are stage-preserving: the case keeps its stage and accepts nothing but its way
  out. That is how "resume returns to the prior stage" works with no column remembering it — the case
  never left. **One exception state at a time.**
- Every transition funnels through one private `apply(...)`: set stage → restamp `stage_entered_at`
  → recompute SLA → save → **exactly one** audit row → **exactly one** `CaseEvent`, inside the
  caller's transaction. Adding a transition means adding a table row + a method that calls `apply`,
  never a bespoke write path.
- `apply` takes an optional `PortalAudience` (Unit 14) and that is the **only** thing it branches on:
  null → `AuditService.recordEvent` with the staff actor from `TenantContext`; non-null →
  `recordPortalEvent` with the brand off the case and the client named as the actor. Everything else
  is shared, because a client approving a draft is the same transition however it was triggered.
- **The two client actions have a second entry point taking an already-authorized `Case`**:
  `clientApproveDraftFromPortal` / `clientRequestRevisionsFromPortal`, called by
  `PortalCaseService`. They take the row, not an id, because the token *is* the authorization and
  there is no `TenantContext` to scope an id with. Both share the id-taking version's guards, so a
  client approving twice gets the same 409 from the same line — the state machine is never duplicated
  for the portal surface.
- `submitDraft(caseId, draftLink)` writes `evalos_case.draft_link` (Unit 14) — the one link the client
  portal shows. Optional and only overwritten when non-blank: a second version filed in the same place
  needs no new link, and blanking one by omission would take the draft away from a client mid-review.
  **Never falls back to `drive_link`** — see `mem:backend/persistence`.
- `stage_entered_at` means "when the current wait began" and is restamped by **every** transition,
  not only stage-changing ones — otherwise a second PM-review round inherits the first round's spent
  clock. There is no per-sub-loop timestamp column.
- Reads go through `findScoped`, so another brand's (or another CM's) case is simply absent. An
  out-of-scope case answers **403, not 404** — whether an id exists is itself another brand's
  information.
- `assignPm` stamps `team_id` from the PM's row. Nothing else populates it, so PM/Coordinator team
  scoping matches nothing until then. Pool → PM is when a case acquires a team.
- **Expert transitions also write an `expert_case_offer` row, inside the same transaction** (Unit 12),
  so an offer and the transition that caused it commit together or not at all: `assignCaseManager` and
  `reassignExpert` open one, `expertDeclined` stamps `DECLINED` with the reason, `expertSigned` stamps
  `ACCEPTED`, and a rematch closes the previous row `SUPERSEDED` so no permanently-open row survives.
  Invariants of that table — including why its partial index being non-unique matters — are in
  `mem:backend/persistence`.

## Payment (Case Creation v2.0) — the money rules

- A case is created **paid**. `paid` / `paid_at` on `evalos_case` (`V14`), written by
  `CaseIntakeService.newCase()` from the won GHL opportunity, along with `deal_value` and
  `ghl_opportunity_id` (`V24`).
- **There is no `markPaid`, no `MARK_PAID` transition and no `mark-paid` endpoint.** They existed in
  05a and were deleted in v2.0: GHL invoices and collects before an opportunity is marked Won, so the
  webhook is the payment record and a second way to set `paid` is a second thing that can disagree with
  GHL. Nothing outside intake ever *writes* the flag. Anything describing a GM or Brand Manager
  recording payment by hand is pre-v2.0 and wrong — see `mem:backend/webhooks`.
- **The unpaid guard is still a single `requireState(subject.isPaid(), ...)` in `markDocsComplete`.**
  Every case now arrives paid, so it is normally satisfied on arrival — keep it anyway: it is one line,
  it is the one place no other transition can bypass (nothing advances past `DOC_COLLECTION` without
  it), and a refund has to be able to make a case not-earned. Do not scatter copies of it.
  **`isPaid()` is read in four places outside the state machine**, and only one of them is a second
  *gate*: `RedactedProfileService.full` (Unit 13) refuses the expert's identity on an unpaid case.
  That is not a scattered copy of the transition guard — it gates a *release of information* rather
  than a stage advance, so `markDocsComplete` could not cover it — and it throws the same
  `IllegalTransitionException`/409 so both read identically to a caller. The other three are not
  gates at all: `RefundService.isRevenueRecognized` (the revenue pair, next bullet) and the
  `CaseController`/`ChecklistController` DTO projections, which merely report the flag to a screen.
- v2.0 also closed the unpaid window on purpose: since no case exists before the deal is won, EvalOS no
  longer collects documents ahead of payment. 05a allowed that on the grounds it "costs EvalOS
  nothing"; leads are now GHL's business.
- **Revenue recognition is `paid && delivered && !refunded`**, read only through
  `RefundService.isRevenueRecognized`. Keep all three terms even though `paid` is now true from birth:
  the term that does the work is **`!refunded`**, and delivery alone must never imply earned. Never sum
  on `delivery_date` alone.
  **A refund does not clear `paid`** — `RefundService` never writes that flag. "Refunded" is `CLOSED`
  **+** `exception_state = REFUND_REQUESTED` (there is no refunded column), read via `isRefunded`. So
  do not go looking for a writer that un-pays a case, and do not add one: since v2.0 `paid` is
  intake-write-only, and reversal lives in the third term. "Flagged refunded" is
  `CLOSED` + `exception_state = REFUND_REQUESTED` (there is no refunded column); a merely *requested*
  refund is not a reversal, which is why `isRefunded` checks the pair.
- Refunds are **GM-only** on both rulings. Approval voids every `PENDING` payout.

## SLA

`service/SlaCalculator` over `service/BusinessCalendar` (09:00–17:00 America/Los_Angeles, weekends,
11 US federal holidays incl. the Sat→Fri / Sun→Mon observance shift and the New-Year shift back into
December). Per-stage business-hour budgets; `AT_RISK` at 75% spent; **null when no clock runs**
(closed, or in an exception state).

`sla_status` is **recomputed on read**, not trusted from the column — a case left sitting past its
budget is overdue though nothing wrote to it. The board's SLA filter matches the recomputed value, so
filtering in SQL on `sla_status` alone is a bug.

**`SlaCalculator` is the only home for these numbers, and the business has now confirmed them** — every
budget in the CRM build spec matched the constants already in that class exactly, so nothing changed.
The numbers are deliberately **not repeated here**: `context/process-automation.md` mirrors them once,
marked as a mirror, for the business to read. If that table and this class disagree, **the class is
right**. Do not add a third copy.

Do not add a `BREACHED` value to `SlaStatus` either — Unit 19's spec once implied one; the statuses are
`ON_TRACK`, `AT_RISK`, `OVERDUE`.

**`EXPERT_SIGNED` is fired by the expert's own upload, not by a provider callback.** There is no
e-signature provider (decision, Production Process v2.0): the expert downloads the letter from their
portal, signs it however they already do, and uploads the signed PDF, which files it into the case's
Drive folder and fires the transition. The offer's first-write-wins rule matters here — pressing Accept
and then uploading are two acts that both mean accepted, on the happy path.

Because nothing issues a certificate, the transition must also record the provenance: hash of the letter
as sent, hash of the file received, the attestation text and name, and `actor_type = 'EXPERT'`. **PM
final QC is therefore load-bearing**, not a formality — it is the only check that the uploaded file is
the right letter, actually signed.

**Timers live in `job` (Unit 19) and only ever prompt.** A sweep publishes an event or raises a
notification; it never calls a transition and never writes `current_stage`. The one that would matter
most is the 24h expert timeout: the clock asks a human to fire `EXPERT_TIMED_OUT`, and no sweep may
fire it.

## Authorization shape

The GM is a superuser on every transition: each gate is the spec's actor role **plus** GM, applied as
one `GM_OR` constant prefixed onto every `@PreAuthorize` rather than hand-maintained lists, so a new
route cannot forget it. `CaseControllerTest`'s route table asserts the GM gets through all of them —
add new routes there.

**The Coordinator gap is closed, and how it was closed is the precedent.** `PROJECT_COORDINATOR` is
`Tier.SELF`, so until a case named one, their scoped read matched nothing and `docs-complete`,
`draft/send-to-client`, `deliver` and `close` 403'd on their own work despite passing the role gate.
The fix was `V17`'s `evalos_case.assigned_coordinator` + `assignCoordinator` + the column in
`CaseRepository.SCOPE`'s assignee set — **not** a widened predicate, which would have failed open.
`assignCoordinator` is re-assignable, unlike `assignPm` (no pool exists for coordination; the audit
trail carries each hand-over, so the column only holds who has it now). A `SELF`-tier role added later
owes the same three pieces, in that order.
