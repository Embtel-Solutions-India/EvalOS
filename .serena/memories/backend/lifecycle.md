# backend/ — Case lifecycle, payment, SLA & refunds

Unit 04 built the state machine; Unit 05a added payment to it. `Stage`:
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
  → recompute SLA → save → **exactly one** `AuditService.recordEvent` → **exactly one** `CaseEvent`,
  inside the caller's transaction. Adding a transition means adding a table row + a method that calls
  `apply`, never a bespoke write path.
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

## Payment (05a) — the money rules

- A case is created **unpaid**. `paid` / `paid_at` on `evalos_case` (`V14`).
- `CaseLifecycleService.markPaid` is GM or Brand Manager, re-checked **in the service** as well as at
  the endpoint — a method-security annotation guards one route, the service guard guards the
  operation. Same reasoning as `RefundService.requireGm`. Any path that writes money does both.
- `markPaid` is **callable on an already-paid case**: the amount and invoice ref are correctable
  (a contact that arrived already paid carries only the *quote*), while `paid`/`paid_at` are
  write-once and the pool alert fires only on the first payment. One value, never a running total, so
  correcting cannot double-count.
- **The unpaid guard is a single `requireState(subject.isPaid(), ...)` in `markDocsComplete`.** That
  is deliberate and sufficient: no other transition advances a case past `DOC_COLLECTION`, so one
  check covers every later stage. Do not scatter copies. Doc collection on an unpaid case is
  *allowed* — it costs EvalOS nothing.
- **Revenue recognition is `paid && delivered && !refunded`**, read only through
  `RefundService.isRevenueRecognized`. Never sum on `delivery_date` alone. "Flagged refunded" is
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

## Authorization shape

The GM is a superuser on every transition: each gate is the spec's actor role **plus** GM, applied as
one `GM_OR` constant prefixed onto every `@PreAuthorize` rather than hand-maintained lists, so a new
route cannot forget it. `CaseControllerTest`'s route table asserts the GM gets through all of them —
add new routes there.

**Known runtime gap:** `PROJECT_COORDINATOR` is `Tier.SELF`, but no `evalos_case` column names a
coordinator, so their scoped read matches nothing and `docs-complete`, `draft/send-to-client`,
`deliver`, `close` will 403 live despite passing their role gate. Closing it needs an
`assigned_coordinator` column and a migration — **not** a widened predicate, which would fail open.
Tracked as an open question in `context/progress-tracker.md`.
