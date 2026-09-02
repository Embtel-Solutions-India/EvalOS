# Unit 31 — Production lifecycle v2: eleven stages, one owner each

> **Status: BUILT 2026-09-02** (backend + board; the S3 half waits on Unit 30). Three things the
> build surfaced that this spec did not predict are recorded in the tracker: a duplicate expert
> signature would have 500'd once signing stopped being stage-preserving; a rematch returns to
> `CLIENT_APPROVAL` rather than to assignment; and `DELIVERED` must be `full` access, not
> `status`, because `close` is declared *from* it.
>
> **Originally: SPECCED (2026-09-02), not built.** A **pivot spec**, written before code because
> it changes the state machine every other unit is built on.
>
> **What it changes in one line:** the five-stage pipeline with sub-status chips becomes
> **twelve explicit stages** (eleven were specced; the twelfth follows from settling that a
> stage is entered by the *act* that starts its clock — §3), each with exactly **one owner,
> one primary action, one event and one next owner**. **The board draws eight columns**, two
> stages sharing one only where they share an owner. Three facts EvalOS currently holds as *sub-statuses on a stage*
> become *stages*, and the draft becomes a **versioned file** rather than a link.
>
> **This reverses a documented decision** (spec 08: "the 8-column reading is a derived
> grouping"). §2 says why that decision was right for a board and wrong for a workflow.

**Phase:** 2 — Connect the seams
**Depends on:** 04 (state machine), 30 (S3 document store — the draft is now a file)
**Amends:** 04, 08, 09, 10, 14, 15, 17, 22
**Governing rule:** **no AI anywhere in a production decision.** §9.

---

## 1. The rule the whole unit exists to enforce

```
ONE STAGE → ONE OWNER → ONE REQUIRED ACTION → ONE EVENT
          → ONE NEXT STAGE → ONE NEXT OWNER → NOTIFICATION
```

Everything below is that sentence applied twelve times. Where the current system is
ambiguous about **who acts next**, this unit's answer is always to make the owner explicit
rather than to add a status somebody has to interpret.

---

## 2. Why sub-statuses become stages

EvalOS today has **five active stages** and carries three further facts as columns on the
case: `pm_approval_status`, `client_approval_status`, `expert_sign_status`. The board draws
them as chips. `08-production-board.md` defends this explicitly — the eight-column reading
is a *derived grouping*, and one stage with a chip was held to be simpler than two stages.

**That was right for a board and wrong for a workflow, and the difference is ownership.**
A chip says what state the work is in. It does not say **whose turn it is**. `DRAFT_GENERATION`
is currently the Case Manager's stage *and* the Project Manager's review *and* the client's
review — three different owners inside one stage, distinguished only by two nullable
columns that a reader has to combine correctly. Every "who has this?" question needs a join
in the reader's head, and the answer is wrong whenever the two columns disagree.

**Explicit stages make the owner a property of the stage.** The case card can then state the
current owner as fact rather than derive it, which is §8's requirement and the thing the
production team actually asked for.

**The cost, stated plainly:** more stages mean more transitions, a wider `TABLE` in
`CaseTransitions`, and a board that must not become eleven columns of one card each. §10
carries the UI answer — eight columns for twelve stages. This is a genuine trade, not a free improvement.

---

## 3. The twelve stages

| # | Stage | Owner | Primary action | Event | → Next | Notifies |
|---|---|---|---|---|---|---|
| 01 | **Document Collection** | Coordinator | Mark Documents Complete | `documents.completed` | 02 | PM |
| 02 | **PM Review & Assignment** | PM | Assign Case (expert + CM) | `case.assigned` | 03 | CM, Expert, ENM |
| 03 | **Draft In Progress** | Case Manager | Submit to PM *(requires an uploaded draft)* | `draft.submitted` | 04 | PM |
| 04 | **Draft Review** | PM | Approve for Client Review · **or** Return | `draft.pm_approved` / `draft.returned` | 05 / **03** | Coordinator / CM |
| 05 | **Ready to Send** | Coordinator | **Send to Client** | `draft.sent_to_client` | 06 | Client |
| 06 | **Client Review** | Coordinator *(client acts)* | client approves · **or** requests revision | `draft.client_approved` / `draft.revision_requested` | 07 / **03** | CM, PM / CM |
| 07 | **Client Approval** | Case Manager | **Send to Expert for Signing** | `expert.sent_for_signing` | 08 | Expert, ENM |
| 08 | **Expert Signing** | Case Manager *(expert acts)* | expert signs · **or** CM reassigns | `expert.signed` | 09 | PM |
| 09 | **Final QC** | PM | Approve Final QC · **or** Return for Correction | `qc.approved` / `qc.failed` | 10 / **03** | Coordinator / CM |
| 10 | **Ready to Deliver** | Coordinator | Deliver to Client | `case.delivered` | 11 | Client, PM |
| 11 | **Delivered** | Coordinator | Close Case | `case.closed` | 12 | PM |
| 12 | **Closed** | — | — | — | — | — |

### Twelve, not eleven — and the twelfth came from an answer, not from a preference

**Settled 2026-09-02: stage 06 Client Review is entered when the Coordinator presses Send,
not when the PM approves.** That leaves a real gap the eleven-stage draft did not have a
home for — the case is PM-approved but not yet with the client, and somebody has to act.
That somebody is the Coordinator, so it is a stage.

**It mirrors *Ready to Deliver* exactly, which is the argument for it.** The end of the
pipeline already has this shape: PM approves QC → **Ready to Deliver** (Coordinator holds
it) → presses Deliver → **Delivered**. The draft half now reads the same way: PM approves
the draft → **Ready to Send** (Coordinator holds it) → presses Send → **Client Review**.
Two identical hand-offs drawn identically, rather than one drawn two ways.

**The same reasoning applies once more, at stage 07.** *Client Approval* is the CM's stage
and its action is **Send to Expert for Signing**; *Expert Signing* is entered by that send.
So all three "an owner holds it, then hands it on" moments — 05, 07 and 10 — have the same
shape.

### This deletes a column I had specced

§7 proposed a new `sent_to_expert_at`, because the 24-hour clock was starting at stage entry
(client approval) rather than at the send. **Making the send the stage boundary fixes that
structurally: `stage_entered_at` *is* the send time.** No new column, and the same is true
for the client's review clock, which had the identical latent defect — a draft approved
Friday and sent Monday would have charged the client for the wait.

**That is the general lesson and it is worth stating once:** when a clock starts at an act,
make the act a transition. A timestamp column beside a stage is a second answer to "when did
this begin", and the two drift.

### Mapping from today

| New stage | Today | Change |
|---|---|---|
| 01 Document Collection | `DOC_COLLECTION` | name only |
| 02 PM Review & Assignment | `EXPERT_ASSIGNMENT` | **renamed** — the old name described one of the two things that happen here and hid the strategy notes and the CM assignment |
| 03 Draft In Progress | `DRAFT_GENERATION` | **split** |
| 04 Draft Review | `DRAFT_GENERATION` + `pm_approval_status = PENDING` | **sub-status → stage** |
| 05 Ready to Send | — | **new.** The gap between PM approval and the Coordinator sending |
| 06 Client Review | `DRAFT_GENERATION` + `client_approval_status = PENDING` | **sub-status → stage**, entered by the send |
| 07 Client Approval | — | **new stage.** §5 — and the CM's send-to-expert lives here |
| 08 Expert Signing | `EXPERT_SIGNING` | entered by the CM's send; owner is the CM. §6 |
| 09 Final QC | `EXPERT_SIGNING` + `PM_QC_APPROVE` | **implicit step → stage.** QC currently happens *inside* Expert Signing |
| 10 Ready to Deliver | `FINAL_DELIVERY` | renamed |
| 11 Delivered | `FINAL_DELIVERY` + delivered | **implicit step → stage** |
| 12 Closed | `CLOSED` | unchanged |

**The three sub-status columns are not dropped.** `pm_approval_status`,
`client_approval_status` and `expert_sign_status` still record the *outcome* of a review —
approved, returned, revision-requested — which is a different fact from which stage the
case sits in. What stops being true is that the **stage is derived from them**. Dropping
them would lose the outcome; keeping them as the stage driver is what this unit ends.

---

## 4. Revision loops

Three loops, all landing in **03 Draft In Progress**, because that is where the Case
Manager works and a revision is always CM work.

```
04 Draft Review ──PM returns──▶ 03 ──CM resubmits──▶ 04
06 Client Review ──client requests revision──▶ 03 ──▶ 04 ──approve──▶ 05 ──send──▶ 06
09 Final QC ──QC failed──▶ 03 ──▶ 04 ──▶ 05–07 if the content changed
```

**A loop never skips the approvals below it.** A QC failure that changes the letter's
content sends it back through PM review, and back to the client if what the client approved
is no longer what will be delivered. **The system must not let a user jump from 03 to 10.**

**`qc.failed` does not exist today** — `CaseTransitions` has `PM_QC_APPROVE` and no
counterpart, so a failed QC has nowhere to go and is presumably handled by conversation.
This unit adds it, and it is the single most valuable transition in the list: it is the one
that catches a bad letter before a client sees it.

---

## 5. Stage 07 — Client Approval, and why it is separate

> "This is a new separate stage. Do not combine it with Client Review."

**Client Review is a period; Client Approval is an event that locks a version.** Collapsing
them loses the distinction between *the client is looking at V3* and *the client accepted
V3* — and the second is what the expert signs and what the business is paid for.

**On approval the system records** the approved version, who approved, and when — and
**locks that version**. After that:

- No CM, PM or Coordinator silently modifies it.
- Any change is a **new version**, and if the change is substantive it needs the client's
  approval again. **"Substantive" is a human judgement made by the PM**, recorded as a
  decision — not a rule the system infers, because a system that decided this would be
  deciding whether a client needs to re-consent.

---

## 6. Ownership changes, and every gate they break

**This is the section to read before touching `CaseController`.** The workflow reassigns
several transitions, and each one contradicts a gate that exists today.

### Owner is not the exclusive actor

**Settled 2026-09-02, and it governs every row below.** A stage's **owner** is who is
accountable for it: whose queue the case appears in, whose name the card shows, and who the
notification goes to. It is **not** a statement that nobody else may act.

The first draft of this spec read "Coordinator owns document collection" as "narrow the gate
to the Coordinator" and proposed removing the Brand Manager and PM. That was wrong. The
Coordinator owns it; the BM and PM keep the gate, because oversight unblocking a stalled
case is not the same act as working it.

**This codebase already draws that line and calls it by name.** Unit 23 removed `/inbox` and
`/checklists` from roles' navigation while leaving `GM_OR` on the backend gates, with the
comment: *"this is a listing decision and not a capability one."* The eleven-stage table in
§3 is the listing decision, at a larger scale.

**So the rule for implementing §3 and this section:**

- **Owner → queue placement, card label, notification routing.** Presentation and routing.
- **Gate → who the server permits.** Unchanged unless a row below says otherwise.
- **No gate is narrowed by this unit.** Every change in the table is a *widening* — the Case
  Manager gaining the expert-signing transitions they are now accountable for. A role that
  can act today can still act tomorrow.

That is the safer direction and the reversible one: a gate narrowed by mistake is discovered
when somebody legitimate is refused mid-case, which is the worst moment to discover it.

| Transition | Gate today | New owner | Verdict |
|---|---|---|---|
| `docs-complete` | GM · BM · PC · PM | **Coordinator owns** | **Unchanged.** BM and PM keep it (answered 2026-09-02). Ownership moves the case into the Coordinator's queue and names them on the card; it takes the capability from nobody |
| `expert/signed` | GM · PM · ENM | **CM** records it | **Add CM.** The CM sends the letter and monitors signing, so recording the outcome is theirs |
| `expert/declined` | GM · PM · ENM | **CM** | **Add CM**, same reason |
| `expert/timed-out` | GM · BM · PM | **CM** | **Add CM.** Built this session with the PM as owner; the workflow gives the CM the 20h/24h alerts and the reassignment, so the actor and the alert must match |
| `reassign-expert` | GM · PM · ENM | **CM** acts, **ENM** notified | **Add CM.** ENM keeps it (they support) and is notified on every reassignment |
| `draft/client-approve` · `client-revisions` | GM · PC | **Client**, in the portal | Portal path already exists (Unit 14). The Coordinator's staff path **stays** — a client who phones in their approval must still be recordable, and that is a different actor, audited as such |
| **`qc-fail`** | — | **PM** | **New.** `GM_OR PROJECT_MANAGER`, mirroring `qc-approve` |
| **`send-to-expert`** | — | **CM** | **New.** §7 |

**No row above narrows a gate.** The only proposed narrowing was `docs-complete`, and it was
answered: it stays. Every remaining change adds a role.

**Three things this does not change, and each was checked rather than assumed:**

1. **`draft/pm-approve` and `pm-return` stay PM-only with no GM override** (Unit 23a). The
   workflow does not touch that, and the reasoning holds: reviewing a CM's draft is the
   judgement of the PM who assigned it, and a superuser around the reviewer is a second
   reviewer.
2. **The ENM still edits no case content.** The workflow says so explicitly; the existing
   `Tier.SUPPLY` field projection already enforces it.
3. **The expert still sees only their own case's letter.** Unit 15's scoping is unchanged,
   and the workflow's expert-portal list (§19 of the requirement) matches it.

---

## 7. `send-to-expert` — the missing transition

Today the expert is chosen at `assign-cm` (stage 02) and the case enters `EXPERT_SIGNING`
automatically on client approval. **Nobody sends anything.** The workflow requires the CM to
press *Send to Expert for Signing*, and that act is what starts the signing SLA.

**It is now a stage boundary rather than a timestamp.** The first draft of this section
proposed a `sent_to_expert_at` column, because the clock was starting at stage entry — so a
letter sent two hours after client approval charged the expert for those two hours and could
report them overdue having had less than the full budget. Making the send the transition
into stage 08 fixes it structurally: **`stage_entered_at` is the send time.**

**The same defect existed on the client's side and is fixed the same way.** A draft approved
Friday and sent Monday would have charged the client for the wait; stage 06 is now entered by
the Coordinator's Send.

**One rule, stated once:** *when a clock starts at an act, make the act a transition.* A
timestamp column beside a stage is a second answer to "when did this begin", and two answers
drift. This is the same argument `V20` makes about `draft_link` and `drive_link`, and the
same one §8 makes about a version count.

### 7a. The expert SLA is three working days, and nobody meant it to be

**`SlaCalculator.EXPERT_SIGN = Duration.ofHours(24)`, measured on `BusinessCalendar`.** That
class's own comment defines the unit — *"three business days, and a business day is eight
hours"* — so **24 business hours is three working days**, and the 20-hour warning lands two
and a half working days in. The business says 24 hours and means one day. Asking whether the
clock is business or wall was the wrong question: whichever it is, **the number is wrong for
the intent**.

**Decision: keep the business calendar, set the budget to 8 business hours — one business
day.**

**Why not wall clock, and the reason is not comfort.** A letter sent Friday 4pm would be
overdue Saturday 4pm. `expert_case_offer` counts `TIMED_OUT` into the acceptance rate that
`ExpertMatchService` ranks on, so a wall clock would **systematically demote good experts
for EvalOS's own sending time** — a scoring defect dressed as a deadline. Weekends and
holidays must not count, which is precisely what `BusinessCalendar` is for.

**Why 8 hours is the right number.** Sent Tuesday 10:00 it falls due Wednesday 10:00 —
exactly what wall-clock "24 hours" would give on a working day. Sent Friday 16:00 it falls
due Monday afternoon instead of demanding a weekend signature. That is what "24 hours"
means when a human says it to an external contractor.

**The 20-hour warning is dropped as a separate threshold.** `AT_RISK_FRACTION` is already
0.75, and 0.75 × 8 = **6 business hours**, leaving two hours' notice. The business's 20/24
ratio is 0.83 — near enough that reusing the existing fraction gives **one** threshold
rather than two that can drift apart. Unit 19's sweep alerts off `AT_RISK`; it does not need
a number of its own.

**Rename the label.** Every screen and message saying "24 hours" says **"one business day"**.
A label next to a clock that means something else is how this survived unnoticed.

**Scope: this budget only.** `DOC_COLLECTION`'s three-business-days reading is commented as
deliberate and stays. `EXPERT_SIGN` carries no such comment, which is the evidence that 24
was written meaning hours and landed as days. **The other budgets are not audited here** —
`FIRST_DRAFT` and `CLIENT_REVIEW` are both 48 (six working days) and may be equally
intentional or equally accidental. That is a separate review, deliberately not folded in.

**Combined with §7, the expert's clock changes twice**: it starts at `sent_to_expert_at`
rather than stage entry, and it runs for one business day rather than three. Both are
corrections, and the board built this session inherits both.

---

## 8. Draft versioning

> "Every uploaded production document must create a new version. Never overwrite."

Today the case carries `draft_link` (one link) and `draft_version_count` (an integer).
**A count is not a history** — it cannot say who uploaded V2, when, what the PM said about
it, or which version the client approved.

**New table `case_document`**, append-only in spirit:

| Column | Note |
|---|---|
| `id`, `brand_id`, `case_id` | scoped like everything else |
| `kind` | `DRAFT` · `CLIENT_UPLOAD` · `REDACTED_PROFILE` · `SIGNED_LETTER` |
| `version` | per case per kind; `V1`, `V2`… **never reused** |
| `object_key` | the S3 object (Unit 30) |
| `filename`, `content_type`, `size_bytes` | as uploaded |
| `uploaded_by`, `uploaded_by_type`, `uploaded_at` | staff / client / expert — the same actor vocabulary the audit trail uses |
| `notes` | the uploader's description |
| `status` | `SUBMITTED` · `RETURNED` · `PM_APPROVED` · `CLIENT_APPROVED` · `SIGNED` · `SUPERSEDED` |

**`draft_link` is replaced by "the latest `DRAFT` row for this case".** Keeping both would
be two answers to one question, and `V20`'s own comment is about exactly that class of bug.

**Uniqueness is a database constraint, not a service check:** `(case_id, kind, version)`
unique. Two concurrent uploads racing for V3 is the failure a service check misses.

**The CM cannot submit without a file.** Enforced server-side — a client that only hides
the button is a client, and the transition is what must refuse.

---

## 9. No AI in production decisions

Explicit and non-negotiable. **No AI for:** document verification · expert selection · draft
creation · draft review · client approval · expert reassignment · quality control.

**The system may automate:** notifications, status transitions, timestamps, versioning,
audit logging.

**This is already the standing decision** — Unit 20 and Unit 21 both record it, and Unit 12's
match engine "never auto-assigns" and is assistance the PM can ignore entirely. This unit
restates it because it is the constraint most likely to be eroded by a plausible-sounding
convenience, and the shortlist is exactly the shape of feature that erodes it.

**The line: a human presses every button that changes a stage.** A sweep may *notice* that
an expert is 24 hours late; a human decides what to do about it. That is already the rule
for `EXPERT_TIMED_OUT` and it generalises to everything here.

---

## 10. The board — eight columns for twelve stages

**Settled 2026-09-02: eight columns.** Twelve columns at 1366px are twelve narrow strips
holding one card each, and the board stops being scannable — which is the only thing it is
for.

**The eight lanes, and what folds into them:**

| Column | Stages |
|---|---|
| Doc Collection | 01 |
| PM Review | 02 |
| Drafting | 03 |
| Draft Review | 04 |
| Client Review | **05 + 06** — *Ready to Send* and *Client Review* are both the Coordinator's, one card with a chip saying which |
| Expert Signing | **07 + 08** — *Client Approval* and *Expert Signing* are both the CM's, same treatment |
| Final QC | 09 |
| Ready to Deliver | 10 |

**Delivered (11) and Closed (12) are a filter, not columns** — they only grow. Exception
lanes (hold, rematching, refund) are unchanged.

**Note what the folding is and is not.** Two stages share a column **only where they share an
owner**, so the column still answers "whose turn is it" without a chip — which is the whole
point of §2. The chip says *what they must do next*, not *who they are*. This is the
opposite of the arrangement §2 rejects, where one column held three different owners.

**Every card states its owner, and this is the point of the whole unit:**

```
CASE #12345 · Client · Service · Deadline
CURRENT STAGE   Client Review
CURRENT OWNER   Project Coordinator
NEXT ACTION     Waiting for client approval
LAST EVENT      Draft sent to client
LAST UPDATED    2 Sep 2026, 10:35
```

**Owner is derived from the stage, in one table, in one place.** A second table mapping
stage → owner beside `STAGE_ACCESS` is how the board and the server come to disagree; this
is the same lesson `navigation.ts` records about the nav and the route guard being one
table.

**Note the card just got taller**, and the board density pass earlier this session shortened
it deliberately to fit more cases at 1366px. *Owner* and *next action* are worth the height;
*last event* and *last updated* likely are not, on a card — they belong on the case. Decide
that against the real board, not in this document.

---

## 11. File permission matrix

| File / information | Coordinator | PM | CM | Client | Expert |
|---|---|---|---|---|---|
| Client uploaded documents | view/download | view/download | view/download | own files | **no** |
| PM strategy notes | view | **edit** | view | no | no |
| Internal comments | limited | **edit** | view | no | no |
| CM draft (pre-approval) | view | review | **edit/upload** | **no** | **no** |
| PM-approved draft | view | view | view | view/download | no |
| Client feedback | view | view | view/respond | **create** | no |
| Client-approved version | view | view | view | view/download | **yes** |
| Signed document | view/download | view/download | view/download | after delivery | own signing |
| Final QC | status | **edit/approve** | view | no | no |
| Sales information | limited | as authorized | no | no | no |

**Two rows carry most of the risk.** *CM draft (pre-approval)* is invisible to the client
and the expert — a draft leaking before PM approval is the failure this row prevents. And
*signed document → client* is **after delivery**, not on signing: the client sees the final
letter when the Coordinator delivers it, not the moment the expert signs.

**This matrix is a projection rule, not a UI rule.** It belongs beside
`CaseController.seesCaseContent` and `SEES_STRATEGY_NOTES`, which already do this work for
three of these rows. Hiding a control while the payload still carries the field is not
enforcement — architecture principle 7.

---

## 12. What breaks

Honest inventory. This is a state-machine change, so the blast radius is wide.

| Breaks | Why |
|---|---|
| `Stage` enum + `CaseTransitions.TABLE` | five stages → eleven, plus two new actions |
| `SlaCalculator` | budgets are keyed by stage; six stages become eleven and **the expert clock moves off `stage_entered_at`** (§7) |
| `boardRules.ts` `STAGE_COLUMNS` / `STAGE_ACCESS` | `Record<Role, Record<Stage, …>>` — every role × every new stage must be declared. **The build failing here is the feature** |
| `queueRules.ts` | `/inbox`, `/drafts`, `/delivery`, `/expert-assignment` all select on stage names |
| Unit 17 dashboards | cycle time and stage-mix figures are per stage |
| `V5` sub-status columns | kept, demoted — §3 |
| `draft_link` | replaced by `case_document` |
| Migration | stage values are **text in the database**; every existing row must map through §3's table in one migration, and `DRAFT_GENERATION` splits three ways using the two sub-status columns to decide which |

**The split migration is the risky one and deserves its own review.** A `DRAFT_GENERATION`
row becomes 03, 04 or 05 depending on `pm_approval_status` and `client_approval_status`, and
rows where those two disagree are exactly the ambiguity this unit exists to remove — so the
migration must have a defined answer for every combination, including the ones that should
not exist.

---

## 13. Open questions

**All six are answered as of 2026-09-02.** Nothing in this unit is blocked on a decision;
what remains is Unit 30's three, which this unit depends on for the draft-as-a-file model.

| # | Question | Blocks |
|---|---|---|
| ~~**(a)**~~ | ~~Does `docs-complete` lose the BM and PM?~~ **ANSWERED 2026-09-02: no.** The Coordinator **owns** it; the Brand Manager and PM **keep** the gate. This established the owner-vs-actor rule now stated at the head of §6 | — |
| ~~**(b)**~~ | ~~Stage 05 entered on PM approval or on Send?~~ **ANSWERED 2026-09-02: on the Coordinator pressing Send.** Which adds **Ready to Send** as a stage (twelve, not eleven — §3), and **removes the `sent_to_expert_at` column** §7 had proposed: the send is the boundary, so `stage_entered_at` is the send time | — |
| ~~**(c)**~~ | **ANSWERED 2026-09-02: yes.** A QC failure whose correction changes the content goes back through the client for approval. §4 and §5 already require it; this confirms it is the business's answer and not an inference | — |
| ~~**(d)**~~ | **ANSWERED 2026-09-02 — and the question was hiding a defect.** See §7a. Business calendar kept; the *budget* changes from 24 hours to **8 business hours (one business day)** | — |
| ~~**(e)**~~ | ~~Eight columns or eleven?~~ **ANSWERED 2026-09-02: eight.** §10 has the mapping; two stages share a column only where they share an owner | — |
| ~~**(f)**~~ | ~~Client sees the signed letter before delivery?~~ **ANSWERED 2026-09-02: no.** The client sees the final letter when the Coordinator delivers it, not when the expert signs. §11's matrix stands as written | — |

---

## 14. Acceptance criteria

1. **Every stage has exactly one owner**, and the case card shows it without the reader
   combining two columns.
2. **Every transition names its actor role**, and no transition is reachable by a role the
   workflow does not give it — asserted per transition, not sampled.
3. **No approval can be skipped.** 03 → 09 is not declared; a QC failure returns to 03 and
   comes back through 04. Asserted by walking the illegal paths and expecting refusals.
4. **A draft cannot be submitted without a file**, and **no version is ever overwritten** —
   `(case_id, kind, version)` unique, enforced by the database.
5. **The client-approved version is locked**; a later change is a new version.
6. **The expert SLA runs from the send**, which is stage 08's entry, so an expert who
   receives the letter late is not charged for the delay — **and its budget is 8 business
   hours, not 24.** The same holds for the client's review clock at stage 06. A letter
   sent Friday afternoon is not overdue on Saturday, asserted against
   `BusinessCalendar`; and no screen or message says "24 hours".
7. **The permission matrix holds at the payload**, not in the UI: for each row, the field is
   absent from the response for a role that may not see it.
8. **The stage migration maps every existing row**, including rows whose two sub-status
   columns disagree, with a defined answer for each combination.
9. **No AI touches a production decision** — no model call exists on any path in §3's table.
10. `./mvnw verify` and `npm run build` green; the six-role scope check re-run against the
    new stage set.
