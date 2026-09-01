# Unit 32 — PM notes panel and draft status board

> **Status: BUILT 2026-09-02.** All three open questions taken on their defaults: the Brand
> Manager reads the rationale, the comment is optional on approve and required on return, and it
> never reaches the redacted profile.
>
> **Originally: SPECCED (2026-09-02), not built.** Small: both surfaces exist in part, and neither
> needs a new subsystem. This is a refinement spec, not a pivot.
>
> **It closes A12** — "comments visible inline on the draft" — in the only sense EvalOS can, and
> §4 is explicit about the sense it cannot.

**Depends on:** 09 (case detail), 22 (role operations UI), 23 (case notes), 31 (`case_document`)
**Amends:** 09, 22, and `process-automation.md`'s A12 row

---

## 1. What already exists

Worth stating first, because most of this unit is *reaching* rather than *building*.

| Asked for | Today |
|---|---|
| Strategy notes written by the PM per case | `evalos_case.pm_strategy_notes` (V5), `StrategyNotes.tsx`, `updateStrategyNotes` gated `GM · PM`, read by `GM · PM · CM` |
| Drafts submitted to PM | `/drafts` (`DraftQueuePage`), `DraftReviewService` |
| Approved or returned | `PM_APPROVE_DRAFT` / `PM_RETURN_DRAFT`, and `pm_approval_status` records which |
| Revision history | **`case_document`** (Unit 31): one row per version, with `status`, `uploaded_by`, `uploaded_at` |
| The return comment | **In the audit trail only**, as the transition's reason |

**So there are exactly two gaps**, and they are §2 and §3.

---

## 2. The PM notes panel — two fields, not one and not three

The requirement names three things: **angle, key points, expert selection rationale.**

**Angle and key points stay one field.** They are one act of writing — a PM setting out how to
argue a case does not naturally stop between them, and two boxes for one paragraph is a form that
gets one box filled. `pm_strategy_notes` already holds this and needs no change but a better
prompt: the panel's placeholder names what belongs there, so the structure is guidance rather than
schema.

**Expert selection rationale becomes its own column**, and this is the one part of this unit that
is a real design decision rather than a re-skin. Three reasons, in order of weight:

1. **It has a different lifetime.** Case strategy is written once and refined; the expert rationale
   is written *per expert*, and Unit 31 made reassignment a normal path — a case can go through two
   or three experts. Folded into one field, a reassignment either overwrites the strategy or the
   new expert's rationale is never recorded.
2. **It has a different audience.** Strategy is for the Case Manager writing the draft. The
   rationale is oversight: it is the answer to *why this expert*, which the ENM and a GM asking
   about a disputed case need and the CM does not.
3. **It is evidence.** "Why was this expert chosen" is the question that gets asked after something
   goes wrong, and an answer buried in a paragraph about case strategy is an answer nobody finds.

```sql
ALTER TABLE evalos_case ADD COLUMN expert_selection_rationale text;
```

**Written where the expert is chosen, not in a separate ceremony.** The rationale field belongs on
the `assign-cm` dialog (which picks the CM *and* the expert) and on `reassign-expert` — optional in
both, because a PM who has not formed a reason yet must not be blocked from staffing a case, and a
required field is how "n/a" becomes the most common value in a column.

**Not versioned, and that is deliberate.** The current rationale is what matters; the history of
who was assigned when is already in the audit trail and in `expert_case_offer`. A version table for
one text field would be a second history beside two that exist.

### Visibility

Same three roles as strategy notes plus the **ENM**, who is the supply-side owner and the one most
likely to be asked why an expert was picked. Not the client, not the expert — an expert reading the
case for why they were chosen over somebody else is a conversation nobody wants to have.

`SEES_STRATEGY_NOTES` and its edit counterpart already exist in `CaseController`. **The rationale
needs its own pair rather than reusing them**, because the ENM reads one and not the other, and
folding it in would widen strategy notes to the ENM by accident.

---

## 3. The draft status board — a read, not a store

`/drafts` today lists drafts **awaiting review**. The requirement asks for the drafts *and* what
became of them, with the comments and the revision history.

**No new table.** Unit 31's `case_document` already holds one row per version with a `status`
(`SUBMITTED · RETURNED · PM_APPROVED · CLIENT_APPROVED · SIGNED · SUPERSEDED`), who uploaded it and
when. What it lacks is the comment.

```sql
ALTER TABLE case_document ADD COLUMN review_comment text;
```

**Stamped on the version, not looked up later.** When the PM returns V2, the transition sets that
row's `status = 'RETURNED'` and writes the reason into `review_comment` in the same transaction.

**Why not join the audit trail instead, which already has the reason?** Because the join key would
be time — "the audit row nearest this version's timestamp" — and time is not an identity. Two
rapid rounds would attach the wrong comment to the wrong version, and it would fail silently and
look plausible. **The audit row stays**; this is a projection of it onto the artefact it is about,
the same relationship `expert_case_offer` has to the trail.

### The screen

One row per version, newest first, within a case:

```
V3  ·  submitted by A. Rao  ·  2 Sep, 10:05  ·  PM APPROVED
V2  ·  submitted by A. Rao  ·  1 Sep, 16:40  ·  RETURNED
        "Section 2 needs the institution's accreditation; expert positioning is too weak."
V1  ·  submitted by A. Rao  ·  1 Sep, 09:15  ·  SUPERSEDED
```

**The queue keeps its own job.** `/drafts` stays *the drafts awaiting this PM, oldest first* — a
review queue that also lists finished work is a queue you cannot work from. The history above is on
the **case**, next to the draft, where somebody asking "what happened to this letter" is already
looking. Two surfaces, two questions.

---

## 4. "Inline comments" — what this can and cannot be

The requirement says *returned with inline comments*, and `process-automation.md`'s **A12** records
the same phrase as satisfied. **It was satisfied by Google Drive's own commenting**, and Unit 30
removes Drive. S3 stores bytes and comments on nothing.

**So be exact about what closes:**

- **Per-version comments — yes.** A comment attached to V2, shown against V2, carried in the
  version history above. That is what §3 builds and it is what a Case Manager actually needs:
  *what must change before I resubmit.*
- **Annotations positioned inside the document — no.** Comment anchors on page 3, paragraph 2
  require a document viewer that understands the file, and building one is a product, not a
  migration. **Do not record A12 as fully covered.**

**The honest interim, and it is not a fallback so much as how this already works:** the PM writes
what needs changing in their return comment, and the CM edits the document in the tool they already
author in. The letter is a `.docx` a human writes; the review is a conversation about it.

`process-automation.md`'s A12 row becomes **partly covered** with that distinction written out,
rather than **open** — which is what it says today and which understates what §3 delivers.

---

## 5. What is NOT in this unit

- **No AI on either surface.** Unit 31 §9 governs: no drafting, no review, no summarising a PM's
  strategy. A "suggested strategy" is a production decision wearing a helpful hat.
- **No rich text.** Both fields are plain text. A comment nobody can bold is a comment; a rich-text
  field is a sanitiser, an editor dependency and an XSS surface for no gain here.
- **No comment threads.** One comment per version, from the PM who ruled on it. A back-and-forth
  belongs in Unit 23's case notes, which exist for exactly that and are already on the case page.

---

## 6. Acceptance criteria

1. A PM writes an **expert selection rationale** when assigning or reassigning an expert; it is
   optional, and a reassignment records a new one without destroying the case strategy.
2. The rationale is visible to **GM · Brand Manager · PM · ENM**, and absent from the payload for
   the Case Manager, the client and the expert — asserted at the response body, not in the UI.
3. Returning a draft writes `status = 'RETURNED'` **and** `review_comment` on **that version's**
   row, in the transition's transaction.
4. The case page shows every draft version, newest first, with uploader, timestamp, status and the
   review comment where there is one.
5. `/drafts` still lists only drafts awaiting review, oldest first — unchanged.
6. **No comment is ever attached to a version by matching timestamps.** The column is written by
   the transition or it is null.
7. `./mvnw verify` and `npm run build` green; the six-role projection check re-run for the new
   field.

---

## 7. Open questions

| # | Question | Default |
|---|---|---|
| **(a)** | Does the **Brand Manager** read the expert rationale? §2 says yes (oversight); they are excluded from strategy notes today, so this is a deliberate divergence worth confirming | Yes — it is an oversight fact, not production guidance |
| **(b)** | Should an **approval** also carry a comment? §3 only requires it on a return | Optional on approve, required on return — a rejection a CM cannot act on is the failure worth preventing |
| **(c)** | Does the rationale appear on the **redacted expert profile** the client sees? | **No.** It is internal reasoning about a person, and Unit 13's whole substance is what the client does not see |
