import { describe, expect, it } from 'vitest'
import type { Role } from '../../lib/session'
import {
  QUICK_ACTIONS,
  STAGE_ACCESS,
  STAGE_COLUMNS,
  actionsFor,
  allInsideSla,
  columnsFor,
  dueBeforeFor,
  slaMix,
  type BoardCard,
  type ExceptionState,
  type Stage,
} from './boardRules'

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

function card(overrides: Partial<BoardCard> = {}): BoardCard {
  return {
    id: 'c1',
    caseCode: 'IE-2026-0001',
    clientName: 'Anita Rao',
    serviceType: 'CREDENTIAL_EVALUATION',
    deadline: null,
    slaStatus: 'ON_TRACK',
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
    for (const role of ALL_ROLES) {
      for (const { stage } of STAGE_COLUMNS) {
        expect(STAGE_ACCESS[role]?.[stage], `${role} / ${stage}`).toBeDefined()
      }
    }
  })

  it('leaves every role at least one column they can work', () => {
    // A role whose every cell was `status` or `none` would have a board they can only stare
    // at, which is a table typo rather than a design.
    for (const role of ALL_ROLES) {
      const worked = STAGE_COLUMNS.filter(({ stage }) => STAGE_ACCESS[role][stage] === 'full')
      expect(worked.length, `${role} works no stage`).toBeGreaterThan(0)
    }
  })

  it('hides the two stages a Case Manager is never responsible for', () => {
    // A case naming them as CM has already left doc collection, and delivery is the
    // Coordinator's stage. Both are empty by scope anyway; this stops them being drawn.
    expect(columnsFor('CASE_MANAGER').map((column) => column.stage)).toEqual([
      'EXPERT_ASSIGNMENT',
      'DRAFT_GENERATION',
      'EXPERT_SIGNING',
    ])
  })

  it('gives the oversight roles the whole pipeline', () => {
    for (const role of ['GM', 'BRAND_MANAGER'] as const) {
      expect(columnsFor(role)).toHaveLength(STAGE_COLUMNS.length)
      expect(columnsFor(role).every((column) => column.access === 'full')).toBe(true)
    }
  })

  it('numbers a column by its place in the whole pipeline, not in the role subset', () => {
    // A Case Manager's first column is stage 2 of 5. Numbering it 1 would say the work starts
    // with them — which is what happens if the `none` cells are filtered before the index.
    expect(columnsFor('CASE_MANAGER').map((column) => column.step)).toEqual([2, 3, 4])
    expect(columnsFor('GM').map((column) => column.step)).toEqual([1, 2, 3, 4, 5])
  })

  it('marks the Coordinator watching the middle of the pipeline and working both ends', () => {
    const access = Object.fromEntries(
      columnsFor('PROJECT_COORDINATOR').map((column) => [column.stage, column.access]),
    )
    expect(access).toEqual({
      DOC_COLLECTION: 'full',
      EXPERT_ASSIGNMENT: 'status',
      DRAFT_GENERATION: 'status',
      EXPERT_SIGNING: 'status',
      FINAL_DELIVERY: 'full',
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
    expect(paths('EXPERT_ASSIGNMENT', 'PROJECT_MANAGER')).toContain('assign-cm')
    expect(paths('DRAFT_GENERATION', 'CASE_MANAGER')).toContain('draft/submit')
    expect(paths('EXPERT_SIGNING', 'PROJECT_MANAGER')).toContain('qc-approve')
    expect(paths('FINAL_DELIVERY', 'PROJECT_COORDINATOR')).toContain('deliver')
  })

  it('withholds a stage action from a role that only watches that stage', () => {
    // The Coordinator can see the draft column but does not submit or approve drafts.
    expect(STAGE_ACCESS.PROJECT_COORDINATOR.DRAFT_GENERATION).toBe('status')
    expect(paths('DRAFT_GENERATION', 'PROJECT_COORDINATOR')).not.toContain('draft/send-to-client')
    // The PM watches delivery rather than running it.
    expect(paths('FINAL_DELIVERY', 'PROJECT_MANAGER')).not.toContain('deliver')
    expect(paths('FINAL_DELIVERY', 'PROJECT_MANAGER')).not.toContain('close')
  })

  it('keeps the stage-preserving actions for a watching role', () => {
    // "Status" means you do not advance the stage, not that you are powerless: a Coordinator
    // watching a stalled draft can still put the case on hold.
    const watching = paths('DRAFT_GENERATION', 'PROJECT_COORDINATOR')
    expect(watching).toContain('hold')
    expect(watching).toContain('refund/request')
  })

  it('never offers a role an action its route would refuse', () => {
    // The client half of the table is only useful if it agrees with the server's gate.
    for (const role of ALL_ROLES) {
      for (const { stage } of STAGE_COLUMNS) {
        for (const action of actionsFor(card({ currentStage: stage }), role)) {
          const admitted = action.gmOnly ? role === 'GM' : role === 'GM' || action.roles.includes(role)
          expect(admitted, `${role} offered ${action.path}`).toBe(true)
        }
      }
    }
  })

  it('offers a case in an exception state only its way out', () => {
    const onHold = paths('DRAFT_GENERATION', 'PROJECT_COORDINATOR', 'ON_HOLD_AWAITING_CLIENT')
    expect(onHold).toEqual(['resume'])

    const rematching = paths('EXPERT_SIGNING', 'PROJECT_MANAGER', 'EXPERT_DECLINED_REMATCHING')
    expect(rematching).toEqual(['reassign-expert'])

    // One exception at a time: a case on hold cannot also be sent to refund.
    expect(onHold).not.toContain('refund/request')
    expect(onHold).not.toContain('hold')
  })

  it('keeps the two refund rulings GM-only, not GM-also', () => {
    const gm = paths('DRAFT_GENERATION', 'GM', 'REFUND_REQUESTED')
    expect(gm).toEqual(['refund/approve', 'refund/deny'])

    for (const role of ALL_ROLES.filter((candidate) => candidate !== 'GM')) {
      expect(paths('DRAFT_GENERATION', role, 'REFUND_REQUESTED')).toEqual([])
    }
  })

  it('gives the GM every action their stage allows', () => {
    // GM is a superuser on every transition, so the board must not be where that stops.
    expect(paths('DOC_COLLECTION', 'GM')).toContain('docs-complete')
    expect(paths('FINAL_DELIVERY', 'GM')).toContain('close')
  })

  it('declares every action against a stage it can actually be reached from', () => {
    // A typo'd stage list would silently make an action unreachable forever.
    const known = new Set(STAGE_COLUMNS.map(({ stage }) => stage))
    for (const action of QUICK_ACTIONS) {
      for (const stage of action.stages ?? []) {
        expect(known.has(stage), `${action.path} names unknown stage ${stage}`).toBe(true)
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
    // End-of-day, not now: "today" has to include a case due this afternoon.
    const today = new Date(dueBeforeFor('today', noon))
    expect(today.getHours()).toBe(23)
    expect(today.getMinutes()).toBe(59)
  })

  it('widens with the range and never narrows', () => {
    const windows = (['today', 'week', 'month', 'year'] as const).map((range) =>
      new Date(dueBeforeFor(range, noon)).getTime(),
    )
    const sorted = [...windows].sort((a, b) => a - b)
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
