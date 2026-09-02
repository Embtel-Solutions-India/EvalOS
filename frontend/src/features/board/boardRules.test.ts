import { describe, expect, it } from 'vitest'
import type { Role } from '../../lib/session'
import {
  QUICK_ACTIONS,
  STAGE_ACCESS,
  STAGE_COLUMNS,
  actionsFor,
  admits,
  allInsideSla,
  columnsFor,
  dueBeforeFor,
  slaMix,
  type BoardCard,
  type ExceptionState,
  type Stage,
} from './boardRules'
import { DEADLINE_WINDOWS } from './deadlineWindow'

/**
 * The board's two decision tables, which until now were only exercised by clicking.
 *
 * These are convenience tables — the server gates every transition and every read — but a
 * wrong cell still costs somebody their work: a hidden column they needed, or a button that
 * only ever produces a 409. That is worth assertions.
 */

const ALL_ROLES: readonly Role[] = [
  'GM',
  'BRAND_MANAGER',
  'PROJECT_MANAGER',
  'PROJECT_COORDINATOR',
  'CASE_MANAGER',
  'EXPERT_NETWORK_MANAGER',
]

/**
 * The roles that actually work the board — every role, now that the sales desk is gone.
 *
 * Kept as its own name for the reason Unit 29 introduced it: the loops below say "every role that
 * works the board", and the next role that does not is a one-line change here rather than a
 * rewrite of the assertions.
 */
const CASE_ROLES: readonly Role[] = ALL_ROLES

function card(overrides: Partial<BoardCard> = {}): BoardCard {
  return {
    id: 'c1',
    caseCode: 'IE-2026-0001',
    clientName: 'Anita Rao',
    serviceType: 'CREDENTIAL_EVALUATION',
    deadline: null,
    slaStatus: 'ON_TRACK',
    deadlineRisk: null,
    currentStage: 'DOC_COLLECTION',
    exceptionState: 'NONE',
    poolStatus: 'ASSIGNED',
    assignedPm: null,
    assignedCm: null,
    assignedCoordinator: null,
    expertSignStatus: null,
    pmApprovalStatus: null,
    clientApprovalStatus: null,
    dealValue: null,
    ...overrides,
  }
}

const paths = (stage: Stage, role: Role, exceptionState: ExceptionState = 'NONE') =>
  actionsFor(card({ currentStage: stage, exceptionState }), role).map((action) => action.path)

describe('STAGE_ACCESS', () => {
  it('covers every role and every column, so no lookup is undefined', () => {
    // Still ALL_ROLES: the point of this one is that the table is *total*, which is what turns
    // adding a role into a compile error instead of an undefined lookup at runtime.
    for (const role of ALL_ROLES) {
      for (const { stages } of STAGE_COLUMNS) {
        for (const stage of stages) {
          expect(STAGE_ACCESS[role]?.[stage], `${role} / ${stage}`).toBeDefined()
        }
      }
    }
  })

  it('leaves every role at least one column they can work', () => {
    // A role whose every cell was `status` or `none` would have a board they can only stare
    // at, which is a table typo rather than a design.
    for (const role of CASE_ROLES) {
      const worked = STAGE_COLUMNS.filter(({ stages }) =>
        stages.some((stage) => STAGE_ACCESS[role][stage] === 'full'),
      )
      expect(worked.length, `${role} works no stage`).toBeGreaterThan(0)
    }
  })


  it('hides the two stages a Case Manager is never responsible for', () => {
    // A case naming them as CM has already left doc collection, and delivery is the
    // Coordinator's stage. Both are empty by scope anyway; this stops them being drawn.
    expect(columnsFor('CASE_MANAGER').map((column) => column.label)).toEqual([
      'PM Review',
      'Drafting',
      'Draft Review',
      'Client Review',
      'Expert Signing',
      'Final QC',
    ])
  })

  it('gives the oversight roles the whole pipeline', () => {
    for (const role of ['GM', 'BRAND_MANAGER'] as const) {
      expect(columnsFor(role)).toHaveLength(STAGE_COLUMNS.length)
      expect(columnsFor(role).every((column) => column.access === 'full')).toBe(true)
    }
  })

  it('numbers a column by its place in the whole pipeline, not in the role subset', () => {
    // A Case Manager's first column is stage 2 of 8. Numbering it 1 would say the work starts
    // with them — which is what happens if the `none` cells are filtered before the index.
    expect(columnsFor('CASE_MANAGER').map((column) => column.step)).toEqual([2, 3, 4, 5, 6, 7])
    expect(columnsFor('GM').map((column) => column.step)).toEqual([1, 2, 3, 4, 5, 6, 7, 8])
  })

  it('marks the Coordinator watching the middle of the pipeline and working both ends', () => {
    const access = Object.fromEntries(
      columnsFor('PROJECT_COORDINATOR').map((column) => [column.label, column.access]),
    )
    // Keyed by column label, because a column can hold two stages (Unit 31). The Coordinator
    // works the three client-facing ones and watches the rest.
    expect(access).toEqual({
      'Doc Collection': 'full',
      'PM Review': 'status',
      Drafting: 'status',
      'Draft Review': 'status',
      // READY_TO_SEND is theirs and CLIENT_REVIEW is theirs; the folded column is `full`.
      'Client Review': 'full',
      'Expert Signing': 'status',
      'Final QC': 'status',
      'Ready to Deliver': 'full',
    })
  })
})

describe('slaMix', () => {
  it('counts a case with no clock as its own band, never as on track', () => {
    // A closed case and one holding an exception state both get a null SLA status, and
    // colouring them green would report a stalled column as healthy — the whole reason
    // `unknown` is a fourth band rather than a fold into `onTrack`.
    expect(
      slaMix([
        card({ slaStatus: 'ON_TRACK' }),
        card({ slaStatus: 'AT_RISK' }),
        card({ slaStatus: 'OVERDUE' }),
        card({ slaStatus: 'OVERDUE' }),
        card({ slaStatus: null }),
      ]),
    ).toEqual({ onTrack: 1, atRisk: 1, overdue: 2, unknown: 1 })
  })

  it('refuses to claim "all inside SLA" over cases with no clock running', () => {
    // The live board found this: 150 cases, 0 overdue, 0 at risk — and 127 with no clock, which
    // the header reported as "all inside SLA" while the rail directly under it drew them grey.
    expect(allInsideSla({ onTrack: 23, atRisk: 0, overdue: 0, unknown: 127 })).toBe(false)
    expect(allInsideSla({ onTrack: 23, atRisk: 0, overdue: 0, unknown: 0 })).toBe(true)
    expect(allInsideSla({ onTrack: 5, atRisk: 1, overdue: 0, unknown: 0 })).toBe(false)
    expect(allInsideSla({ onTrack: 5, atRisk: 0, overdue: 1, unknown: 0 })).toBe(false)
    // An empty board makes no claim either way.
    expect(allInsideSla({ onTrack: 0, atRisk: 0, overdue: 0, unknown: 0 })).toBe(false)
  })

  it('totals every card exactly once, so the rail is the whole column', () => {
    const cards = [
      card({ slaStatus: 'ON_TRACK' }),
      card({ slaStatus: null }),
      card({ slaStatus: 'AT_RISK' }),
    ]
    const mix = slaMix(cards)
    expect(mix.onTrack + mix.atRisk + mix.overdue + mix.unknown).toBe(cards.length)
    expect(slaMix([])).toEqual({ onTrack: 0, atRisk: 0, overdue: 0, unknown: 0 })
  })
})

describe('actionsFor', () => {
  it('offers the stage action to the role that drives the stage', () => {
    expect(paths('DOC_COLLECTION', 'PROJECT_COORDINATOR')).toContain('docs-complete')
    expect(paths('PM_REVIEW', 'PROJECT_MANAGER')).toContain('assign-cm')
    expect(paths('DRAFT_IN_PROGRESS', 'CASE_MANAGER')).toContain('draft/submit')
    // QC is its own stage now, not a step inside signing.
    expect(paths('FINAL_QC', 'PROJECT_MANAGER')).toContain('qc-approve')
    expect(paths('FINAL_QC', 'PROJECT_MANAGER')).toContain('qc-fail')
    // And the CM's send is what moves a client-approved letter to the expert.
    expect(paths('CLIENT_APPROVAL', 'CASE_MANAGER')).toContain('send-to-expert')
    expect(paths('READY_TO_DELIVER', 'PROJECT_COORDINATOR')).toContain('deliver')
  })

  it('withholds a stage action from a role that only watches that stage', () => {
    // The Coordinator can see the draft column but does not submit or approve drafts.
    expect(STAGE_ACCESS.PROJECT_COORDINATOR.DRAFT_IN_PROGRESS).toBe('status')
    expect(paths('READY_TO_SEND', 'PROJECT_COORDINATOR')).toContain('draft/send-to-client')
    // The PM watches delivery rather than running it.
    expect(paths('READY_TO_DELIVER', 'PROJECT_MANAGER')).not.toContain('deliver')
    expect(paths('DELIVERED', 'PROJECT_MANAGER')).not.toContain('close')
  })

  it('keeps the stage-preserving actions for a watching role', () => {
    // "Status" means you do not advance the stage, not that you are powerless: a Coordinator
    // watching a stalled draft can still put the case on hold.
    const watching = paths('DRAFT_IN_PROGRESS', 'PROJECT_COORDINATOR')
    expect(watching).toContain('hold')
    expect(watching).toContain('refund/request')
  })

  it('never offers a role an action its route would refuse', () => {
    // The client half of the table is only useful if it agrees with the server's gate.
    for (const role of CASE_ROLES) {
      for (const { stages } of STAGE_COLUMNS) {
        for (const stage of stages) {
          for (const action of actionsFor(card({ currentStage: stage }), role)) {
            // `admits` rather than a re-derivation of the same rule: a second copy is how
            // "the GM sees everything" quietly survives a decision to the contrary.
            expect(admits(action, role), `${role} offered ${action.path}`).toBe(true)
          }
        }
      }
    }
  })

  /**
   * Draft review is the one place the GM is excluded rather than added (Unit 23a), and it is
   * asserted directly because "the GM can do anything" is the assumption everything else here
   * encodes. If `GM_OR` ever goes back on `draft/pm-approve` / `draft/pm-return`, this fails and
   * asks why — which is the point.
   */
  it('withholds draft approval and return from the GM, who is a superuser everywhere else', () => {
    const gmInDrafting = paths('DRAFT_IN_PROGRESS', 'GM')
    expect(gmInDrafting).not.toContain('draft/pm-approve')
    expect(gmInDrafting).not.toContain('draft/pm-return')
    // Still the superuser on the rest of the same stage, so this is an exclusion and not a
    // role that lost the screen.
    expect(gmInDrafting).toContain('hold')
    expect(paths('READY_TO_SEND', 'GM')).toContain('draft/send-to-client')

    // Draft review is its own stage now, so the two rulings live there rather than inside the
    // CM's drafting stage. The exclusion above is unchanged and is the point of this test.
    expect(paths('DRAFT_REVIEW', 'GM')).not.toContain('draft/pm-approve')
    const pmInReview = paths('DRAFT_REVIEW', 'PROJECT_MANAGER')
    expect(pmInReview).toContain('draft/pm-approve')
    expect(pmInReview).toContain('draft/pm-return')
  })

  it('offers a case in an exception state only its way out', () => {
    const onHold = paths('DRAFT_IN_PROGRESS', 'PROJECT_COORDINATOR', 'ON_HOLD_AWAITING_CLIENT')
    expect(onHold).toEqual(['resume'])

    const rematching = paths('EXPERT_SIGNING', 'PROJECT_MANAGER', 'EXPERT_DECLINED_REMATCHING')
    expect(rematching).toEqual(['reassign-expert'])

    // One exception at a time: a case on hold cannot also be sent to refund.
    expect(onHold).not.toContain('refund/request')
    expect(onHold).not.toContain('hold')
  })

  it('keeps the two refund rulings GM-only, not GM-also', () => {
    const gm = paths('DRAFT_IN_PROGRESS', 'GM', 'REFUND_REQUESTED')
    expect(gm).toEqual(['refund/approve', 'refund/deny'])

    for (const role of CASE_ROLES.filter((candidate) => candidate !== 'GM')) {
      expect(paths('DRAFT_IN_PROGRESS', role, 'REFUND_REQUESTED')).toEqual([])
    }
  })

  it('pins the docs-complete roles to the backend gate', () => {
    // The one transition the checklist screen drives, and the seam that leaked: the screen was
    // widened to the Brand Manager without the action being widened with it, so they got an
    // enabled button the server answered 403 on. navigation.test.ts pins the screen's own role
    // list to COORDINATION; this pins the action's, one layer down.
    //
    // **This list must equal CaseController.docsComplete's @PreAuthorize**, minus the GM, whom
    // actionsFor adds everywhere.
    const docsComplete = QUICK_ACTIONS.find((action) => action.path === 'docs-complete')
    expect(docsComplete?.roles).toEqual(['BRAND_MANAGER', 'PROJECT_COORDINATOR', 'PROJECT_MANAGER'])

    // And every role that can open the checklist screen can finish what the screen is for.
    for (const role of ['GM', 'BRAND_MANAGER', 'PROJECT_COORDINATOR'] as const) {
      expect(paths('DOC_COLLECTION', role), role).toContain('docs-complete')
    }
  })

  it('gives the GM every action their stage allows', () => {
    // GM is a superuser on every transition, so the board must not be where that stops.
    expect(paths('DOC_COLLECTION', 'GM')).toContain('docs-complete')
    expect(paths('READY_TO_DELIVER', 'GM')).toContain('deliver')
    expect(paths('DELIVERED', 'GM')).toContain('close')
  })

  it('declares every action against a stage it can actually be reached from', () => {
    // A typo'd stage list would silently make an action unreachable forever.
    // Every stage a column draws, plus DELIVERED — `close` runs from there and it is
    // deliberately not a column (an outcome lane only grows).
    const known = new Set<Stage>([...STAGE_COLUMNS.flatMap(({ stages }) => stages), 'DELIVERED'])
    for (const action of QUICK_ACTIONS) {
      for (const stage of action.stages ?? []) {
        expect(known.has(stage), `${action.path} names unknown stage ${stage}`).toBe(true)
      }
    }
  })

  it('offers no way to record a payment', () => {
    // Case Creation v2.0: the case arrives paid from a won GHL opportunity, and the route this
    // used to call is gone — so an action here would be a button that can only ever 404.
    expect(QUICK_ACTIONS.map((action) => action.path)).not.toContain('mark-paid')
    for (const action of QUICK_ACTIONS) {
      for (const field of action.fields ?? []) {
        expect(field.name, `${action.path} still collects a payment amount`).not.toBe('dealValue')
      }
    }
  })

  it('gives every input-taking action a field, and every picker a source', () => {
    for (const action of QUICK_ACTIONS) {
      for (const field of action.fields ?? []) {
        expect(field.label.length, `${action.path}/${field.name} has no label`).toBeGreaterThan(0)
        if (field.kind === 'member') {
          // A member picker with no role would fall through to the expert list.
          expect(field.memberRole, `${action.path}/${field.name} has no memberRole`).toBeDefined()
        }
      }
    }
  })
})

describe('dueBeforeFor', () => {
  const noon = new Date('2026-07-30T12:00:00Z')

  it('includes the whole of the last day in the window', () => {
    // End-of-day, not now: a one-week window has to include a case due at 5pm on the seventh day.
    //
    // This used to pass 'today', which is no longer a deadline window — the board's horizon is
    // now its own three-value type, and 'today' only ever worked here by falling through every
    // branch to end-of-today. The assertion is unchanged because the behaviour is.
    const week = new Date(dueBeforeFor('week', noon))
    expect(week.getHours()).toBe(23)
    expect(week.getMinutes()).toBe(59)
  })

  it('widens with the window and never narrows', () => {
    const windows = DEADLINE_WINDOWS.map((option) =>
      new Date(dueBeforeFor(option.value, noon)).getTime(),
    )
    const sorted = [...windows].sort((a, b) => a - b)
    // Driven off DEADLINE_WINDOWS rather than a literal list, so a window added to the control
    // without a place in this ordering fails here instead of going unchecked.
    expect(windows).toEqual(sorted)
    expect(new Set(windows).size).toBe(windows.length)
  })

  it('clamps a month-end date instead of overflowing into the month after next', () => {
    // The original assertion here was only "later than now", which 3 March satisfies just as
    // well as 28 February — so it passed while `setMonth` overflowed and quietly widened the
    // window by three days. Pin the month.
    const janEnd = new Date('2026-01-31T12:00:00Z')
    expect(new Date(dueBeforeFor('month', janEnd)).getMonth()).toBe(1) // February, not March
  })

  it('clamps a leap day rolled forward a year', () => {
    const leapDay = new Date('2028-02-29T12:00:00Z')
    const oneYearOn = new Date(dueBeforeFor('year', leapDay))
    expect(oneYearOn.getFullYear()).toBe(2029)
    expect(oneYearOn.getMonth()).toBe(1) // still February, not 1 March
    expect(oneYearOn.getDate()).toBe(28)
  })
})
