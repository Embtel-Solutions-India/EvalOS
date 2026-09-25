# Unit 55 — The client questionnaire is removed

**Decided 2026-09-25 by the business.** The client portal no longer asks a questionnaire, and the
questionnaire no longer exists anywhere in EvalOS: not in the portal, not in the API, not in the
database, not on the deal screen. Supersedes the questionnaire parts of Unit 43
(`43-client-intake-funnel.md` step 2 and the autosave) and the "answers" half of D13 and D34.
**Status: BUILT 2026-09-25, except the column drop (`V69`), which waits on an explicit go-ahead** — see §3.

## 1. What stays

The **request** is not the questionnaire and stays exactly as it is:

- choose a service (and a purpose when the service does not imply one) → `POST /api/portal/applications`
  opens the `DRAFT` row;
- attach documents (Unit 53, D33) against that draft;
- submit → `POST /api/portal/applications/{id}/submit` opens the GHL opportunity on the `INTAKE`
  pipeline (D10, D10b, D10c).

`client_application` keeps `service_id`, `service_name`, `purpose`, `status`, the opportunity link
and the timestamps. `DRAFT` stays: the documents need a row to attach to before submit, and the
one-draft-per-client index still stops a second request racing the first.

## 2. What goes

| Layer | Removed |
|---|---|
| Portal | the "Your request" step (question groups, `QuestionField`, the conditional engine `lib/questionnaire.ts`, `constants/questionGroups.ts`, `constants/countries.ts` — used only by the groups), the answers read-back on review, `saveApplication` / `parseAnswers` / `AnsweredQuestion`, the question types in `types/intake.ts` and `questionGroupIds` on every catalog entry. The funnel is two steps: **Service → Review** (review carries the documents and the send button). |
| API | `PUT /api/portal/applications/{id}` (the autosave — it existed only to carry answers; purpose already travels on the start) and its `SaveRequest`; `answers` on `ApplicationView`. |
| Service | `ClientApplicationService.save`, `MAX_ANSWERS_CHARS`. |
| Entity | `ClientApplication.answers`, `saveAnswers`, `getAnswers`. |
| Database | `client_application.answers`, to be dropped by **`V69`** (pending, §3). Unmapped from the entity already; its `'[]'` default fills new rows meanwhile. |
| Staff app | the answers table on the deal screen. The panel stays as **"Portal request"**: service, purpose, submitted date, status. |

## 3. Data

**`V69` drops the column and the answers with it.** Answers already given are not archived: the
business asked for the questionnaire's *existence* to go, and a kept copy is a questionnaire that
still exists. Hard to reverse once deployed — restore from a backup is the only way back.

**Not yet written (2026-09-25).** Writing the migration was held for an explicit go-ahead because it
destroys client data. Nothing depends on it: no code reads or writes the column, and its default
fills new rows. The file, when approved, is one statement —
`ALTER TABLE client_application DROP COLUMN answers;` — as
`db/migration/V69__drop_client_application_answers.sql`.

## 4. Consequences

- **Sales prices from the call, not the form.** The deal screen shows what was asked for and the
  documents; everything else is asked on the phone. This was already the posture for a gap in the
  answers (43 §5, "we'll ask about anything missing when we call").
- **`open-decisions.md` g2** (does the expert see the answers?) is moot and leaves.
- The GHL `SUBMITTED` marker on the opportunity (D10) now means "the request was sent", which is
  what it recorded all along.

## 5. Checks

- `ClientApplicationServiceTest` loses the save cases. `ClientApplicationRoutesTest` asserts the
  portal chain's preflight now **refuses PUT** (with PATCH): the autosave was the only PUT under
  `/api/portal/**`, so `PortalSecurityConfig` dropped it from the enumerated CORS methods.
- `MigrationTreeTest` passes; it must still pass once `V69` joins the main tree.
- Portal and staff builds type-check with no `answers` field anywhere.
