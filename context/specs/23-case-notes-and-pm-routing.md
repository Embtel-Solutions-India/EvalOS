# Unit 23 — Case notes, and routing intake to the PM

**Phase:** 2 — Connect the seams
**Depends on:** 04 (lifecycle + audit), 08 (the board), 09 (case detail),
22 (the PM inbox this unit makes the single front door)
**Supersedes:** nothing. It **narrows** Unit 22's GM surface and **widens** Unit 09's
timeline panel; both files stay the authority for everything not restated here.
**Gating open questions:** none.

## Goal

Two changes that travel together because they are the same decision seen twice:
**the case's front door is the Project Manager, and the case carries its own
conversation.**

1. **The GM stops working the queue.** The pool lane leaves their board, and the
   cases inbox and doc checklists leave their sidebar. A new case lands in the
   **PM inbox**, the PM takes it, and the PM staffs it — coordinator and case
   manager both.
2. **The timeline becomes Notes + Timeline.** Anyone who can read a case can
   write a note on it, in the same append-only trail that already records every
   transition. The note the case arrives with from GHL is the first entry.

**Verifiable result:** a case created by Handoff A appears in a Project Manager's
inbox under *Unassigned* carrying its intake note; the PM takes it, assigns a
coordinator and a case manager, and each of those three can leave a note the next
one reads — while the GM's sidebar no longer offers the inbox or the checklists
and their board no longer draws a pool lane.

## Decisions taken

### 1. The PM claims the case; the pool stays, and stops being a screen

`assign_pm` is what stamps `team_id` and is therefore what makes a case visible to
a team. It was gated `GM or BRAND_MANAGER`. It becomes
**`GM or BRAND_MANAGER or PROJECT_MANAGER`**.

The **pool is not deleted.** `PoolStatus.IN_POOL` remains the fact that a paid case
has nobody on it, because that fact is real and three things already read it — the
inbox's *Unassigned* preset, the `unassigned` nav badge, and the
`NEW_CASE_IN_POOL` notification. What changes is that the pool stops being a
*place*: it has no lane of its own on the GM's board, and the only screen that
shows it is the queue of the person who acts on it.

> **Refused: auto-routing at intake.** Picking a PM round-robin in
> `CaseIntakeService` would delete the pool outright, and with it the one honest
> statement the system makes about a case nobody has looked at yet. A case
> auto-assigned to an absent PM is not staffed, it is hidden.

### 2. A TEAM-tier caller sees the unteamed rows of their brand

This is the change that makes decision 1 possible, and it is a scope widening, so
it is stated rather than slipped in.

`ScopePredicate` gave a `Tier.TEAM` caller `brand = mine AND team = mine`. A pooled
case has `team_id IS NULL`, so the predicate excluded it — **a Project Manager
could not read the cases they are now expected to claim.** The inbox's *Unassigned*
preset has been filtering a set that was always empty for the only role that can
reach it.

`ScopePredicate.Fields` gains one component, `unteamedVisible`, and the TEAM branch
becomes `team = mine OR team IS NULL` when it is set. **It is set on cases and
nowhere else.** `TeamMemberQueryService` is the only other holder of a team axis and
keeps the strict predicate: an unteamed *person* is not unclaimed work, and widening
both from one flag is how a scope starts meaning something the schema never said.

The brand predicate is untouched, so this stays inside one brand. A GM is `Tier.ALL`
and was never affected.

### 3. A note is an audit row, not a table

**`AuditAction.NOTE_ADDED`**, written through `AuditService.recordEvent` with the
text in the after-snapshot's `note` — the pattern `flagToPm` already uses for an
event that changes nothing about the case.

There is no `case_note` table, no `notes` read endpoint and no second panel. The
trail already carries a `note` on every row, `CaseTimelineService` already projects
it, `Timeline` already renders it, actor names already resolve, and brand scoping
already applies. A notes table would be a second append-only store beside the
append-only store, with its own scope predicate to keep in step.

Three properties come free and are the reason this is the right shape:

- **Append-only.** Invariant 13 already forbids editing the trail three times over
  (`updatable = false`, no repository method, a database trigger). A note cannot be
  edited or deleted after the fact, which is what makes "I told you on Tuesday"
  answerable.
- **Interleaved.** A note sits in time between the transitions it is about, because
  it is one of them. A separate table would need merging on read.
- **Scoped.** `POST /cases/{id}/notes` carries **no `@PreAuthorize`**. The scoped
  load is the gate, exactly as it is for `GET /cases/{id}` — "everyone related to
  the case" is not a role list, it is precisely the set of callers the scope already
  admits, and writing that set out as roles would be a copy that drifts.

**Shape: one shared thread.** A note is free text and is addressed to nobody. An
optional "for:" recipient was considered and refused for now — it adds a snapshot
component and a roster fetch to the composer to encode something the thread already
conveys, and the next person reads the case either way.

**No notification fires on a note.** Deliberate, and recorded so it is not read as
an oversight: the people on a case open the case. If notes turn out to need a ping,
it is a `Route` in `NotificationListeners` and nothing else moves.

### 4. The case arrives with its note

`OpportunityWon` gains an optional `notes` field, carried through
`CaseIntakeService.NewCase` onto the `CREATED` audit row's snapshot note. Whatever
sales typed on the opportunity is the first thing on the timeline, attributed to
`System` like every other webhook-written row.

Optional, not required: a case with no note is normal, and rejecting a delivery for
a blank field would fail Handoff A over a nicety.

### 5. What leaves the GM

| Surface | Before | After |
|---|---|---|
| Board pool lane | GM, Brand Manager | Brand Manager |
| `/inbox` (Cases inbox) | GM, Project Manager | Project Manager |
| `/checklists` (Doc checklists) | GM, Brand Manager, Project Coordinator | Brand Manager, Project Coordinator |
| `/drafts` (Draft review) | GM, Project Manager | Project Manager — **and the gate too**, see 5a |

**Only the nav and the lane, except for draft review (5a). No other backend gate is narrowed.** The GM stays a superuser
on every transition, `GM_OR` is untouched, and `/board`, `/drafts` and `/delivery`
keep them. This is the rule Unit 22 set for `/delivery` read the other way: a screen
whose transitions the GM can drive should not 403 them. Taking the *entry* away
removes it from their working day; taking the *gate* away would make them unable to
unblock a case at 6pm, which is the one thing a GM is for.

### 5a. Draft review leaves the GM outright (amendment, Unit 23a)

Decision 5 above is a **nav-only** narrowing: the GM keeps every backend gate. Draft review is the
one exception to that rule and to the GM-superuser rule generally.

`draft/pm-approve` and `draft/pm-return` **drop `GM_OR`**, `/drafts` becomes `['PROJECT_MANAGER']`,
and `boardRules` marks both actions `gm: 'never'` so the buttons do not render for a GM on the case
page either.

**Why an exclusion rather than a hidden entry.** Approving a draft is a judgement about a Case
Manager's work, made by the person who assigned it to them and who answers for what reaches the
client. A superuser path *around* that reviewer is not oversight — it is a second reviewer with
none of the context, and it makes "who approved this" ambiguous on the one artefact the business is
paid for. The GM's lever here is reassigning the PM, not overriding them.

`QuickAction.gmOnly` (boolean) became `QuickAction.gm: 'only' | 'never' | undefined`, and the rule
moved into an exported `admits(action, role)` that both `actionsFor` and its test call — the old
test re-derived the expression, which is how "the GM sees everything" would have survived this
decision. `CaseControllerTest.Route.gmMayAct` asserts the **403** rather than skipping the row, so
restoring `GM_OR` fails a test instead of passing quietly.

## What gets built

**Backend**

- `ScopePredicate.Fields` — `unteamedVisible` component; TEAM branch ORs `IS NULL`.
  A 3-arg constructor keeps every existing call site compiling and false.
- `CaseRepository.SCOPE` — sets it.
- `AuditAction.NOTE_ADDED`.
- `CaseLifecycleService.addNote(caseId, note)` — scoped load, refuse blank, refuse a
  closed case, one audit row.
- `CaseController` — `POST /{id}/notes`, no `@PreAuthorize`.
- `CaseController.assignPm` — `PROJECT_MANAGER` added to the gate.
- `CaseController.pmApproveDraft` / `pmReturnDraft` — `GM_OR` removed (23a).
- `GhlOpportunityHandler.OpportunityWon.notes` → `NewCase.notes` → `CREATED` snapshot.

**Frontend**

- `navigation.ts` — the three role lists in the table above.
- `boardRules` — `gmOnly` → `gm: 'only' | 'never'`, plus the exported `admits()` both
  `actionsFor` and the test call.
- `BoardView.SEES_POOL` — `['BRAND_MANAGER']`.
- `caseApi` — `postNote`, and `NOTE_ADDED` on the `AuditAction` union.
- `Timeline` — renamed *Notes & timeline*, a composer at the foot, a `NOTE_ADDED`
  label, and notes drawn distinctly from transitions.
- `InboxPage` — a **Take this case** action on a pooled row, posting `assign-pm`
  with the caller's own member id, then reloading.

## Verification

- `./mvnw verify` and `npm run build` clean.
- A PM lists the board and receives their brand's pooled cases; a PM of brand A
  receives none of brand B's.
- A Case Manager posts a note on their own case and is refused on a case that does
  not name them — by the scope, with no role gate involved.
- The `CREATED` row on a case delivered with `notes` carries that text.
- Nav: a GM's sections contain neither `/inbox`, `/checklists` nor `/drafts`; a deep
  link to any of them renders the 403 view rather than the screen.
- A GM posting to `draft/pm-approve` or `draft/pm-return` receives **403**, and no
  Approve/Return button renders for them on the case page — while every other
  transition on that stage still does.

## Out of scope

Notes on experts or payouts; note editing; @-mentions; a note-arrived notification;
the Google Ads pipeline card on the GM dashboard (separate, deferred).
