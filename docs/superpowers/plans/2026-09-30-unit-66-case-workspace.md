# Unit 66 — Case Workspace Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give every production role a case page they can act from — a 12-stage progress strip after the case ID, one header action bar, and Upload draft / Checklist / Offer / Deadline overlays — using only routes that already exist.

**Architecture:** Frontend only (`frontend/`). Every rule is a pure function in a `*Rules.ts`/`caseProgress.ts` file with a vitest test (the SPA has no DOM test setup; this unit adds no dependency). Components are thin over those rules and over the existing Radix wrappers in `components/ui/` (`dialog.tsx` is a protected path — use it, never edit it). The case page reloads case + timeline after every write, as it does today.

**Tech Stack:** React 19, TypeScript ~6, Vite 8, vitest 4, radix-ui, Tailwind 4 + tokens in `src/styles/tokens.css`.

**Spec:** `context/specs/66-case-workspace.md`

## Global Constraints

- No new npm dependency. No backend change, no migration.
- No hardcoded hex in components; colours are `var(--…)` tokens (`tokens.css`). RAG tokens for status only.
- `boardRules.actionsFor` stays the single table of legal actions; the header only splits its answer.
- `components/ui/dialog.tsx` and `popover.tsx` are used as is.
- Dialog = a decision; Sheet = working / inspecting a record (`dialog.tsx` header).
- Operational empty-state copy, never "No data".
- Run from `frontend/`: `npx vitest run`, `npx tsc -b` (never `tsc --noEmit`), `npx oxlint`.
- Commit messages: `feat(unit-66): …` ending with `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`.

## Review Focus

1. **Legacy timeline rows with `stage: null`** — the strip must skip them, not start a phantom visit or crash. (Task 1 test.)
2. **A case whose timeline never shows the current stage** (rows written before snapshots carried a stage) — the current dot must still be drawn as current. (Task 1 test.)
3. **Upload succeeds, note fails** — the draft must stand, the page must not claim failure, and the note text must survive for a retry. (Task 3 test.)
4. **Fee edit racing an acceptance** — the server answers 409; the card must reload the offer and say why, not leave a stale editable fee. (Task 4: `mayEditFee` test + 409 handling step.)
5. **Deadline picked in a non-UTC timezone** — saving "4 Oct" must mean end of 4 Oct locally, not 3 Oct in UTC. (Task 4 `endOfDayIso` test.)

---

## File map

| File | Responsibility |
|---|---|
| `src/features/case/caseProgress.ts` (new) | Pure: timeline entries → 12 steps; duration formatting |
| `src/features/case/caseProgress.test.ts` (new) | Its tests |
| `src/features/case/caseRules.ts` (new) | Pure: `splitActions`, `nextStep`, `mayEditFee`, `maySetDeadline`, `mayManageChecklist`, `endOfDayIso`, `toDateInput` |
| `src/features/case/caseRules.test.ts` (new) | Its tests |
| `src/features/case/draftRules.ts` (modify) | + `submitDraft` sequencing |
| `src/features/board/boardRules.ts` (modify) | CM on three expert actions |
| `src/features/case/caseApi.ts` (modify) | + `OfferView`, `fetchOffer`, `editOfferFee`, `changeDeadline`; − `draftLink` field |
| `src/features/case/UploadDraftDialog.tsx` (new, replaces `UploadDraft.tsx`) | Dialog |
| `src/features/case/DeadlineDialog.tsx` (new) | Dialog |
| `src/features/case/OfferHistorySheet.tsx` (new) | Sheet |
| `src/features/case/ChecklistSheet.tsx` (new) | Sheet around existing `CaseChecklist` |
| `src/features/case/CaseProgress.tsx` (new) | The strip + step popover |
| `src/features/case/CaseHeader.tsx` (new, replaces `StageActions.tsx`) | Sticky header |
| `DraftPanel.tsx`, `DocumentsPanel.tsx`, `ExpertCard.tsx`, `CaseDetail.tsx`, `queues/MyDraftsPage.tsx` (modify) | Wiring |

---

### Task 1: Progress derivation (`caseProgress.ts`)

**Files:**
- Create: `frontend/src/features/case/caseProgress.ts`
- Test: `frontend/src/features/case/caseProgress.test.ts`

**Interfaces:**
- Consumes: `TimelineEntry` from `./caseApi` (`{ at, actorName, action, stage: string | null, exceptionState, note }`, oldest first); `Stage` from `../board/boardRules`.
- Produces:
  ```ts
  export const STAGE_ORDER: readonly Stage[]
  export const STAGE_SHORT: Record<Stage, string>
  export type StepState = 'reached' | 'current' | 'not-reached'
  export type Step = { stage: Stage; state: StepState; firstAt: string | null; lastAt: string | null; visits: number; spentMs: number; lastNote: string | null }
  export function progress(entries: readonly TimelineEntry[], current: Stage, now: Date): Step[]
  export function formatSpan(ms: number): string
  ```

- [ ] **Step 1: Write the failing test**

```ts
// frontend/src/features/case/caseProgress.test.ts
import { describe, expect, it } from 'vitest'
import type { TimelineEntry } from './caseApi'
import { STAGE_ORDER, formatSpan, progress } from './caseProgress'

const e = (at: string, stage: string | null, note: string | null = null): TimelineEntry => ({
  at, actorName: 'x', action: 'STAGE_CHANGED', stage, exceptionState: 'NONE', note,
})
const H = 3_600_000

describe('progress', () => {
  it('draws all twelve stages in pipeline order', () => {
    const steps = progress([e('2026-09-01T00:00:00Z', 'DOC_COLLECTION')], 'DOC_COLLECTION', new Date('2026-09-01T05:00:00Z'))
    expect(steps.map((s) => s.stage)).toEqual(STAGE_ORDER)
    expect(steps).toHaveLength(12)
  })

  it('marks a straight run reached, current, then not reached, and times the open visit to now', () => {
    const steps = progress(
      [e('2026-09-01T00:00:00Z', 'DOC_COLLECTION'), e('2026-09-01T10:00:00Z', 'PM_REVIEW')],
      'PM_REVIEW',
      new Date('2026-09-01T12:00:00Z'),
    )
    expect(steps[0]).toMatchObject({ state: 'reached', visits: 1, spentMs: 10 * H })
    expect(steps[1]).toMatchObject({ state: 'current', visits: 1, spentMs: 2 * H })
    expect(steps[2]).toMatchObject({ state: 'not-reached', visits: 0, firstAt: null })
  })

  it('counts a PM return as a second visit to Drafting and keeps the returning note', () => {
    const steps = progress(
      [
        e('2026-09-01T00:00:00Z', 'DOC_COLLECTION'),
        e('2026-09-02T00:00:00Z', 'PM_REVIEW'),
        e('2026-09-03T00:00:00Z', 'DRAFT_IN_PROGRESS'),
        e('2026-09-04T00:00:00Z', 'DRAFT_REVIEW'),
        e('2026-09-05T00:00:00Z', 'DRAFT_IN_PROGRESS', 'dates on p.2 wrong'),
      ],
      'DRAFT_IN_PROGRESS',
      new Date('2026-09-05T06:00:00Z'),
    )
    const drafting = steps[2]
    expect(drafting).toMatchObject({ state: 'current', visits: 2, firstAt: '2026-09-03T00:00:00Z', lastAt: '2026-09-05T00:00:00Z', lastNote: 'dates on p.2 wrong' })
    expect(drafting.spentMs).toBe(24 * H + 6 * H)
    expect(steps[3]).toMatchObject({ state: 'reached', visits: 1 })
  })

  it('skips rows with no stage and rows that stay in the same stage', () => {
    const steps = progress(
      [e('2026-09-01T00:00:00Z', 'DOC_COLLECTION'), e('2026-09-01T01:00:00Z', null), e('2026-09-01T02:00:00Z', 'DOC_COLLECTION', 'chased')],
      'DOC_COLLECTION',
      new Date('2026-09-01T03:00:00Z'),
    )
    expect(steps[0]).toMatchObject({ visits: 1, spentMs: 3 * H, lastNote: null })
  })

  it('draws the current stage as current even when no row ever entered it', () => {
    const steps = progress([], 'FINAL_QC', new Date())
    expect(steps.find((s) => s.stage === 'FINAL_QC')?.state).toBe('current')
  })

  it('stops the clock once the case is closed', () => {
    const steps = progress([e('2026-09-01T00:00:00Z', 'CLOSED')], 'CLOSED', new Date('2026-09-09T00:00:00Z'))
    expect(steps[11].spentMs).toBe(0)
  })
})

describe('formatSpan', () => {
  it('reads as the largest two units', () => {
    expect(formatSpan(0)).toBe('<1m')
    expect(formatSpan(12 * 60_000)).toBe('12m')
    expect(formatSpan(5 * H + 20 * 60_000)).toBe('5h 20m')
    expect(formatSpan(2 * 24 * H + 3 * H)).toBe('2d 3h')
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx vitest run src/features/case/caseProgress.test.ts`
Expected: FAIL — cannot resolve `./caseProgress`.

- [ ] **Step 3: Write minimal implementation**

```ts
// frontend/src/features/case/caseProgress.ts
import type { Stage } from '../board/boardRules'
import type { TimelineEntry } from './caseApi'

/**
 * The case's path through the twelve stages, derived from the timeline the page already loads
 * (Unit 66). Each entry's `stage` is the stage *after* the event (`CaseTimelineService`), so a
 * change of stage between consecutive entries is an entry into that stage. No second record.
 */

export const STAGE_ORDER: readonly Stage[] = [
  'DOC_COLLECTION', 'PM_REVIEW', 'DRAFT_IN_PROGRESS', 'DRAFT_REVIEW', 'READY_TO_SEND', 'CLIENT_REVIEW',
  'CLIENT_APPROVAL', 'EXPERT_SIGNING', 'FINAL_QC', 'READY_TO_DELIVER', 'DELIVERED', 'CLOSED',
]

export const STAGE_SHORT: Record<Stage, string> = {
  DOC_COLLECTION: 'Docs', PM_REVIEW: 'PM review', DRAFT_IN_PROGRESS: 'Drafting', DRAFT_REVIEW: 'Draft review',
  READY_TO_SEND: 'Ready to send', CLIENT_REVIEW: 'Client review', CLIENT_APPROVAL: 'Client approved',
  EXPERT_SIGNING: 'Signing', FINAL_QC: 'Final QC', READY_TO_DELIVER: 'Ready', DELIVERED: 'Delivered', CLOSED: 'Closed',
}

export type StepState = 'reached' | 'current' | 'not-reached'
export type Step = {
  stage: Stage
  state: StepState
  firstAt: string | null
  lastAt: string | null
  visits: number
  spentMs: number
  /** The note on the entry that last moved the case INTO this stage — a return's reason. */
  lastNote: string | null
}

export function progress(entries: readonly TimelineEntry[], current: Stage, now: Date): Step[] {
  const steps = new Map<Stage, Step>(
    STAGE_ORDER.map((stage) => [
      stage,
      { stage, state: 'not-reached', firstAt: null, lastAt: null, visits: 0, spentMs: 0, lastNote: null },
    ]),
  )
  let open: { stage: Stage; at: number } | null = null
  for (const entry of entries) {
    const step = entry.stage ? steps.get(entry.stage as Stage) : undefined
    if (!step || open?.stage === step.stage) continue
    const at = Date.parse(entry.at)
    if (open) steps.get(open.stage)!.spentMs += at - open.at
    step.visits += 1
    step.firstAt ??= entry.at
    step.lastAt = entry.at
    step.lastNote = entry.note
    open = { stage: step.stage, at }
  }
  if (open && open.stage !== 'CLOSED') steps.get(open.stage)!.spentMs += now.getTime() - open.at

  return STAGE_ORDER.map((stage) => {
    const step = steps.get(stage)!
    return { ...step, state: stage === current ? 'current' : step.visits > 0 ? 'reached' : 'not-reached' }
  })
}

export function formatSpan(ms: number): string {
  const minutes = Math.floor(ms / 60_000)
  if (minutes < 1) return '<1m'
  const days = Math.floor(minutes / 1440)
  const hours = Math.floor((minutes % 1440) / 60)
  const mins = minutes % 60
  if (days > 0) return hours > 0 ? `${days}d ${hours}h` : `${days}d`
  if (hours > 0) return mins > 0 ? `${hours}h ${mins}m` : `${hours}h`
  return `${mins}m`
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && npx vitest run src/features/case/caseProgress.test.ts`
Expected: PASS (7 tests).

- [ ] **Step 5: Commit**

```bash
git add frontend/src/features/case/caseProgress.ts frontend/src/features/case/caseProgress.test.ts
git commit -m "feat(unit-66): derive the case's stage progress from its timeline"
```

---

### Task 2: Header rules + the CM's expert actions

**Files:**
- Create: `frontend/src/features/case/caseRules.ts`, `frontend/src/features/case/caseRules.test.ts`
- Modify: `frontend/src/features/board/boardRules.ts` (the `expert/signed`, `expert/declined`, `expert/timed-out` entries in `QUICK_ACTIONS`)
- Test: `frontend/src/features/board/boardRules.test.ts`

**Interfaces:**
- Consumes: `QuickAction`, `Stage`, `ExceptionState`, `STAGE_OWNER`, `STAGE_NEXT_ACTION` from `../board/boardRules`; `Role`, `ROLE_LABELS` from `../../lib/session`.
- Produces:
  ```ts
  export function splitActions(actions: readonly QuickAction[]): { primary: QuickAction[]; more: QuickAction[] }
  export function nextStep(stage: Stage, exception: ExceptionState, role: Role): string | null
  export function mayEditFee(role: Role, outcome: string | null): boolean
  export function maySetDeadline(role: Role): boolean
  export function mayManageChecklist(role: Role): boolean
  export function endOfDayIso(date: string): string      // 'YYYY-MM-DD' → ISO instant, 23:59:59.999 local
  export function toDateInput(iso: string | null): string // ISO → local 'YYYY-MM-DD', '' for null
  ```

- [ ] **Step 1: Write the failing tests**

```ts
// frontend/src/features/case/caseRules.test.ts
import { describe, expect, it } from 'vitest'
import { QUICK_ACTIONS } from '../board/boardRules'
import { endOfDayIso, mayEditFee, mayManageChecklist, maySetDeadline, nextStep, splitActions, toDateInput } from './caseRules'

const byPath = (path: string) => QUICK_ACTIONS.find((a) => a.path === path)!

describe('splitActions', () => {
  it('puts stage transitions and exception exits first, stage-preserving actions under More', () => {
    const { primary, more } = splitActions([byPath('hold'), byPath('send-to-expert'), byPath('resume'), byPath('refund/request')])
    expect(primary.map((a) => a.path)).toEqual(['send-to-expert', 'resume'])
    expect(more.map((a) => a.path)).toEqual(['hold', 'refund/request'])
  })
})

describe('nextStep', () => {
  it("tells the owner what to do and everyone else who it is waiting on", () => {
    expect(nextStep('DRAFT_IN_PROGRESS', 'NONE', 'CASE_MANAGER')).toBe('Your next step: upload and submit the draft')
    expect(nextStep('DRAFT_IN_PROGRESS', 'NONE', 'PROJECT_COORDINATOR')).toBe('Waiting on the Case Manager')
    expect(nextStep('DRAFT_IN_PROGRESS', 'NONE', 'GM')).toBe('Case Manager: upload and submit the draft')
  })
  it('says nothing in an exception state or once nobody owes anything', () => {
    expect(nextStep('DRAFT_IN_PROGRESS', 'ON_HOLD_AWAITING_CLIENT', 'CASE_MANAGER')).toBeNull()
    expect(nextStep('DELIVERED', 'NONE', 'PROJECT_COORDINATOR')).toBeNull()
  })
})

describe('permissions mirrored from the server', () => {
  it('lets the fee setters edit only an open offer (OfferFeeController, D59)', () => {
    expect(mayEditFee('PROJECT_COORDINATOR', 'OFFERED')).toBe(true)
    expect(mayEditFee('EXPERT_NETWORK_MANAGER', 'OFFERED')).toBe(true)
    expect(mayEditFee('PROJECT_MANAGER', 'ACCEPTED')).toBe(false)
    expect(mayEditFee('CASE_MANAGER', 'OFFERED')).toBe(false)
    expect(mayEditFee('BRAND_MANAGER', 'OFFERED')).toBe(false)
    expect(mayEditFee('GM', null)).toBe(false)
  })
  it('matches PATCH /deadline (GM, PM) and the checklist COORDINATION gate (GM, BM, PC, CM)', () => {
    expect((['GM', 'PROJECT_MANAGER'] as const).every((r) => maySetDeadline(r))).toBe(true)
    expect(maySetDeadline('PROJECT_COORDINATOR')).toBe(false)
    expect((['GM', 'BRAND_MANAGER', 'PROJECT_COORDINATOR', 'CASE_MANAGER'] as const).every((r) => mayManageChecklist(r))).toBe(true)
    expect(mayManageChecklist('PROJECT_MANAGER')).toBe(false)
  })
})

describe('deadline dates', () => {
  it('saves the end of the chosen day in local time', () => {
    const saved = new Date(endOfDayIso('2026-10-04'))
    expect([saved.getFullYear(), saved.getMonth(), saved.getDate(), saved.getHours(), saved.getMinutes()]).toEqual([2026, 9, 4, 23, 59])
  })
  it('round-trips through the date input', () => {
    expect(toDateInput(endOfDayIso('2026-10-04'))).toBe('2026-10-04')
    expect(toDateInput(null)).toBe('')
  })
})
```

Append to `frontend/src/features/board/boardRules.test.ts` (it already defines `paths(stage, role, exceptionState)`):

```ts
describe('the Case Manager on expert signing (Unit 66)', () => {
  it('offers the three signing answers the server admits a CM to', () => {
    const offered = paths('EXPERT_SIGNING', 'CASE_MANAGER')
    expect(offered).toEqual(expect.arrayContaining(['expert/signed', 'expert/declined', 'expert/timed-out']))
  })
  it('still does not offer reassign — its expert picker is gated away from the CM', () => {
    expect(paths('EXPERT_SIGNING', 'CASE_MANAGER', 'EXPERT_DECLINED_REMATCHING')).not.toContain('reassign-expert')
  })
})
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd frontend && npx vitest run src/features/case/caseRules.test.ts src/features/board/boardRules.test.ts`
Expected: FAIL — `./caseRules` unresolved; `expert/signed` not offered to `CASE_MANAGER`.

- [ ] **Step 3: Implement**

```ts
// frontend/src/features/case/caseRules.ts
import {
  STAGE_NEXT_ACTION,
  STAGE_OWNER,
  type ExceptionState,
  type QuickAction,
  type Stage,
} from '../board/boardRules'
import { ROLE_LABELS, type Role } from '../../lib/session'

/**
 * The case page's small decisions (Unit 66), pure so they are tested without a DOM. Each role list
 * mirrors a server gate named beside it; the server still decides.
 */

/** Transitions out of the stage and ways out of an exception are the header's buttons; the rest go under More. */
export function splitActions(actions: readonly QuickAction[]): { primary: QuickAction[]; more: QuickAction[] } {
  const primary = actions.filter((action) => action.stages !== null || action.requiresException !== undefined)
  return { primary, more: actions.filter((action) => !primary.includes(action)) }
}

export function nextStep(stage: Stage, exception: ExceptionState, role: Role): string | null {
  const owner = STAGE_OWNER[stage]
  if (exception !== 'NONE' || !owner) return null
  const todo = STAGE_NEXT_ACTION[stage].toLowerCase()
  if (owner === role) return `Your next step: ${todo}`
  if (role === 'GM' || role === 'BRAND_MANAGER') return `${ROLE_LABELS[owner]}: ${todo}`
  return `Waiting on the ${ROLE_LABELS[owner]}`
}

/** `OfferFeeController` PATCH …/offer/fee: GM, PM, PC, ENM, and only while the offer is open (D59). */
const FEE_SETTERS: readonly Role[] = ['GM', 'PROJECT_MANAGER', 'PROJECT_COORDINATOR', 'EXPERT_NETWORK_MANAGER']
export function mayEditFee(role: Role, outcome: string | null): boolean {
  return outcome === 'OFFERED' && FEE_SETTERS.includes(role)
}

/** `CaseController` PATCH /{id}/deadline: GM or PM. */
export function maySetDeadline(role: Role): boolean {
  return role === 'GM' || role === 'PROJECT_MANAGER'
}

/** `ChecklistController.COORDINATION`: GM, BM, PC, CM. */
const COORDINATION: readonly Role[] = ['GM', 'BRAND_MANAGER', 'PROJECT_COORDINATOR', 'CASE_MANAGER']
export function mayManageChecklist(role: Role): boolean {
  return COORDINATION.includes(role)
}

/** A date-only choice means "by the end of that day where I am", so it is parsed as local time. */
export function endOfDayIso(date: string): string {
  return new Date(`${date}T23:59:59.999`).toISOString()
}

export function toDateInput(iso: string | null): string {
  if (!iso) return ''
  const d = new Date(iso)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}
```

In `boardRules.ts`, change the three entries' `roles` (and extend the comment above `expert/signed`):

```ts
  // The CM is admitted by `CaseController` on all three (Unit 66) — they own EXPERT_SIGNING. Not on
  // `reassign-expert` below: its picker reads GET /api/experts, which the CM may not.
  {
    path: 'expert/signed',
    label: 'Expert signed',
    roles: ['PROJECT_MANAGER', 'EXPERT_NETWORK_MANAGER', 'CASE_MANAGER'],
    stages: ['EXPERT_SIGNING'],
  },
  {
    path: 'expert/declined',
    label: 'Expert declined',
    roles: ['PROJECT_MANAGER', 'EXPERT_NETWORK_MANAGER', 'CASE_MANAGER'],
    stages: ['EXPERT_SIGNING'],
    fields: REASON,
  },
```

and on `expert/timed-out`: `roles: ['BRAND_MANAGER', 'PROJECT_MANAGER', 'CASE_MANAGER'],`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd frontend && npx vitest run src/features/case/caseRules.test.ts src/features/board/boardRules.test.ts`
Expected: PASS. If an existing `boardRules.test.ts` assertion pinned the old CM list, update it to the new server-matching list and say so in the commit body.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/features/case/caseRules.ts frontend/src/features/case/caseRules.test.ts frontend/src/features/board/boardRules.ts frontend/src/features/board/boardRules.test.ts
git commit -m "feat(unit-66): header rules, and the CM answers expert signing as the server allows"
```

---

### Task 3: Upload draft dialog (+ note to PM), in the Draft panel and My drafts

**Files:**
- Modify: `frontend/src/features/case/draftRules.ts`, `draftRules.test.ts`
- Create: `frontend/src/features/case/UploadDraftDialog.tsx`
- Delete: `frontend/src/features/case/UploadDraft.tsx`
- Modify: `frontend/src/features/case/DraftPanel.tsx`, `frontend/src/features/case/CaseDetail.tsx`, `frontend/src/features/queues/MyDraftsPage.tsx`, `frontend/src/features/case/caseApi.ts` (remove `draftLink`)

**Interfaces:**
- Consumes: `uploadDraft(caseId, docx, pdf)`, `postNote(caseId, note)` from `./caseApi`; `DialogRoot`, `DialogTrigger`, `DialogContent` from `../../components/ui/dialog`; `mayUploadDraft` from `./draftRules`.
- Produces:
  ```ts
  // draftRules.ts
  export type DraftSteps = { upload: () => Promise<void>; note: (text: string) => Promise<void> }
  export function submitDraft(steps: DraftSteps, note: string): Promise<'submitted' | 'note-failed'>
  // UploadDraftDialog.tsx (default export)
  props: { caseId: string; nextVersion: number; trigger: React.ReactElement; onUploaded: () => void }
  // DraftPanel.tsx (default export) — new props
  props: { detail: CaseDetail; role: Role; onUploaded: () => void }
  ```

- [ ] **Step 1: Write the failing test** (append to `draftRules.test.ts`; change its import to `import { mayComment, mayUploadDraft, submitDraft } from './draftRules'`)

```ts
describe('submitDraft', () => {
  it('posts the note only after the upload succeeds', async () => {
    const calls: string[] = []
    const result = await submitDraft(
      { upload: async () => { calls.push('upload') }, note: async (t) => { calls.push(`note:${t}`) } },
      '  see p.2  ',
    )
    expect(calls).toEqual(['upload', 'note:see p.2'])
    expect(result).toBe('submitted')
  })

  it('sends no note when the box is blank', async () => {
    const calls: string[] = []
    await submitDraft({ upload: async () => { calls.push('upload') }, note: async () => { calls.push('note') } }, '   ')
    expect(calls).toEqual(['upload'])
  })

  it('never posts the note when the upload fails, and lets the failure reach the dialog', async () => {
    let noted = false
    await expect(
      submitDraft({ upload: async () => { throw new Error('415') }, note: async () => { noted = true } }, 'hi'),
    ).rejects.toThrow('415')
    expect(noted).toBe(false)
  })

  it('keeps the draft when only the note fails', async () => {
    const result = await submitDraft({ upload: async () => {}, note: async () => { throw new Error('500') } }, 'hi')
    expect(result).toBe('note-failed')
  })
})
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd frontend && npx vitest run src/features/case/draftRules.test.ts`
Expected: FAIL — `submitDraft` is not exported.

- [ ] **Step 3: Implement `submitDraft`** (append to `draftRules.ts`)

```ts
export type DraftSteps = { upload: () => Promise<void>; note: (text: string) => Promise<void> }

/**
 * Upload, then the optional note to the PM (Unit 66). The upload route takes files only, so the note
 * is a case note written after it — never before, or a failed upload would leave a note about a draft
 * that does not exist. An upload failure throws to the dialog; a note failure does not undo the draft.
 */
export async function submitDraft(steps: DraftSteps, note: string): Promise<'submitted' | 'note-failed'> {
  await steps.upload()
  const text = note.trim()
  if (!text) return 'submitted'
  try {
    await steps.note(text)
    return 'submitted'
  } catch {
    return 'note-failed'
  }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `cd frontend && npx vitest run src/features/case/draftRules.test.ts`
Expected: PASS.

- [ ] **Step 5: Create the dialog**

```tsx
// frontend/src/features/case/UploadDraftDialog.tsx
import { useState, type ReactElement } from 'react'
import { DialogContent, DialogRoot, DialogTrigger } from '../../components/ui/dialog'
import { postNote, uploadDraft } from './caseApi'
import { submitDraft } from './draftRules'

/**
 * The next draft version as Word + PDF, plus an optional note to the PM (Unit 66). One component for
 * every place a CM starts an upload, each passing its own `trigger`. Submitting moves the case to
 * PM review, which is why this is a Dialog and not a Sheet.
 */
export default function UploadDraftDialog({
  caseId,
  nextVersion,
  trigger,
  onUploaded,
}: {
  caseId: string
  nextVersion: number
  trigger: ReactElement
  onUploaded: () => void
}) {
  const [open, setOpen] = useState(false)
  const [docx, setDocx] = useState<File | null>(null)
  const [pdf, setPdf] = useState<File | null>(null)
  const [note, setNote] = useState('')
  const [state, setState] = useState<'idle' | 'sending' | 'failed' | 'note-failed'>('idle')

  function reset() {
    setDocx(null)
    setPdf(null)
    setNote('')
    setState('idle')
  }

  function close() {
    const uploaded = state === 'note-failed'
    setOpen(false)
    reset()
    if (uploaded) onUploaded()
  }

  async function submit(event: React.FormEvent) {
    event.preventDefault()
    if (!docx || !pdf) return
    setState('sending')
    try {
      const result = await submitDraft({ upload: () => uploadDraft(caseId, docx, pdf), note: (t) => postNote(caseId, t) }, note)
      if (result === 'note-failed') {
        setState('note-failed')
        return
      }
      setOpen(false)
      reset()
      onUploaded()
    } catch {
      setState('failed')
    }
  }

  async function retryNote() {
    try {
      await postNote(caseId, note.trim())
    } catch {
      return // still 'note-failed'; the text stays in the box
    }
    setOpen(false)
    reset()
    onUploaded()
  }

  return (
    <DialogRoot open={open} onOpenChange={(next) => (next ? setOpen(true) : close())}>
      <DialogTrigger asChild>{trigger}</DialogTrigger>
      <DialogContent title={`Upload draft v${nextVersion}`} description="Submits the draft to PM review.">
        {state === 'note-failed' ? (
          <div className="flex flex-col gap-3 text-sm">
            <p role="alert" style={{ color: 'var(--status-amber)' }}>
              Draft submitted; the note was not saved.
            </p>
            <textarea value={note} onChange={(e) => setNote(e.target.value)} rows={3} className="rounded-md border p-2"
              style={{ borderColor: 'var(--border-default)' }} />
            <div className="flex justify-end gap-2">
              <button type="button" onClick={close} className="rounded-md px-3 py-1.5" style={{ background: 'var(--bg-raised)' }}>
                Close
              </button>
              <button type="button" onClick={() => void retryNote()} className="rounded-md px-3 py-1.5 font-medium"
                style={{ background: 'var(--accent-primary)', color: 'var(--text-on-accent, white)' }}>
                Save note
              </button>
            </div>
          </div>
        ) : (
          <form onSubmit={submit} className="flex flex-col gap-3 text-sm">
            <label className="flex flex-col gap-1">
              <span style={{ color: 'var(--text-muted)' }}>Word file (.docx)</span>
              <input type="file" accept=".docx" required onChange={(e) => setDocx(e.target.files?.[0] ?? null)} />
            </label>
            <label className="flex flex-col gap-1">
              <span style={{ color: 'var(--text-muted)' }}>PDF of the same draft</span>
              <input type="file" accept="application/pdf,.pdf" required onChange={(e) => setPdf(e.target.files?.[0] ?? null)} />
            </label>
            <label className="flex flex-col gap-1">
              <span style={{ color: 'var(--text-muted)' }}>Note to PM (optional)</span>
              <textarea value={note} onChange={(e) => setNote(e.target.value)} rows={3} className="rounded-md border p-2"
                style={{ borderColor: 'var(--border-default)' }} />
            </label>
            {state === 'failed' && (
              <p role="alert" style={{ color: 'var(--status-red)' }}>
                The draft was not uploaded. Check both files are a real Word document and PDF under 15MB, then try again.
              </p>
            )}
            <div className="flex justify-end gap-2">
              <button type="button" onClick={close} className="rounded-md px-3 py-1.5" style={{ background: 'var(--bg-raised)' }}>
                Cancel
              </button>
              <button type="submit" disabled={!docx || !pdf || state === 'sending'} className="rounded-md px-3 py-1.5 font-medium disabled:opacity-40"
                style={{ background: 'var(--accent-primary)', color: 'var(--text-on-accent, white)' }}>
                {state === 'sending' ? 'Uploading…' : 'Upload & submit'}
              </button>
            </div>
          </form>
        )}
      </DialogContent>
    </DialogRoot>
  )
}
```

Exactly one `onUploaded()` per successful upload: from `submit`, from `retryNote`, or from `close()` when the dialog is dismissed in the `note-failed` state — never two of them.

- [ ] **Step 6: Merge the Draft panel**

Replace `DraftPanel.tsx`'s signature and the `draftLink` paragraph (the whole `{draftVersionCount > 0 && (<p className="mt-3 text-sm">…</p>)}` block) so the panel owns status, history and upload:

```tsx
import type { Role } from '../../lib/session'
import type { CaseDetail } from './caseApi'
import DraftHistory from './DraftHistory'
import UploadDraftDialog from './UploadDraftDialog'
import { mayUploadDraft } from './draftRules'
// …TONE, approvalTone, APPROVAL_LABEL unchanged…

export default function DraftPanel({ detail, role, onUploaded }: { detail: CaseDetail; role: Role; onUploaded: () => void }) {
  const { id, currentStage, pmApprovalStatus, clientApprovalStatus, draftVersionCount } = detail.summary
  return (
    <section className="rounded-lg border p-4" style={{ background: 'var(--bg-surface)', borderColor: 'var(--border-default)' }}>
      <div className="flex items-baseline justify-between gap-2">
        <h2 className="text-sm font-semibold tracking-tight">
          Draft{draftVersionCount > 0 ? ` · v${draftVersionCount}` : ''}
        </h2>
        {mayUploadDraft(currentStage, role) ? (
          <UploadDraftDialog caseId={id} nextVersion={draftVersionCount + 1} onUploaded={onUploaded}
            trigger={<button type="button" className="text-xs font-medium" style={{ color: 'var(--accent-primary)' }}>Upload new version</button>} />
        ) : (
          <span className="font-num text-xs tabular-nums" style={{ color: 'var(--text-muted)' }}>
            {draftVersionCount === 0 ? 'no draft yet' : `version ${draftVersionCount}`}
          </span>
        )}
      </div>
      <dl className="mt-3 space-y-2">
        <Row label="PM review" status={pmApprovalStatus} />
        <Row label="Client review" status={clientApprovalStatus} />
      </dl>
      <div className="mt-3">
        <DraftHistory caseId={id} clientApprovalStatus={clientApprovalStatus} reloadKey={draftVersionCount} />
      </div>
    </section>
  )
}
```

If `DraftHistory` renders its own `<section>` border, change its outer element to a plain `<div>` so the panel is not boxed twice (read the file; keep its content unchanged).

In `caseApi.ts` remove the `draftLink: string | null` field and its doc comment from `CaseDetail` (the server may still send it; the SPA stops reading it). Run `grep -rn draftLink frontend/src` — expect zero hits after this task.

- [ ] **Step 7: Wire the case page**

In `CaseDetail.tsx`: remove the imports of `UploadDraft`, `DraftHistory`, `mayUploadDraft`; replace the three JSX blocks (`<DraftPanel …/>`, the `mayUploadDraft && <UploadDraft …/>`, `<DraftHistory …/>`) with:

```tsx
<DraftPanel detail={detail} role={me.role} onUploaded={() => void load()} />
```

Delete `frontend/src/features/case/UploadDraft.tsx`.

- [ ] **Step 8: My drafts row button**

In `MyDraftsPage.tsx`: `Row` gains `onUploaded: () => void`; the page passes `onUploaded={() => load()}`. Add `import { useMe } from '../../lib/authContext'`, `import UploadDraftDialog from '../case/UploadDraftDialog'`, `import { mayUploadDraft } from '../case/draftRules'`. In `Row`, `const me = useMe()`, and replace the closing `<Link …>Open the case</Link>` with:

```tsx
<div className="flex items-center gap-3">
  {mayUploadDraft(card.currentStage, me.role) && (
    <UploadDraftDialog caseId={card.id} nextVersion={(versions?.length ?? 0) + 1} onUploaded={onUploaded}
      trigger={<button type="button" className="rounded-md px-2.5 py-1 text-sm font-medium"
        style={{ background: 'var(--accent-primary)', color: 'var(--text-on-accent, white)' }}>Upload draft</button>} />
  )}
  <Link to={`/cases/${card.id}`} className="text-sm font-medium" style={{ color: 'var(--accent-primary)' }}>
    Open the case
  </Link>
</div>
```

- [ ] **Step 9: Verify**

Run: `cd frontend && npx vitest run && npx tsc -b && npx oxlint`
Expected: all green.

- [ ] **Step 10: Commit**

```bash
git add -A frontend/src/features/case frontend/src/features/queues/MyDraftsPage.tsx
git commit -m "feat(unit-66): upload a draft from a dialog, with a note to the PM, from the case and My drafts"
```

---

### Task 4: Offer & fee, offer history, deadline

**Files:**
- Modify: `frontend/src/features/case/caseApi.ts`, `frontend/src/features/case/ExpertCard.tsx`
- Create: `frontend/src/features/case/OfferHistorySheet.tsx`, `frontend/src/features/case/DeadlineDialog.tsx`

**Interfaces:**
- Consumes: `mayEditFee`, `maySetDeadline`, `endOfDayIso`, `toDateInput` (Task 2); `formatPayout(value, currency)` from `../../lib/money`; `SheetRoot`, `SheetTrigger`, `SheetContent`, `DialogRoot`, `DialogTrigger`, `DialogContent` from `../../components/ui/dialog`.
- Produces:
  ```ts
  // caseApi.ts
  export type OfferLogEntry = { at: string; who: string; what: string; before: string | null; after: string | null }
  export type OfferView = { offerId: string; expertId: string; fee: number | null; currency: string; outcome: 'OFFERED' | 'ACCEPTED' | 'DECLINED' | 'TIMED_OUT' | 'SUPERSEDED'; feeSetByName: string | null; feeSetAt: string | null; log: OfferLogEntry[] }
  export function fetchOffer(caseId: string, signal?: AbortSignal): Promise<OfferView | null>
  export function editOfferFee(caseId: string, fee: number): Promise<OfferView>
  export function changeDeadline(caseId: string, deadline: string): Promise<void>
  // ExpertCard.tsx — new props: { detail: CaseDetail; role: Role; reloadKey: number }
  // OfferHistorySheet.tsx — { log: OfferLogEntry[]; trigger: ReactElement }
  // DeadlineDialog.tsx — { caseId: string; deadline: string | null; trigger: ReactElement; onSaved: () => void }
  ```

The rules these components use are already tested in Task 2 (`mayEditFee`, `endOfDayIso`, `toDateInput`). This task is wiring; its check is `tsc -b` and the browser pass in Task 6.

- [ ] **Step 1: API**

Append to `caseApi.ts`:

```ts
/** One line of an offer's log (Unit 65 `OfferLog.Entry`): who · when · what · old → new. */
export type OfferLogEntry = { at: string; who: string; what: string; before: string | null; after: string | null }

/** The case's latest expert offer (Unit 65). `fee` is null until set; frozen once ACCEPTED. */
export type OfferView = {
  offerId: string
  expertId: string
  fee: number | null
  currency: string
  outcome: 'OFFERED' | 'ACCEPTED' | 'DECLINED' | 'TIMED_OUT' | 'SUPERSEDED'
  feeSetByName: string | null
  feeSetAt: string | null
  log: OfferLogEntry[]
}

/** Null when nobody has been offered the case yet. */
export async function fetchOffer(caseId: string, signal?: AbortSignal): Promise<OfferView | null> {
  return unwrap<OfferView | null>(api.get(`/cases/${caseId}/expert/offer`, { signal }))
}

/** 409 once the expert has answered — the fee is frozen at acceptance (D59). */
export async function editOfferFee(caseId: string, fee: number): Promise<OfferView> {
  return unwrap<OfferView>(api.patch(`/cases/${caseId}/expert/offer/fee`, { fee }))
}

/** The date promised to the client (GM, PM). Not a transition; `DeadlineRisk` recomputes on read. */
export async function changeDeadline(caseId: string, deadline: string): Promise<void> {
  await unwrap(api.patch(`/cases/${caseId}/deadline`, { deadline }))
}
```

- [ ] **Step 2: Offer history sheet**

```tsx
// frontend/src/features/case/OfferHistorySheet.tsx
import type { ReactElement } from 'react'
import { SheetContent, SheetRoot, SheetTrigger } from '../../components/ui/dialog'
import type { OfferLogEntry } from './caseApi'

/** Every change to the offer, oldest first: who · date and time · what · old → new (Unit 65 §3). */
export default function OfferHistorySheet({ log, trigger }: { log: readonly OfferLogEntry[]; trigger: ReactElement }) {
  return (
    <SheetRoot>
      <SheetTrigger asChild>{trigger}</SheetTrigger>
      <SheetContent title="Offer history" description="Who changed the offer, when, and from what to what.">
        {log.length === 0 ? (
          <p className="text-sm" style={{ color: 'var(--text-muted)' }}>Nothing has changed since the offer was made.</p>
        ) : (
          <ol className="flex flex-col gap-3">
            {log.map((line, index) => (
              <li key={index} className="border-l-2 pl-3 text-sm" style={{ borderColor: 'var(--border-default)' }}>
                <p className="font-medium">{line.what}</p>
                <p className="text-xs" style={{ color: 'var(--text-muted)' }}>
                  {line.who} · {new Date(line.at).toLocaleString()}
                </p>
                {(line.before !== null || line.after !== null) && (
                  <p className="font-num mt-0.5 text-xs tabular-nums">{line.before ?? '—'} → {line.after ?? '—'}</p>
                )}
              </li>
            ))}
          </ol>
        )}
      </SheetContent>
    </SheetRoot>
  )
}
```

- [ ] **Step 3: Expert card with the offer**

In `ExpertCard.tsx`: change the signature to `({ detail, role, reloadKey }: { detail: CaseDetail; role: Role; reloadKey: number })`, rename the heading to **Expert & offer**, and add, after the tier line and before the read-receipt line:

```tsx
<OfferRows caseId={detail.summary.id} role={role} reloadKey={reloadKey} />
```

Add in the same file (imports: `useEffect, useState, type ReactElement` from react; `DialogContent, DialogRoot, DialogTrigger` from `../../components/ui/dialog`; `editOfferFee, fetchOffer, type OfferView` from `./caseApi`; `mayEditFee` from `./caseRules`; `formatPayout` from `../../lib/money`; `OfferHistorySheet` from `./OfferHistorySheet`; `type Role` from `../../lib/session`):

```tsx
const OUTCOME: Record<OfferView['outcome'], { label: string; fg: string; bg: string }> = {
  OFFERED: { label: 'offered', fg: 'var(--status-amber)', bg: 'var(--status-amber-bg)' },
  ACCEPTED: { label: 'accepted', fg: 'var(--status-green)', bg: 'var(--status-green-bg)' },
  DECLINED: { label: 'declined', fg: 'var(--status-red)', bg: 'var(--status-red-bg)' },
  TIMED_OUT: { label: 'timed out', fg: 'var(--status-red)', bg: 'var(--status-red-bg)' },
  SUPERSEDED: { label: 'superseded', fg: 'var(--text-muted)', bg: 'var(--bg-raised)' },
}

function OfferRows({ caseId, role, reloadKey }: { caseId: string; role: Role; reloadKey: number }) {
  const [offer, setOffer] = useState<OfferView | null | undefined>(undefined)
  const [tick, setTick] = useState(0)

  useEffect(() => {
    const controller = new AbortController()
    fetchOffer(caseId, controller.signal).then(setOffer).catch(() => {
      if (!controller.signal.aborted) setOffer(null)
    })
    return () => controller.abort()
  }, [caseId, reloadKey, tick])

  if (offer === undefined) return null
  if (offer === null) return <p className="mt-2 text-xs" style={{ color: 'var(--text-muted)' }}>Not offered yet.</p>

  const tone = OUTCOME[offer.outcome]
  return (
    <div className="mt-3 flex flex-col gap-1.5 text-sm">
      <div className="flex items-center justify-between gap-2">
        <span style={{ color: 'var(--text-muted)' }}>Offer</span>
        <span className="rounded-md px-1.5 py-0.5 text-xs font-semibold" style={{ color: tone.fg, background: tone.bg }}>{tone.label}</span>
      </div>
      <div className="flex items-center justify-between gap-2">
        <span style={{ color: 'var(--text-muted)' }}>Fee for this case</span>
        <span className="font-num tabular-nums">
          {offer.fee === null ? 'Fee not set' : formatPayout(offer.fee, offer.currency)}
          {offer.outcome === 'ACCEPTED' && offer.fee !== null && (
            <span className="ml-1 text-xs" style={{ color: 'var(--text-muted)' }}>· frozen at acceptance</span>
          )}
        </span>
      </div>
      <div className="flex gap-3 text-xs">
        {mayEditFee(role, offer.outcome) && (
          <EditFeeDialog caseId={caseId} fee={offer.fee} onSaved={() => setTick((n) => n + 1)}
            trigger={<button type="button" className="font-medium" style={{ color: 'var(--accent-primary)' }}>Edit fee</button>} />
        )}
        <OfferHistorySheet log={offer.log}
          trigger={<button type="button" className="font-medium" style={{ color: 'var(--accent-primary)' }}>History</button>} />
      </div>
    </div>
  )
}

function EditFeeDialog({ caseId, fee, trigger, onSaved }: { caseId: string; fee: number | null; trigger: ReactElement; onSaved: () => void }) {
  const [open, setOpen] = useState(false)
  const [value, setValue] = useState(fee === null ? '' : String(fee))
  const [error, setError] = useState<string | null>(null)

  async function save(event: React.FormEvent) {
    event.preventDefault()
    setError(null)
    try {
      await editOfferFee(caseId, Number(value))
      setOpen(false)
      onSaved()
    } catch (cause: unknown) {
      // A 409 means the expert answered while this was open: reload so the card stops offering Edit.
      setError(cause instanceof Error ? cause.message : 'The fee was not saved')
      onSaved()
    }
  }

  return (
    <DialogRoot open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild>{trigger}</DialogTrigger>
      <DialogContent title="Edit fee" description="Editable only while the expert has not answered the offer.">
        <form onSubmit={save} className="flex flex-col gap-3 text-sm">
          <input type="number" min="0" step="0.01" required value={value} onChange={(e) => setValue(e.target.value)}
            className="rounded-md border p-2" style={{ borderColor: 'var(--border-default)' }} />
          {error && <p role="alert" style={{ color: 'var(--status-red)' }}>{error}</p>}
          <div className="flex justify-end">
            <button type="submit" className="rounded-md px-3 py-1.5 font-medium" style={{ background: 'var(--accent-primary)', color: 'var(--text-on-accent, white)' }}>Save</button>
          </div>
        </form>
      </DialogContent>
    </DialogRoot>
  )
}
```

In `CaseDetail.tsx` change `<ExpertCard detail={detail} />` to `<ExpertCard detail={detail} role={me.role} reloadKey={timeline.length} />` (a new audit row means something changed — reassign, accept — so the offer re-reads).

- [ ] **Step 4: Deadline dialog**

```tsx
// frontend/src/features/case/DeadlineDialog.tsx
import { useState, type ReactElement } from 'react'
import { DialogContent, DialogRoot, DialogTrigger } from '../../components/ui/dialog'
import { changeDeadline, postNote } from './caseApi'
import { endOfDayIso, toDateInput } from './caseRules'

/** The date promised to the client (GM, PM). The optional reason becomes a timeline note. */
export default function DeadlineDialog({ caseId, deadline, trigger, onSaved }: {
  caseId: string; deadline: string | null; trigger: ReactElement; onSaved: () => void
}) {
  const [open, setOpen] = useState(false)
  const [date, setDate] = useState(toDateInput(deadline))
  const [reason, setReason] = useState('')
  const [error, setError] = useState<string | null>(null)

  async function save(event: React.FormEvent) {
    event.preventDefault()
    setError(null)
    try {
      await changeDeadline(caseId, endOfDayIso(date))
    } catch (cause: unknown) {
      setError(cause instanceof Error ? cause.message : 'The deadline was not changed')
      return
    }
    // The date stands even if the note fails; the reason is worth a retry but not a rollback.
    if (reason.trim()) await postNote(caseId, `Deadline changed to ${date}: ${reason.trim()}`).catch(() => undefined)
    setOpen(false)
    setReason('')
    onSaved()
  }

  return (
    <DialogRoot open={open} onOpenChange={(next) => { setOpen(next); if (next) setDate(toDateInput(deadline)) }}>
      <DialogTrigger asChild>{trigger}</DialogTrigger>
      <DialogContent title="Change deadline" description="The date promised to the client.">
        <form onSubmit={save} className="flex flex-col gap-3 text-sm">
          <input type="date" required value={date} onChange={(e) => setDate(e.target.value)}
            className="rounded-md border p-2" style={{ borderColor: 'var(--border-default)' }} />
          <label className="flex flex-col gap-1">
            <span style={{ color: 'var(--text-muted)' }}>Reason (optional, goes to the timeline)</span>
            <textarea rows={2} value={reason} onChange={(e) => setReason(e.target.value)}
              className="rounded-md border p-2" style={{ borderColor: 'var(--border-default)' }} />
          </label>
          {error && <p role="alert" style={{ color: 'var(--status-red)' }}>{error}</p>}
          <div className="flex justify-end">
            <button type="submit" className="rounded-md px-3 py-1.5 font-medium" style={{ background: 'var(--accent-primary)', color: 'var(--text-on-accent, white)' }}>Save</button>
          </div>
        </form>
      </DialogContent>
    </DialogRoot>
  )
}
```

(It is mounted by the header in Task 6.)

- [ ] **Step 5: Verify**

Run: `cd frontend && npx vitest run && npx tsc -b && npx oxlint`
Expected: green.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/features/case
git commit -m "feat(unit-66): the offer and its fee on the case, its history, and a deadline dialog"
```

---

### Task 5: Checklist on the case

**Files:**
- Create: `frontend/src/features/case/ChecklistSheet.tsx`
- Modify: `frontend/src/features/case/DocumentsPanel.tsx`, `frontend/src/features/case/CaseDetail.tsx`

**Interfaces:**
- Consumes: `CaseChecklist` default export from `../checklist/CaseChecklist` (props `{ caseId: string; onChecklistChanged: (view: ChecklistView) => void; onCaseLeftTheStage: () => void }`); `fetchChecklist(caseId, signal)` from `../checklist/checklistApi`; `ChecklistView` from `../checklist/checklistRules` (`items[]`, `unsent`, `lastChasedAt`, `total`, `complete`); `mayManageChecklist` (Task 2).
- Produces: `DocumentsPanel` props become `{ detail: CaseDetail; role: Role; onChanged: () => void }`; `ChecklistSheet` props `{ caseId: string; trigger: ReactElement; onChecklistChanged: (view: ChecklistView) => void; onCaseLeftTheStage: () => void }`.

Read `checklistRules.ts` for `ChecklistItem`'s field names (`label`, `status`, `sentAt`) before step 2, and use them as they are.

- [ ] **Step 1: Sheet**

```tsx
// frontend/src/features/case/ChecklistSheet.tsx
import type { ReactElement } from 'react'
import { SheetContent, SheetRoot, SheetTrigger } from '../../components/ui/dialog'
import CaseChecklist from '../checklist/CaseChecklist'
import type { ChecklistView } from '../checklist/checklistRules'

/** The Doc checklists screen's own component, opened from the case (Unit 66). Nothing re-implemented. */
export default function ChecklistSheet({ caseId, trigger, onChecklistChanged, onCaseLeftTheStage }: {
  caseId: string; trigger: ReactElement; onChecklistChanged: (view: ChecklistView) => void; onCaseLeftTheStage: () => void
}) {
  return (
    <SheetRoot>
      <SheetTrigger asChild>{trigger}</SheetTrigger>
      <SheetContent title="Checklist" description="Add items, set their status, send new items and chase the client.">
        <CaseChecklist caseId={caseId} onChecklistChanged={onChecklistChanged} onCaseLeftTheStage={onCaseLeftTheStage} />
      </SheetContent>
    </SheetRoot>
  )
}
```

- [ ] **Step 2: Panel summary**

In `DocumentsPanel.tsx`: rename the heading to **Documents & checklist**; take `{ detail, role, onChanged }`; drop `useMe`, `mayReach` and the `/checklists` `<Link>` block; load the checklist:

```tsx
const [view, setView] = useState<ChecklistView | null>(null)
useEffect(() => {
  const controller = new AbortController()
  fetchChecklist(detail.summary.id, controller.signal).then(setView).catch(() => undefined)
  return () => controller.abort()
}, [detail.summary.id, detail.checklistComplete, detail.checklistTotal])
```

Keep the existing `n / m` chip. Under it, when `view`:

```tsx
<ul className="mt-2 flex flex-col text-sm">
  {view.items.map((item) => (
    <li key={item.id} className="flex justify-between border-t py-1" style={{ borderColor: 'var(--bg-raised)' }}>
      <span>{item.label}</span>
      <span className="text-xs" style={{ color: 'var(--text-muted)' }}>
        {item.sentAt ? item.status.toLowerCase().replaceAll('_', ' ') : 'not sent'}
      </span>
    </li>
  ))}
</ul>
<p className="mt-1 text-xs" style={{ color: 'var(--text-muted)' }}>
  {[
    view.unsent > 0 ? `${view.unsent} item${view.unsent === 1 ? '' : 's'} wait for the next Send` : null,
    view.lastChasedAt ? `chased ${new Date(view.lastChasedAt).toLocaleDateString()}` : null,
  ].filter(Boolean).join(' · ')}
</p>
{mayManageChecklist(role) && (
  <ChecklistSheet caseId={detail.summary.id} onChecklistChanged={setView} onCaseLeftTheStage={onChanged}
    trigger={<button type="button" className="mt-2 text-sm font-medium" style={{ color: 'var(--accent-primary)' }}>Manage checklist →</button>} />
)}
```

In `CaseDetail.tsx`: `<DocumentsPanel detail={detail} role={me.role} onChanged={() => void load()} />`.

- [ ] **Step 3: Verify**

Run: `cd frontend && npx vitest run && npx tsc -b && npx oxlint`
Expected: green. `checklistRules.test.ts` still passes untouched.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/features/case
git commit -m "feat(unit-66): the checklist on the case, managed from a sheet"
```

---

### Task 6: The header — progress strip, action bar, next step; browser pass

**Files:**
- Create: `frontend/src/features/case/CaseProgress.tsx`, `frontend/src/features/case/CaseHeader.tsx`
- Delete: `frontend/src/features/case/StageActions.tsx`
- Modify: `frontend/src/features/case/CaseDetail.tsx`

**Interfaces:**
- Consumes: `progress`, `STAGE_SHORT`, `formatSpan`, `type Step` (Task 1); `splitActions`, `nextStep`, `maySetDeadline` (Task 2); `UploadDraftDialog` (Task 3); `DeadlineDialog` (Task 4); `actionsFor`, `STAGE_OWNER`, `type BoardCard`, `type QuickAction`, `type Stage` from `../board/boardRules`; `PopoverRoot`, `PopoverTrigger`, `PopoverContent` from `../../components/ui/popover`; `ROLE_LABELS` from `../../lib/session`.
- Produces: `CaseHeader` props `{ detail: CaseDetail; timeline: readonly TimelineEntry[]; role: Role; busy: boolean; error: string | null; onAction: (a: QuickAction) => void; onChanged: () => void }`.

- [ ] **Step 1: The strip**

```tsx
// frontend/src/features/case/CaseProgress.tsx
import { useEffect, useMemo, useRef } from 'react'
import { PopoverContent, PopoverRoot, PopoverTrigger } from '../../components/ui/popover'
import { ROLE_LABELS } from '../../lib/session'
import { STAGE_OWNER, type Stage } from '../board/boardRules'
import type { TimelineEntry } from './caseApi'
import { STAGE_SHORT, formatSpan, progress, type Step } from './caseProgress'

/**
 * The twelve stages left to right under the case name (Unit 66, option A). A summary of the trail,
 * never a replacement for it — Notes & timeline stays the record.
 */
export default function CaseProgress({ entries, current, slaLabel }: {
  entries: readonly TimelineEntry[]; current: Stage; slaLabel: string | null
}) {
  const steps = useMemo(() => progress(entries, current, new Date()), [entries, current])
  const currentRef = useRef<HTMLLIElement>(null)
  useEffect(() => currentRef.current?.scrollIntoView({ inline: 'center', block: 'nearest' }), [current])

  return (
    <ol className="mt-3 flex overflow-x-auto pb-1" aria-label="Case progress">
      {steps.map((step, index) => (
        <li key={step.stage} ref={step.state === 'current' ? currentRef : undefined}
          className="relative min-w-[4.5rem] flex-1 text-center text-[11px]">
          {index > 0 && (
            <span aria-hidden className="absolute top-[7px] right-1/2 h-0.5 w-full"
              style={{ background: step.state === 'not-reached' ? 'var(--border-default)' : 'var(--accent-primary)' }} />
          )}
          <PopoverRoot>
            <PopoverTrigger asChild>
              <button type="button" aria-label={ariaFor(step)} className="relative flex w-full flex-col items-center gap-1"
                style={{ color: step.state === 'current' ? 'var(--text-primary)' : 'var(--text-muted)', fontWeight: step.state === 'current' ? 600 : 400 }}>
                <span className="h-4 w-4 rounded-full border-2" style={dotStyle(step)} />
                <span>
                  {STAGE_SHORT[step.stage]}
                  {step.visits > 1 && (
                    <span className="ml-0.5 rounded px-0.5 text-[10px]" style={{ color: 'var(--status-amber)', background: 'var(--status-amber-bg)' }}>×{step.visits}</span>
                  )}
                </span>
                <span className="text-[10px] font-normal" style={{ color: 'var(--text-muted)' }}>
                  {step.state === 'current' ? `${formatSpan(step.spentMs)} here` : step.firstAt ? new Date(step.firstAt).toLocaleDateString(undefined, { day: 'numeric', month: 'short' }) : ''}
                </span>
              </button>
            </PopoverTrigger>
            <PopoverContent label={`${STAGE_SHORT[step.stage]} details`}>
              <StepDetail step={step} slaLabel={step.state === 'current' ? slaLabel : null} />
            </PopoverContent>
          </PopoverRoot>
        </li>
      ))}
    </ol>
  )
}

function dotStyle(step: Step): React.CSSProperties {
  if (step.state === 'current') return { borderColor: 'var(--accent-primary)', background: 'var(--bg-surface)', boxShadow: '0 0 0 4px var(--accent-soft)' }
  if (step.state === 'reached') return { borderColor: 'var(--accent-primary)', background: 'var(--accent-primary)' }
  return { borderColor: 'var(--rail-unknown)', background: 'var(--bg-surface)' }
}

function ariaFor(step: Step): string {
  const state = step.state === 'current' ? 'current' : step.state === 'reached' ? 'reached' : 'not reached'
  return `${STAGE_SHORT[step.stage]}, ${state}${step.visits > 1 ? `, entered ${step.visits} times` : ''}`
}

function StepDetail({ step, slaLabel }: { step: Step; slaLabel: string | null }) {
  const owner = STAGE_OWNER[step.stage]
  if (step.visits === 0 && step.state !== 'current') {
    return <p className="text-sm"><b>{STAGE_SHORT[step.stage]}</b> — not reached yet.</p>
  }
  return (
    <div className="text-sm">
      <p><b>{STAGE_SHORT[step.stage]}</b>{owner && <span style={{ color: 'var(--text-muted)' }}> · {ROLE_LABELS[owner]}</span>}</p>
      <dl className="mt-2 grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 text-xs">
        {step.lastAt && (<><dt style={{ color: 'var(--text-muted)' }}>Entered</dt><dd>{new Date(step.lastAt).toLocaleString()}{step.visits > 1 ? ` (visit ${step.visits})` : ''}</dd></>)}
        {step.visits > 1 && step.firstAt && (<><dt style={{ color: 'var(--text-muted)' }}>First</dt><dd>{new Date(step.firstAt).toLocaleString()}</dd></>)}
        <dt style={{ color: 'var(--text-muted)' }}>Time here</dt><dd>{formatSpan(step.spentMs)}{step.visits > 1 ? ' in total' : ''}</dd>
        {step.lastNote && (<><dt style={{ color: 'var(--text-muted)' }}>Why</dt><dd>{step.lastNote}</dd></>)}
        {slaLabel && (<><dt style={{ color: 'var(--text-muted)' }}>Stage SLA</dt><dd>{slaLabel}</dd></>)}
      </dl>
    </div>
  )
}
```

- [ ] **Step 2: The header** — create `CaseHeader.tsx` by moving `StageActions.tsx`'s content (keep its long comments about the sticky offset and `--shell-gutter` bleed verbatim, and `SLA_TONE` / `readable`), then change the body to:

```tsx
export default function CaseHeader({ detail, timeline, role, busy, error, onAction, onChanged }: {
  detail: CaseDetail; timeline: readonly TimelineEntry[]; role: Role; busy: boolean; error: string | null
  onAction: (action: QuickAction) => void; onChanged: () => void
}) {
  const card = detail.summary as BoardCard
  const { primary, more } = splitActions(actionsFor(card, role))
  const sla = card.slaStatus ? SLA_TONE[card.slaStatus] : null
  const inException = card.exceptionState !== 'NONE'
  const next = nextStep(card.currentStage, card.exceptionState, role)
  const [moreOpen, setMoreOpen] = useState(false)
  const due = card.deadline ? new Date(card.deadline).toLocaleDateString(undefined, { day: 'numeric', month: 'short' }) : 'not set'
  const button = 'rounded-md px-2.5 py-1.5 text-sm font-medium disabled:opacity-40'

  return (
    <header /* same className and style as StageActions today */>
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          <div className="flex flex-wrap items-center gap-2">
            <span className="font-mono text-xs" style={{ color: 'var(--text-muted)' }}>{card.caseCode}</span>
            {sla && <span className="rounded-md px-1.5 py-0.5 text-xs font-semibold" style={{ color: sla.fg, background: sla.bg }}>Stage SLA: {sla.label.toLowerCase()}</span>}
            {inException && <span className="rounded-md px-1.5 py-0.5 text-xs font-semibold" style={{ color: 'var(--status-red)', background: 'var(--status-red-bg)' }}>{readable(card.exceptionState)}</span>}
            {maySetDeadline(role) ? (
              <DeadlineDialog caseId={card.id} deadline={card.deadline} onSaved={onChanged}
                trigger={<button type="button" className="rounded-md px-1.5 py-0.5 text-xs font-semibold" style={{ background: 'var(--bg-raised)', color: 'var(--accent-primary)' }}>Due {due} ✎</button>} />
            ) : (
              <span className="rounded-md px-1.5 py-0.5 text-xs font-semibold" style={{ background: 'var(--bg-raised)', color: 'var(--text-muted)' }}>Due {due}</span>
            )}
          </div>
          <h1 className="mt-1 text-lg font-semibold tracking-tight">
            {detail.maySeeCaseContent ? (detail.clientName ?? 'Unnamed contact') : <span style={{ color: 'var(--text-muted)' }}>Client withheld</span>}
          </h1>
          <p className="text-sm" style={{ color: 'var(--text-muted)' }}>{readable(card.serviceType)}</p>
        </div>

        <div className="flex flex-col items-end gap-1.5">
          <div className="flex flex-wrap justify-end gap-1.5">
            {mayUploadDraft(card.currentStage, role) && (
              <UploadDraftDialog caseId={card.id} nextVersion={detail.summary.draftVersionCount + 1} onUploaded={onChanged}
                trigger={<button type="button" disabled={busy} className={button} style={{ background: 'var(--accent-primary)', color: 'var(--text-on-accent, white)' }}>Upload draft</button>} />
            )}
            {primary.map((action) => (
              <button key={action.path} type="button" disabled={busy} onClick={() => onAction(action)} className={button}
                style={{ background: 'var(--bg-raised)', color: 'var(--accent-primary)' }}>{action.label}</button>
            ))}
            {more.length > 0 && (
              <PopoverRoot open={moreOpen} onOpenChange={setMoreOpen}>
                <PopoverTrigger asChild>
                  <button type="button" disabled={busy} className={button} style={{ background: 'var(--bg-raised)', color: 'var(--text-primary)' }}>More ▾</button>
                </PopoverTrigger>
                <PopoverContent label="More actions">
                  <ul className="flex flex-col">
                    {more.map((action) => (
                      <li key={action.path}>
                        <button type="button" onClick={() => { setMoreOpen(false); onAction(action) }}
                          className="w-full rounded-md px-2 py-1.5 text-left text-sm hover:bg-[var(--bg-raised)]">{action.label}</button>
                      </li>
                    ))}
                  </ul>
                </PopoverContent>
              </PopoverRoot>
            )}
            {primary.length === 0 && more.length === 0 && !mayUploadDraft(card.currentStage, role) && (
              <span className="text-xs" style={{ color: 'var(--text-muted)' }}>No action is yours at this stage.</span>
            )}
          </div>
          {next && <p className="text-xs" style={{ color: 'var(--text-muted)' }}>{next}</p>}
        </div>
      </div>

      <CaseProgress entries={timeline} current={card.currentStage} slaLabel={sla?.label ?? null} />

      {error && <p className="mt-2 text-sm" style={{ color: 'var(--status-red)' }}>{error}</p>}
    </header>
  )
}
```

Imports: `useState`; `PopoverContent, PopoverRoot, PopoverTrigger` from `../../components/ui/popover`; `actionsFor, type BoardCard, type QuickAction` from `../board/boardRules`; `type CaseDetail, type TimelineEntry` from `./caseApi`; `CaseProgress`; `DeadlineDialog`; `UploadDraftDialog`; `mayUploadDraft` from `./draftRules`; `maySetDeadline, nextStep, splitActions` from `./caseRules`; `type Role` from `../../lib/session`. Update the file's header comment to say it is Unit 66's header and still takes its actions from `boardRules.actionsFor`.

Note: `PopoverContent` aligns `end`; for the steps that is acceptable. Do not edit `popover.tsx`.

- [ ] **Step 3: Wire and delete**

In `CaseDetail.tsx`: replace `import StageActions from './StageActions'` with `import CaseHeader from './CaseHeader'`, and the `<StageActions …/>` element with:

```tsx
<CaseHeader detail={detail} timeline={timeline} role={me.role} busy={busy} error={actionError}
  onAction={onAction} onChanged={() => void load()} />
```

Delete `frontend/src/features/case/StageActions.tsx`. Run `grep -rn "StageActions\|UploadDraft'" frontend/src` — only comments may remain; update `CaseCard.tsx`'s header comment mention of `StageActions` to `CaseHeader`.

- [ ] **Step 4: Verify**

Run: `cd frontend && npx vitest run && npx tsc -b && npx oxlint`
Expected: green.

- [ ] **Step 5: Browser pass** (use the `run` skill to start backend profile `local` + `npx vite`, then the claude-in-chrome skill; sign in with the `seed-local` logins, `V900..V911`)

At **1440 wide and 390 wide**, check and screenshot:
1. **CM**, case in `DRAFT_IN_PROGRESS` after a PM return: strip shows Drafting current with ×2 and Draft review reached; popover shows the return reason; header **Upload draft** opens the dialog; upload docx+pdf with a note → case moves to Draft review, note appears in Notes & timeline; My drafts row shows **Upload draft**.
2. **CM**, case in `EXPERT_SIGNING`: Expert signed / declined / Mark expert overdue are offered; Reassign is not.
3. **PC**, case in `DOC_COLLECTION`: Documents & checklist lists items; **Manage checklist →** opens the sheet; add an item, Send, Chase; summary updates.
4. **PM**: **Due … ✎** opens Change deadline; save with a reason; chip and timeline update. Expert & offer shows the fee, **Edit fee** while OFFERED, **History** sheet.
5. **ENM**: client withheld; Expert & offer and Edit fee present.
6. At 390: the strip scrolls horizontally with the current step in view; no horizontal page scroll.
7. Dark/light: dots and chips legible (tokens only).

Record any defect as a fix in this task before committing.

- [ ] **Step 6: Commit**

```bash
git add -A frontend/src/features
git commit -m "feat(unit-66): case header with the stage progress strip and one action bar"
```

---

### Task 7: Documents and memory

**Files:**
- Modify: `.claude/implementation-status.md` (Next-up item 8 → built, with evidence), `context/ui-context.md` (the **Case detail** bullet near line 425), `.serena/memories/implementation_status.md`, and add a line to `context/specs/66-case-workspace.md` header: `Built 2026-MM-DD`.

- [ ] **Step 1: Edit the status row** — item 8 becomes: `~~**Unit 66 — the case workspace.**~~ **Built {date}** (spec `66`): progress strip from the timeline (`caseProgress.ts`), `CaseHeader` action bar, Upload draft dialog with note (case + My drafts), checklist sheet, Expert & offer with Edit fee + History, Deadline dialog, CM on expert signed / declined / timed-out. Frontend {n} tests green; browser-checked at 1440 and 390.` Use the real test count from `npx vitest run`.
- [ ] **Step 2: Edit `ui-context.md`'s Case detail bullet** (an edit, not a note beside it): the sticky header now holds the case code, SLA / exception / due chips, the name, the action bar (primary + More ▾), the next-step line and the 12-stage progress strip; left column is Documents & checklist, Draft (status + versions + upload), Case facts, Expert & offer, Strategy notes, Expert rationale.
- [ ] **Step 3: Serena memory** — replace the "Unit 66 SPECCED" line in `.serena/memories/implementation_status.md` with the built line from Step 1.
- [ ] **Step 4: Commit**

```bash
git add .claude/implementation-status.md context/ui-context.md .serena/memories/implementation_status.md context/specs/66-case-workspace.md
git commit -m "docs(unit-66): case workspace built"
```
