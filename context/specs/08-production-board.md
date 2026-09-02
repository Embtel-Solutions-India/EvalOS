# Unit 08 — Production Kanban board

> **⚠ AMENDED by Unit 31 — Production lifecycle v2 (SPECCED 2026-09-02, not built).**
> The pipeline becomes **twelve explicit stages, each with one owner, one primary action and
> one next owner**, drawn as **eight board columns**. Three facts this codebase currently carries as *sub-statuses on a stage*
> — PM approval, client approval, and the QC/delivered split — become **stages**, and the
> draft becomes a **versioned file** rather than a link. Two transitions are added
> (`qc-fail`, `send-to-expert`) and several gates move, notably the Case Manager taking
> ownership of expert signing and reassignment.
> **Read `context/specs/31-production-lifecycle-v2.md` before changing anything below.**

**Phase:** 1 — Structure the data (the spine)
**Depends on:** 04, 07
**Unlocks:** 09 (cards open the case detail), and the daily operating view for
PM / Brand Manager / GM
**Gating open questions:** none

## Goal

The production board: cases as cards in stage columns, RAG deadline badges,
brand- and role-scoped, with a pool/unassigned queue whose Assign PM calls the
Unit 04 transitions. It is the day-to-day operating surface for the GM, Brand
Manager, and PM (and a read view of the wider pipeline for the Coordinator).

**Verifiable result:** each role sees the correct, brand-scoped board; cards sit
in the right stage column with a correct RAG deadline badge; the pool shows
unassigned cases; Assign PM performs a legal transition (and an illegal one is
refused with the 409 surfaced), and the card moves.

## In scope

- The board view (5 EvalOS stage columns + exception lanes), wired to the case
  API.
- RAG deadline/SLA badges from `sla_status` + `deadline`.
- Brand + role filtering, and the pool/unassigned queue.
- Quick actions mapped to legal next transitions (on the case, and the pool's
  Assign PM on the board — see item 4; not on the card).
- A board query endpoint (or a documented reuse of `GET /api/cases`).

## Out of scope

- Case detail (Unit 09) — cards deep-link into it.
- Doc checklist management (Unit 10).
- Real dashboard KPIs (Unit 17).
- Free drag-across-any-column: moves are constrained to legal transitions.

## Backend
Reuse `GET /api/cases` (Unit 04, scoped, filterable by stage / deadline / sla /
pool_status). Add a convenience grouping endpoint:
| Method | Path | Auth | Returns |
| --- | --- | --- | --- |
| GET | /api/cases/board | staff (scoped) | cases grouped by `current_stage` + exception lane, each card projected to board fields |

Card projection: `{ id, case_code, client_name, service_type, deadline,
sla_status, current_stage, exception_state, pool_status, assigned_pm,
assigned_cm, expert_sign_status }`. `deal_value` is included **only** for
PM/BM/GM (DTO projection); never for a Case Manager view.

Scope rules (server-enforced): GM = all brands (optionally filtered by the
switcher's `brandId`); Brand Manager = own brand; PM = own team; Case Manager =
own docket; Coordinator = own brand's cases (read/status view).

## Frontend deliverables
1. **Board layout** (`features/board`): horizontal columns — Doc Collection ·
   Expert Assignment · Draft / Report · Expert Signing · Final Delivery — plus
   collapsible exception lanes (On Hold · Rematching · Refund Requested). Uses
   `ui-context.md` tokens; columns are `rounded-lg` cards.
2. **Cards**: client, service type, deadline with a **RAG badge**
   (`--status-red/amber/green` by `sla_status`), owner (PM/CM), and an
   expert-sign chip when in signing. Overdue cards tinted with the `*-bg` token.
   Case code in `--font-mono`; dates/counts in `--font-num`.
3. **Pool / unassigned queue**: a pinned lane or filter showing
   `pool_status = IN_POOL` (visible to GM/BM/PM) with an **Assign PM** / **Assign
   CM** quick action.
4. **Quick actions** = the *legal* next transition(s) for a case's stage/role
   (from Unit 04). Actions needing input (assign PM/CM + expert, QC, deliver)
   open a small dialog; the action posts to the matching endpoint. A rejected
   transition (409) shows a reason and nothing moves.

   **Superseded 2026-08-28: they are not on the card.** A board card is read-only
   — six pieces of data and a link to the case. The transitions live on the case
   itself (`StageActions`, off the same `boardRules.actionsFor` table) and in the
   draft and delivery queues, which is where somebody working a batch of them
   already is. On the card they were tried twice, in flow and then as a hover
   overlay, and both spent the board's two scarcest resources — vertical room and
   a layout that holds still under the pointer — on controls one click away. The
   board's remaining action is the pool's **Assign PM**, which is the one thing
   this screen asks you to decide; its refusal renders above the pool lane.
5. **Filters/controls**: brand switcher (GM) already in the shell; board-local
   filters for owner, service type, and "at risk / overdue only". The shell date
   filter narrows by deadline window.
6. **Empty/loading states** per column; optimistic move with rollback on error.

## The business's eight columns (Production Process v2.0)

The CRM build spec names eight board columns: Doc Collection · Expert Assigned ·
Draft In Progress · Draft Review · Client Review · Expert Signing · QC · Ready to
Deliver. **That is a view, not a state machine.** All eight derive from data the
board already loads, and the `Stage` enum stays six values:

| Business column | Derived from |
|---|---|
| Doc Collection | `DOC_COLLECTION` |
| Expert Assigned | `EXPERT_ASSIGNMENT` |
| Draft In Progress | `DRAFT_GENERATION`, no approval pending |
| Draft Review | `DRAFT_GENERATION` + `pm_approval_status = PENDING` |
| Client Review | `DRAFT_GENERATION` + `client_approval_status = PENDING` |
| Expert Signing | `EXPERT_SIGNING`, signature not yet returned |
| QC | `EXPERT_SIGNING`, signature returned, awaiting `qc-approve` |
| Ready to Deliver | `FINAL_DELIVERY` |

`CaseCard.draftChip()` already computes the three draft sub-states for the chips, so
the expanded view is a **grouping of existing values, not new logic** — and
splitting `DRAFT_GENERATION` into three real stages would break the sub-loop
design, where a returned draft goes round again inside one stage with
`stage_entered_at` restamped per round.

**Adding a `Stage` value for any of these is the wrong fix.** It would multiply the
transition table, invalidate the SLA budgets (which are keyed per stage and
sub-state), and turn every "which stage is this" query into a set membership test.

The QC split is the only one needing anything new, and it needs it from Unit 15: a
returned signature is what distinguishes Expert Signing from QC, and today nothing
records the signature coming back. Until Unit 15, render those two as one column.

## Delivery queue — reinstated

`/delivery` is a real screen again: cases in `FINAL_DELIVERY`, oldest first, with a
one-click **Deliver** per row, for the Coordinator (and GM/Brand Manager).

**This reverses a deletion, and the reversal is deliberate.** The entry was removed
in Unit 10 because it promised "final delivery queue (Unit 13)" and no unit built a
screen behind it — an empty nav item, not a rejected idea. `navigation.test.ts`
currently **asserts the entry is absent**, so that assertion changes with this work;
a failure there is the expected consequence, not a regression. The business asked for
the queue twice (A20 "case moved to delivery queue", and the Coordinator's
"one-click delivery"), which is a stronger signal than the reason it was cut.

The `deliver` and `close` transitions are unchanged — this is a second surface onto
the actions the Final Delivery column already offers, aimed at a Coordinator working
through a batch rather than hunting cards on a board.

## Acceptance criteria
- [ ] Each role's board is correctly brand/team/docket scoped; no card from
      another brand ever appears.
- [ ] Cards land in the column matching `current_stage`; exception cases appear
      in the right lane.
- [ ] The RAG badge matches `sla_status`; overdue cards are visually distinct.
- [ ] The pool shows only `IN_POOL` cases; **Assign PM** moves a card out of the
      pool and re-renders it under the owner.
- [ ] A legal quick action transitions the case and re-renders the board; an
      illegal action is refused (409) with a visible reason and no move.
- [ ] A card carries no controls: clicking anywhere on it opens the case.
- [ ] A Case Manager never sees `deal_value` on any card.
- [ ] `npm run build` green (no TS/console errors); `./mvnw verify` green (board
      endpoint).

## Invariants honored
Brand/team/docket isolation on every card (1); `deal_value` projection by role
(3); all moves go through Unit 04's guarded transitions with audit + events (3,
13); UI scoping is convenience, server enforces (principle 7).

## Files touched (created)
Backend: `.../web/CaseBoardController.java` (+ board DTO),
`.../service/CaseBoardService.java`.
Frontend: `frontend/src/features/board/*` (`BoardView`, `StageColumn`,
`CaseCard`, `PoolLane`, `QuickActionDialog`, `boardApi`).
