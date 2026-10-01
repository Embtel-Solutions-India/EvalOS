# Unit 73 — The expert is offered after the draft

**Decided 2026-10-02 by the business (D36 edited).** **Status: BUILT 2026-10-02** (branch
`feature/unit-73-expert-after-draft`; see `implementation-status.md`). Supersedes the in-place expert window that Unit 39b/PR #39
gave `PATCH /cases/{id}/expert` (any stage before signing).

## 0. Today, before this unit

- **PM Review → Drafting is one action that picks two people.** `assign-cm`
  (`CaseLifecycleService.assignCaseManager`) names the CM **and** the expert, prices the fee and
  opens the expert offer, then moves the case to `DRAFT_IN_PROGRESS`. Nothing is drafted yet when
  the expert is chosen.
- **The CM cannot choose an expert.** `GET /api/experts` and `GET /cases/{id}/expert-shortlist`
  exclude the role ("they draft for the expert the PM chose").
- **`EXPERT_ASSIGNED` does three things:** tells the CM "You are the case manager", sends the
  expert the offer mail (D67, `CaseMailListener`), and adds the expert to the case's Expert chat
  (`ChatLifecycleListener`). **`changeExpert` (PR #39) publishes no event**, so an expert offered
  that way gets no mail and no chat — a defect this unit fixes.
- An offer is answered only in `EXPERT_SIGNING` (`EXPERT_ACCEPTED` / `EXPERT_DECLINED` are declared
  there). An offer opened earlier waits; this unit does not change that.

## 1. Decisions

| # | Question | Answer |
|---|---|---|
| 1 | What does PM Review → Drafting take? | **The CM only.** `assign-cm` keeps its route and transition; its body is `cmId` alone. No expert, fee, rationale or offer note. |
| 2 | When may the expert be offered? | **Once a draft exists:** `draftVersionCount ≥ 1` and the stage is one of `DRAFT_IN_PROGRESS`, `DRAFT_REVIEW`, `READY_TO_SEND`, `CLIENT_REVIEW`, `CLIENT_APPROVAL`, no exception held. Through `PATCH /cases/{id}/expert` (`changeExpert`) — set or change, stage kept. |
| 3 | Who may offer? | **PM, CM, ENM, GM.** The CM joins (the business: "PM, CM can assign an expert and offer them an amount"). |
| 4 | The CM's amount | **Standard fee only** — Unit 65's `OfferFees` rule stands: a CM offers at the expert's standard fee and is told to ask a PM when there is none. PM / ENM / GM may set any amount. |
| 5 | What the CM sees | **The name list and the ranked shortlist**, as the PM does: `GET /api/experts` and `GET /cases/{id}/expert-shortlist` admit `CASE_MANAGER`. Payment details stay off both (unchanged). |
| 6 | When does the expert get the letter? | **Unchanged:** at `send-to-expert`, after the client approves (D36's client-first rule). `send-to-expert` is already refused with no expert on the case. **The client's approval no longer requires an expert** (it did: "no expert is on this case"), or a client approving before staff had offered one would be refused in the portal. |
| 7 | Events | `ASSIGN_CASE_MANAGER` publishes **`CASE_MANAGER_REASSIGNED`** (CM told, chat follows) instead of `EXPERT_ASSIGNED`. **`changeExpert` publishes `EXPERT_ASSIGNED`** (offer mail + Expert chat). The `EXPERT_ASSIGNED` notification to the CM reads "An expert has been offered %s." |
| 8 | The PM's queue | Expert assignment's "waiting for an expert" list becomes **cases with a draft and no expert** (in the §1.2 window) plus the rematch lane. `PM_REVIEW` cases are "waiting for a CM". The board card gains `hasExpert` (a boolean, never the id). |
| 9 | Cases already past PM Review with an expert | Untouched: their offer stands. A case at `PM_REVIEW` today simply gets its expert later. No migration. |

## 2. Backend

- `assignCaseManager(caseId, cmId)`: CM checks as today, transition, no offer.
  `AssignCmRequest` is `cmId` only.
- `changeExpert`: window per §1.2 (`draftVersionCount ≥ 1` plus the stage set), publishes
  `EXPERT_ASSIGNED` after the save. Refusals: "the expert is offered once the CM has uploaded a
  draft" (no draft yet), "from expert signing on, mark the expert declined or overdue, then
  reassign" (past the window).
- `CaseController.changeExpert` gate: `GM_OR` PM, CM, ENM.
- `ExpertPickerController.available`, `ExpertShortlistController`: add `CASE_MANAGER`.
- `CaseTransitions`: `ASSIGN_CASE_MANAGER(CASE_MANAGER_REASSIGNED, ASSIGNED)`.
- `CaseBoardController.BoardCard.hasExpert`.
- `NotificationListeners`: `EXPERT_ASSIGNED` → CM, "An expert has been offered %s."

## 3. Staff app

- `QUICK_ACTIONS`: `assign-cm` "Assign CM" with the CM picker only; `expert` action stages per
  §1.2, roles PM / CM / ENM; the Unit 12 `ShortlistPanel` moves from `assign-cm` to `expert`.
  `prefill` keeps the CM only.
- A **drop** from PM Review onto Drafting still runs `assign-cm` (now one field).
- Expert assignment page: "Waiting for an expert" = `hasExpert === false` in the window + rematch;
  PM Review cases leave it.

## 4. Tests

`CaseLifecycleServiceTest` (assign-cm opens no offer; changeExpert refused before a draft, allowed
after, publishes `EXPERT_ASSIGNED`; a CM's custom fee refused), `CaseTransitions` event mapping,
`NotificationListenersTest`, controller gates, `boardRules.test.ts` (actions per stage and role,
prefill), `queueRules` (waiting list).

## 5. Docs this unit changes

`current-decisions.md` (D36), `workflows.md` (case lifecycle CURRENT), `implementation-status.md`,
`ui-context.md` if the queue text names PM Review, and the matching Serena memories.
