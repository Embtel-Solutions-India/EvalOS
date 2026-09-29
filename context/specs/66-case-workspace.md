# Unit 66 — The case workspace: progress strip, one action bar, overlays

Specced 2026-09-30 from a gap audit of the staff SPA against every staff route. **Frontend only —
no migration, no new endpoint.** Every write below already exists on the server; what is missing
is the screen that reaches it.

First of three UI units cut from that audit: **66** the case page (this), **67** role queues (act
from the board card, My drafts, Doc checklists and Expert assignment without opening the case),
**68** GM admin (staff directory, pipeline purpose and access, sync health, the `/brands`
placeholder). 67 and 68 get their own specs.

## 0. Today, before this unit

- **The Case Manager's draft upload is a form halfway down the left column**, drawn only in
  `DRAFT_IN_PROGRESS` (`draftRules.mayUploadDraft`). Board cards and My drafts say *"Upload and
  submit the draft"* (`STAGE_NEXT_ACTION`) and offer no button, because the upload is multipart
  and not a `QuickAction`. From where the CM works, the option does not exist.
- **Four transitions the server admits a CM to have no button for them**: `expert/signed`,
  `expert/declined`, `expert/timed-out`, `reassign-expert` (`CaseController.java:719–756` admit
  `CASE_MANAGER`; `boardRules.ts` roles omit it).
- **The checklist is not on the case.** `CaseChecklist` is rendered only inside `ChecklistBoard`;
  the case page shows documents but not what was asked for, sent, or chased.
- **The expert card has no offer.** `GET /api/cases/{id}/expert/offer` and `PATCH …/offer/fee`
  (Unit 65) are called by nothing.
- **`PATCH /api/cases/{id}/deadline` (GM, PM) is called by nothing.**
- **`DraftPanel` still offers "Open the current draft ↗"** from `draftLink`, the field Unit 58
  replaced with Word + PDF versions. Status, history (`DraftHistory`) and upload (`UploadDraft`)
  are three separate panels about one subject.
- **No stage progress.** The only history is the vertical Notes & timeline.

## 1. Decisions inside this unit

1. **Progress strip = all 12 stages, dots on a line, under the case name** (option A of the
   mockup). Each stage: reached / current / not reached; the date first reached; `×n` when entered
   more than once; the current stage shows time so far. Click a dot → popover (§3.2).
2. **Derived on the client from `GET /cases/{id}/timeline`, which the page already loads.** Each
   `TimelineEntry.stage` is the stage *after* the event (`CaseTimelineService`), so an entry whose
   stage differs from the previous non-null one is an *entry into* that stage. No backend change,
   no second record of the same facts (the client portal's `CaseMilestones` does the same
   server-side for its five milestones).
3. **One action bar in the sticky header.** The role's stage-specific actions are primary
   buttons; stage-preserving ones (hold, refund, assign PM/PC, change deadline) go under
   **More ▾**. Upload draft joins the bar as a primary button wherever `mayUploadDraft` holds.
   `boardRules.actionsFor` stays the single table — the header only splits its answer.
4. **Overlays follow `dialog.tsx`'s existing rule**: a `Dialog` for a decision, a `Sheet` for
   working or inspecting a record without losing your place.
5. **The CM gets the four expert buttons** by adding `CASE_MANAGER` to those `QUICK_ACTIONS`
   roles — matching the server's gates, which is what the table's comment says it must do.
   `STAGE_ACCESS.CASE_MANAGER.EXPERT_SIGNING` is already `full`. No fee field for the CM on
   reassign (D59: a CM offers at the standard fee only).
6. **The case-page half of Unit 65 §4.5 is built here** (the Expert & offer panel, Edit fee,
   History). The fee field in the assign / reassign dialogs stays Unit 65's. One place per rule.
7. **The upload dialog carries an optional "Note to PM"**, posted after a successful upload as a
   case note (`POST /cases/{id}/notes`), so it lands in the timeline beside the submit. The
   upload route takes files only and is not changed. **The deadline dialog's reason works the same
   way** (`DeadlineRequest` is `{deadline}` only).

## 2. Layout — `/cases/:id`

```
┌ sticky header ───────────────────────────────────────────────────────────────┐
│ IE-2026-0412  [Stage SLA chip] [Due 4 Oct]           [Primary…] [More ▾]      │
│ Maria Gonzalez                                       Your next step: …        │
│ Expert opinion letter · PM … · CM … · PC …                                    │
│ ●──●──◉──●──○──○──○──○──○──○──○──○   (12 stages, §3)                          │
└──────────────────────────────────────────────────────────────────────────────┘
left column                                   right column (unchanged)
  Documents & checklist   [Manage checklist →]  Conversations (CaseChat)
  Draft · vN              [Upload new version]  Notes & timeline (Timeline)
  Case facts
  Expert & offer          [History]
  Strategy notes
  Expert rationale
```

- **"Your next step"**: `STAGE_NEXT_ACTION[stage]` when `STAGE_OWNER[stage] === me.role` (GM and BM
  read it as the owner's); otherwise *"Waiting on the {owner}"*. Hidden in an exception state,
  where the exception chip already says it.
- **Team line**: assigned PM / CM / PC names already on `summary`.
- **Client withheld** for the ENM stays exactly as today (`maySeeCaseContent`).

## 3. Components

### 3.1 `caseProgress.ts` (pure) + `CaseProgress.tsx`

```ts
type StepState = 'reached' | 'current' | 'not-reached'
type Step = { stage: Stage; state: StepState; firstAt: string | null; lastAt: string | null;
              visits: number; spentMs: number; lastNote: string | null }
progress(entries: TimelineEntry[], current: Stage, now: Date): Step[]
```

- The first entry (the case's `CREATED` row) is the entry into `DOC_COLLECTION`; `CaseDetail` has no
  created date of its own. Entries with a null stage are skipped.
- `spentMs` sums each visit (entry → next entry into another stage; the open visit runs to `now`).
- `lastNote` is the note on the entry that last moved the case *into* the stage (a PM return's
  reason, a QC fail's reason) — the "why back" line.
- Labels are the stage names shortened for the strip (*Docs, PM review, Drafting, Draft review,
  Ready to send, Client review, Client approved, Signing, Final QC, Ready, Delivered, Closed*).
- **Width**: the strip scrolls horizontally inside the header below ~900px and scrolls the current
  step into view on mount. At 390px it shows ~5 steps.
- Each dot is a `button` with an `aria-label` ("Drafting, current, entered twice"); the strip is an
  `ol`. Colour is not the only signal: the current step is bold and ringed.

### 3.2 Step popover (`components/ui/popover.tsx`)

Stage name · owner role · entered (latest, and "2nd time") · earlier visits with durations · total
time · *Why back* (`lastNote`, when there is one) · for the current step, the SLA chip. A step
never reached says so and nothing else.

### 3.3 `CaseHeader.tsx` (replaces `StageActions.tsx`)

Keeps everything `StageActions` does (sticky offset, SLA and exception chips, withheld name,
inline error). Adds the due chip (a button for GM/PM → Change deadline), the team line, the next-step
line, `CaseProgress`, and the split:

```ts
splitActions(actions: readonly QuickAction[]): { primary: QuickAction[]; more: QuickAction[] }
// primary: action.stages !== null (declared from a stage) or action.requiresException (an exit)
// more:    everything else — the stage-preserving actions (stages === null, no exception)
```

The **More ▾** menu is the existing `popover.tsx`. Upload draft is rendered first in the primary
group when `mayUploadDraft(stage, role)`.

### 3.4 `UploadDraftDialog.tsx` (replaces `UploadDraft.tsx`) — Dialog

Title *Upload draft v{n+1}*, subtitle *Submits to PM review*. Word (.docx) and PDF inputs (drop or
choose), optional *Note to PM*. **Upload & submit** → `uploadDraft` → if a note, `postNote` → close →
reload. The note is posted only after the upload succeeds; if the note fails the draft stands and
the dialog says *"Draft submitted; the note was not saved"* with the text still in the box.
Existing error copy (real Word/PDF, 15MB) kept.

Opened from: the header button, the Draft panel's *Upload new version*, **the CM's board card**
(`CaseCard`, when `mayUploadDraft`) and **each My drafts row**. Same component, `caseId` +
`onUploaded` props, so the two queue entry points are two call sites and not a Unit 67 dependency.

### 3.5 `DraftPanel.tsx` — merged

Status chips (PM review, Client review) + `DraftHistory` inside one panel + *Upload new version*
link when `mayUploadDraft`. **`draftLink` and "Open the current draft ↗" are removed**; the
versions' own docx / pdf links replace them.

### 3.6 `DocumentsPanel` + `ChecklistSheet.tsx` — Sheet

The panel gains the checklist summary: each item with its status chip, *"n items wait for the next
Send"* (`ChecklistView.unsent`), *"chased {when}"* (`lastChasedAt`), read with
`fetchChecklist`. **Manage checklist →** opens a right `Sheet` holding the existing
`CaseChecklist` (add item, set status, Send, Chase) unchanged. Shown to the roles the server's
`COORDINATION` gate admits (GM, BM, PC, CM); others see the summary only. `onCaseLeftTheStage`
reloads the page, since docs-complete moves the case.

### 3.7 `ExpertCard.tsx` — Expert & offer, and `OfferHistorySheet.tsx` — Sheet

Reads `GET /cases/{id}/expert/offer` (null → *"Not offered yet"*). Shows the expert, the offer
outcome chip, **Fee for this case** with *"frozen at acceptance"* once accepted, or *"Fee not set"*.
**Edit fee** (GM / PM / PC / ENM, only while the outcome is open) → small Dialog, one amount field,
`PATCH …/offer/fee`, a 409 reloads and says the offer changed. **History** → Sheet listing
`OfferView.log`: *who · date and time · what · old → new*. Signed letter list and the portal-read
line stay.

### 3.8 `DeadlineDialog.tsx` — Dialog

GM / PM. Native `<input type="date">`, optional reason posted as a note after the save.
`PATCH /cases/{id}/deadline` with end-of-day in the browser's timezone.

### 3.9 `boardRules.ts`

`CASE_MANAGER` added to `expert/signed`, `expert/declined`, `expert/timed-out`, `reassign-expert`.

## 4. Files

New: `features/case/caseProgress.ts`, `CaseProgress.tsx`, `CaseHeader.tsx`,
`UploadDraftDialog.tsx`, `ChecklistSheet.tsx`, `OfferHistorySheet.tsx`, `DeadlineDialog.tsx`.
Changed: `CaseDetail.tsx`, `DraftPanel.tsx`, `DocumentsPanel.tsx`, `ExpertCard.tsx`,
`caseApi.ts` (`fetchOffer`, `editOfferFee`, `changeDeadline`), `board/boardRules.ts`,
`board/CaseCard.tsx`, `queues/MyDraftsPage.tsx`.
Deleted: `StageActions.tsx`, `UploadDraft.tsx` (absorbed).

## 5. Tests — one per rule

- `caseProgress.test.ts`: straight run; a PM return (Drafting ×2, Draft review reached but not
  current); null-stage rows skipped; open visit runs to `now`; `lastNote` is the returning note.
- `boardRules.test.ts`: a CM at `EXPERT_SIGNING` is offered signed / declined / timed-out; a CM in
  `EXPERT_DECLINED_REMATCHING` is offered reassign.
- `splitActions` test: stage actions primary, stage-preserving ones in More, exception exits primary.
- `UploadDraftDialog.test.tsx`: note posted only after a successful upload; note failure keeps the
  draft and the text.
- `npx vitest run` and `npx tsc -b` green; the four nav/route guard tests untouched.
- **Chrome check at 1440 and 390 wide** as a CM (drafting, returned draft), PC (doc collection,
  send checklist), PM (deadline, QC), ENM (offer, client withheld).

## 6. Not built, by choice

- A horizontal *replacement* for Notes & timeline — the strip summarises, the trail stays the record.
- A backend progress endpoint — the timeline already carries every entry.
- Editing the fee after acceptance, or a CM fee field (D59).
- Queue-wide actions beyond the upload entry points (Unit 67); GM admin screens (Unit 68).
- Client and expert portals — out of this audit (production staff side only).
