# Unit 71 — A note with every offer, and a note with every win

**Decided 2026-10-01 by the business (D69, D70).** **Status: BUILT 2026-10-01.**

## 0. Before this unit

- An expert offer carried a fee (Unit 65) and nothing else the expert could read. The PM's
  "Why this expert" is the team's own record and never reached the expert.
- A salesperson pressing **Won** in EvalOS closed the deal with no word to production. GHL's
  "won" has no field for one either, so the case arrived with nothing from Sales.

## 1. Decisions

| # | Question | Answer |
|---|---|---|
| 1 | Which offers need a note? (D69) | Every one: **Assign CM + expert** and **Reassign expert** refuse a blank `expertNote` (400, before the case moves). **Retake** asks for none: it is the same case to the same expert, so their last offer's note comes back with it. |
| 2 | Where does the expert read it? | With the offer, in `Answers` above Accept (New cases and the case page): `ExpertCaseView.offerNote`, `ExpertCaseSummary.offerNote`. Not in the offer email: the mail stays a sign-in nudge (D67). |
| 3 | Where is it stored? | `expert_case_offer.note` (`V82`), nullable only for offers made before it. The audit row keeps who and when, never the words (the opportunity-note rule). |
| 4 | Which wins need a note? (D70) | A win made in EvalOS (`PUT /api/sales/opportunities/{id}/status`, `won`): refused blank. Lost and abandoned take none. A deal won in GHL directly has none, and the case says so. |
| 4a | And a move to the Won stage? | **Winning too.** Every pipeline has a stage named "Won"; a move there (board drag, or the deal's stage picker) needs the note and closes the deal won in the same transaction (`SalesDeskService.moveToStage(id, stage, note)`: UPSERT for the stage + CLOSE for the status). Matched by name (`isWonStage`, backend and `boardMove.ts`); a renamed stage would slip through as a plain move. |
| 5 | Where is it stored? | An ordinary `opportunity_note` flagged **`handoff`** (`V82`), written in the same transaction as the close, so a refused close leaves no note. It syncs to the GHL contact like any note (Unit 54). |
| 6 | Where does production read it? | The case page's **Sales handoff note** card: the newest handoff note on the case's `ghl_opportunity_id` in its brand, with author and time. Behind `maySeeCaseContent` (it is about the client), so the ENM does not see it. |

## 2. Tests

`CaseLifecycleServiceTest#anOfferWithoutANoteIsRefused`, `#theOfferCarriesTheFeeThePmNamed` (note
stored), `#aRetakeKeepsTheDeclinedOffersFee` (note carried);
`SalesDeskServiceTest#aWinWritesTheHandoffNoteAndOnlyAWinDoes`, `#aMoveToTheWonStageWinsTheDealAndNeedsTheNote`,
`#anOrdinaryStageWritesNoNote`; `boardMove.test.ts` (`isWonStage`);
`OpportunityNoteServiceTest#aHandoffNoteIsFlaggedAndNeedsWords`;
`CaseControllerTest#caseContentIsWithheldFromTheSupplySideRole` (the note is gated) and
`#everyTransitionRouteAnswersItsDeclaredRoleAndNobodyElse` (the bodies carry the note).
