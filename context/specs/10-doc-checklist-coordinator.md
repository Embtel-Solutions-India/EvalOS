# Unit 10 — Document checklist board + Coordinator flow

**Phase:** 1 — Structure the data (the spine) — final unit
**Depends on:** 04, 09
**Unlocks:** completes the intake→production handoff; feeds Unit 19 (reminder
timers) and Unit 18 (client chase via GHL)
**Gating open questions:** none (reminder *scheduling* lands in Unit 19; Unit 10
provides the hooks and the manual chase)

## Goal

The Project Coordinator's surface for stage 3 (Document Collection): track
required vs. uploaded documents against the Drive folder, chase the client
through GHL, and push a complete case to the PM. This is the last Phase-1 unit —
it closes the loop from Handoff-A intake to production.

**Verifiable result:** a Coordinator sees their brand's cases in Document
Collection with per-item status and completeness/aging; can set item status, add
a required item, and send a chase (which emits a GHL event, not an EvalOS email);
and can mark docs complete only when the checklist is satisfied — which moves the
case to the PM (Unit 04's `markDocsComplete`) and publishes `documents.completed`.

## In scope

- Checklist read/update endpoints and the Coordinator checklist board.
- The manual "chase client" action → emits a client-notification event for GHL.
- The mark-docs-complete flow (delegates to Unit 04, guarded by completeness).
- The doc-collection SLA/aging **hooks** the Unit 19 timers will drive.

## Out of scope

- Client-side document *upload UI* — **now owned by Unit 21**
  (`21-client-document-upload.md`), which puts an upload control on the client
  portal and streams the file into the case's Drive folder. This line used to read
  "documents are collected in Google Drive, handled separately"; that is still where
  the files land, but the client now gets them there through EvalOS rather than
  outside it. **Unit 21 feeds this unit** — an upload sets the item to `UPLOADED`
  and the Coordinator reviews it here, flagging `MISSING` or `INCORRECT`.
- Scheduling the 24h/48h reminders and the day-3 escalation — Unit 19 (this unit
  defines the events they fire).
- **AI upload review — ruled out, not deferred.** The Coordinator does the human
  review. This line previously said "deferred (Phase 2+)", which left an open
  intention nobody owned; the decision (Production Process v2.0) is that it is not
  being built, and Unit 20 records the same exclusion.

## `markDocsComplete` and A12, for the record

Two clarifications this unit is the natural home for:

- `ChecklistItemStatus.isComplete()` counts `UPLOADED` as complete, so a client
  upload alone can satisfy the docs-complete gate. That is correct and intended: the
  real gate is the **Coordinator choosing** to mark the case complete, and a bad
  document gets `INCORRECT`, which makes it incomplete again. The enum stays the one
  definition — do not add a second predicate for "reviewed".
- A12's "PM comments visible inline on the draft" is **Google Drive's own commenting**
  on the draft document. EvalOS records the PM's return reason and builds no
  annotation subsystem; the draft already lives somewhere that does this natively.

## Backend
| Method | Path | Auth | Notes |
| --- | --- | --- | --- |
| GET | /api/checklists/board | Coordinator (brand-scoped) | cases in DOC_COLLECTION with completeness % + aging since `stage_entered_at` |
| GET | /api/cases/{id}/checklist | scoped | the case's checklist items |
| PATCH | /api/cases/{id}/checklist/{itemId} | Coordinator | set status `REQUIRED/UPLOADED/APPROVED/MISSING/INCORRECT` (audited) |
| POST | /api/cases/{id}/checklist/items | Coordinator | add an ad-hoc required item |
| POST | /api/cases/{id}/chase | Coordinator | emit `checklist.reminder` (→ GHL sends the client the chase); logged |
| POST | /api/cases/{id}/docs-complete | Coordinator, PM | **Unit 04** transition; guard: no item in `MISSING/INCORRECT`, all `REQUIRED` resolved |

Checklist items are seeded at intake from the service-type template (Unit 05);
here they are maintained. `docs-complete` is the existing Unit 04 method — Unit
10 only surfaces it and enforces the completeness guard in the UI + service.

### SLA / reminder hooks (consumed by Unit 19)
Define the event types the Coordinator flow and timers use:
- `checklist.reminder` — manual (this unit) or scheduled 24h/48h (Unit 19) →
  GHL sends the client a chase.
- `docs.escalation.day3` — fired by Unit 19 when a case is still incomplete at 3
  business days → in-app notification to the PM + Brand Manager (Unit 06) and
  flagged on the board.
Unit 10 wires the manual `checklist.reminder`; Unit 19 schedules the timed ones
against the Pacific business calendar.

## Frontend deliverables
1. **Checklist board** (`features/checklist`): the Coordinator's DOC_COLLECTION
   cases, each row/card showing client, deadline RAG, completeness (e.g. 3/5),
   and **aging** (hours since entering the stage; amber >24h, red >48h). Sort by
   urgency.
2. **Per-case checklist**: the item list with a status control per item, an
   **Add required item** control, and a link to the Drive folder.
3. **Chase action**: "Send document chase" → `POST .../chase`; shows last-chased
   time. Copy is sent by GHL; EvalOS sends no email.
4. **Mark docs complete**: enabled only when the guard is satisfied; on click →
   `docs-complete` → the case leaves the board (moves to the PM) and the PM is
   notified (Unit 06).
5. **Pending-docs queue**: cases with incomplete docs and no client response in
   24h/48h surfaced at the top with the chase prompt.

## Acceptance criteria
- [ ] The board shows only the cases in DOC_COLLECTION that the caller's scope
      admits, with correct completeness and 24h/48h aging indicators. For a
      Project Coordinator that is **the cases assigned to them**, not their
      brand's: the role is `Tier.SELF` (`architecture.md`, access tiers), so the
      scoped read matches on `assigned_coordinator`. The two oversight roles on
      this screen (GM, Brand Manager) see the brand or all brands. An intake case
      with no coordinator assigned therefore appears on no Coordinator's board —
      it is visible to oversight, and staffed from the production board's
      `assign-coordinator` action.
- [ ] Setting an item status persists and writes an audit entry; adding a
      required item makes the case "incomplete" until resolved.
- [ ] **Send chase** emits `checklist.reminder` (assert via test subscriber) for
      GHL to deliver; no EvalOS email is sent; last-chased time updates.
- [ ] **Mark docs complete** is blocked while any item is MISSING/INCORRECT or a
      REQUIRED item is unresolved; when satisfied it calls `docs-complete`, moves
      the case to EXPERT_ASSIGNMENT, publishes `documents.completed`, and notifies
      the PM.
- [ ] A Coordinator cannot see or act on another brand's checklist.
- [ ] `npm run build` green; `./mvnw verify` green.

## Invariants honored
Brand isolation (1); docs-complete goes through the Unit 04 guarded transition
with audit + event (3, 13); client chase is a GHL event, EvalOS sends no mail
(14); no document files hosted — Drive link + item status only (14);
event-driven, no manual pipeline hacks (principle 4).

## Files touched (created)
Backend: `.../web/ChecklistController.java` (+ DTOs),
`.../service/ChecklistService.java`, extend `CaseEvents` with
`checklist.reminder` / `docs.escalation.day3`.
Frontend: `frontend/src/features/checklist/*` (`ChecklistBoard`, `CaseChecklist`,
`ChaseButton`, `checklistApi`). Uses the `document_checklist_item` table from
Unit 03; no new migration.
