# Unit 67 — Act from the queues

**Specced and built 2026-10-02** (branch `feature/unit-70-live-screens`). The second of the three UI
units cut from Unit 66's gap audit (66 the case page, **67** the queues, 68 GM admin). Inventory
taken from the code on 2026-10-02, not from 66's notes, two of which Unit 73 had overtaken.

## 0. Today, before this unit

- **Queue rows mostly link out.** `InboxPage` offers Take / Change CM and otherwise "Assign on the
  case"; `MyDraftsPage` offers only Upload draft; `ExpertAssignmentPage` hand-picks one action per row
  (`actionFor`: reassign when rematching, timed-out when signing, else none — so a case waiting for
  its first expert offers nothing); `DeliveryQueuePage` hand-picks Deliver.
- **Everything the server admits per row already sits in one table**, `boardRules.QUICK_ACTIONS`,
  filtered by `actionsFor(card, role)` and run through `QuickActionDialog` + `performAction`. The case
  header uses it; the queues do not.
- **The CM has no Reassign expert button anywhere.** `reassign-expert` lists PM and ENM only, on the
  grounds that the picker's `GET /api/experts` refused the CM — but Unit 73 admitted the CM to that
  route (`ExpertPickerController:79`), and `CaseController` admits the CM to `reassign-expert`. The
  comment is stale; the gap is client-only.
- **The case page names no team.** `CaseSummary.assignedPm / assignedCm / assignedCoordinator` are
  ids; nothing returns the names (Unit 66 §2 "left for Unit 67").

## 1. Decisions

| # | Question | Answer |
|---|---|---|
| 1 | How does a row act? | **One `RowActions` control on every board-fed queue row**: a native `<select>` labelled *Act on {case code}* listing `actionsFor(card, role)`; choosing one opens `QuickActionDialog`; the write goes through `performAction`. No second rule table: whatever the case header offers this role on this case, the row offers. |
| 2 | Which queues? | `InboxPage` (PM, beside Take / Change CM), `MyDraftsPage` (CM — in the expanded row, since the row header is itself a button), `ExpertAssignmentPage` (PM — replaces `actionFor`, so a waiting case now offers *Assign expert*). `DeliveryQueuePage` keeps its own Deliver with its irreversibility warning, which the generic dialog does not carry. `ChecklistBoard` keeps its expanded checklist (send, chase, complete are there); `DraftQueuePage` keeps its inspector (approve / return need the draft in view). |
| 3 | The board card? | **Unchanged: no buttons.** `CaseCard`'s ruling stands (buttons were tried twice and cost the board its vertical room); the board acts by drag, and the queues are where a row acts. |
| 4 | Refresh after acting | Nothing per screen: the `api` interceptor re-reads every case-shaped query after the write (Unit 70a), so the row moves lanes on its own. A refusal is shown under the row, naming the action and the case. |
| 5 | CM reassign | `reassign-expert` gains `CASE_MANAGER`, matching the server. The CM still never sees a fee field (`actionsFor` strips it). |
| 6 | Team names | `CaseDetail.team {pm, cm, coordinator}` — display names, read in one brand-scoped query of the three ids. The case header shows *PM · CM · Coordinator* under the client line. Not on the board payload (a name per card is a read per board load for a line the card does not draw). |

## 2. Not built, by choice

- New queues for stages nobody has one for (PC's send-to-client / client review, PM's final QC):
  those cases are on the board and the case header; a queue is a business ask, not a gap.
- `close` from a queue: `DELIVERED` is not on the board payload.
- Bulk actions (several rows at once).

## 3. Tests

`rowActions` is `actionsFor` (already tested in `boardRules.test.ts`); add a case: the CM is offered
*Reassign expert* in the rematch lane. Backend: `CaseDetailServiceTest` — the team names come from the
case's own brand. `tsc -b`, `vitest`, `oxlint`.
