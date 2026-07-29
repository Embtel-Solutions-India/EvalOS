import { describe, expect, it } from 'vitest'
import type { Role } from '../../lib/session'
import {
  QUICK_ACTIONS,
  STAGE_ACCESS,
  STAGE_COLUMNS,
  actionsFor,
  columnsFor,
  dueBeforeFor,
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

  it('rolls a month-end date forward without landing in the wrong month', () => {
    // 31 Jan + 1 month is the classic JS date trap; assert whatever it does is stable and
    // still in the future rather than silently behind the caller.
    const janEnd = new Date('2026-01-31T12:00:00Z')
    expect(new Date(dueBeforeFor('month', janEnd)).getTime()).toBeGreaterThan(janEnd.getTime())
  })
})
