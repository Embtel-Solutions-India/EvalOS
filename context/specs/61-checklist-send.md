# Unit 61 — The checklist is sent by the PC or the CM

**Decided 2026-09-29 by the business (D60, with Q15 and Q16 closed into it).** **Status: BUILT
2026-09-29**, without the email: the checklist email is D58's and shipped with the other three as
Unit 64c on 2026-09-30 (`64c-case-progress-emails.md`).

## 0. Today, before this unit

- An item shows in the client portal **the moment it is saved** (`PortalCaseService` read
  `findByCaseId` unfiltered). The intake template seeds a case's items at creation.
- Only the GM, the Brand Manager and the **PC** may write (`ChecklistController.COORDINATION`); the
  **CM** could only read.
- Nothing tells the client: `CHECKLIST_REQUESTED` fired at intake and nothing delivered it.

## 1. Decisions inside this unit

| # | Question | Answer |
|---|---|---|
| 1 | What is "sent"? | Per item: `document_checklist_item.sent_at` + `sent_by` (`V75`). Null = unsent = not in the portal. |
| 2 | What does Send do? | Stamps **every unsent item on the case** with now and the caller, writes one audit row on the case, publishes `CHECKLIST_REQUESTED` (the event D58's email will listen to). With nothing unsent it is refused: "already sent by … on …". |
| 3 | Items that exist today | **Backfilled as sent** (`V75`, `sent_by` null = "before D60"), so no client loses a list they can see. The two demo seed trees get the same backfill (`V912`, `V952`) because on a fresh database they run after `V75`. |
| 4 | Who | `COORDINATION` gains **`CASE_MANAGER`**: add, status (Q16), send, chase and the board. The CM's checklist access is the PC's. `docs-complete` is unchanged. |
| 5 | Intake | Seeds items **unsent** and **no longer publishes `CHECKLIST_REQUESTED`**: nothing was sent, so nothing may claim it was. |
| 6 | Expert evidence request (Q15) | Its item is **unsent like any other**; the coordinators are already alerted. |
| 7 | Where the PC/CM finds it | The checklist board lists a case in `DOC_COLLECTION` **or any case with unsent items**: an evidence request arrives after that stage, and a board that hid it would leave the item unsendable. |
| 8 | Portal upload | Refused against an unsent item, the same 403 as an item on another case. |

## 2. Routes

- `POST /api/cases/{id}/checklist/send` → the refreshed checklist view.
- The checklist view gains `unsent`, `lastSentAt`, `lastSentBy`; each item gains `sentAt`, `sentBy`
  (display names).

## 3. UI

`CaseChecklist`: an unsent item carries a **Not sent** tag; **Send N to client** publishes them;
with none unsent the button reads **Already sent by … on …** and is disabled. The nav entry
`/checklists` adds `CASE_MANAGER`.

## 4. Tests

`ChecklistServiceTest` (send stamps only unsent items, refused when none, event published),
`PortalCaseServiceTest` (unsent hidden, upload refused), `CaseIntakeServiceTest` (no
`CHECKLIST_REQUESTED` at intake), `ChecklistControllerTest` (CM may send).
