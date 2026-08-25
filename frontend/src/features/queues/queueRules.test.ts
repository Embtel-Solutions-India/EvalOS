import { describe, expect, it } from 'vitest'
import type { BoardCard, BoardData, Stage } from '../board/boardRules'
import { draftReviewQueue, inboxQueue } from './queueRules'

const NOW = new Date('2026-07-08T12:00:00Z')

function card(overrides: Partial<BoardCard>): BoardCard {
  return {
    id: overrides.caseCode ?? 'id',
    caseCode: 'IE-2026-0001',
    clientName: 'Anita Rao',
    serviceType: 'CREDENTIAL_EVALUATION',
    deadline: null,
    slaStatus: 'ON_TRACK',
    deadlineRisk: 'ON_TRACK',
    currentStage: 'DRAFT_GENERATION',
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

function board(cards: BoardCard[], stage: Stage = 'DRAFT_GENERATION'): BoardData {
  const stages = {
    DOC_COLLECTION: [],
    EXPERT_ASSIGNMENT: [],
    DRAFT_GENERATION: [],
    EXPERT_SIGNING: [],
    FINAL_DELIVERY: [],
  } as BoardData['stages']
  stages[stage] = cards
  return {
    stages,
    exceptions: {
      ON_HOLD_AWAITING_CLIENT: [],
      EXPERT_DECLINED_REMATCHING: [],
      REFUND_REQUESTED: [],
    },
  }
}

describe('inboxQueue', () => {
  it('orders by deadline and puts undated cases last', () => {
    const data = board([
      card({ caseCode: 'late', deadline: '2026-07-20T12:00:00Z' }),
      card({ caseCode: 'none', deadline: null }),
      card({ caseCode: 'soon', deadline: '2026-07-09T12:00:00Z' }),
    ])

    expect(inboxQueue(data, 'all', NOW).map((row) => row.caseCode)).toEqual(['soon', 'late', 'none'])
  })

  it('counts a case as at risk from the deadline band, not the stage SLA', () => {
    const data = board([
      // Comfortable stage budget, dangerous deadline: the case the two clocks disagree about.
      card({ caseCode: 'tight', slaStatus: 'ON_TRACK', deadlineRisk: 'AT_RISK' }),
      card({ caseCode: 'slow-stage', slaStatus: 'OVERDUE', deadlineRisk: 'ON_TRACK' }),
    ])

    expect(inboxQueue(data, 'at-risk', NOW).map((row) => row.caseCode)).toEqual(['tight'])
  })

  it('treats a case already past its deadline as overdue but not as "due today"', () => {
    const data = board([card({ caseCode: 'past', deadline: '2026-07-01T12:00:00Z' })])

    expect(inboxQueue(data, 'overdue', NOW)).toHaveLength(1)
    expect(inboxQueue(data, 'today', NOW)).toHaveLength(0)
  })

  it('includes the whole of the last local day in "due today"', () => {
    // Built from NOW rather than written as a UTC literal, because "today" is the operator's
    // local day, not a UTC one — the same rule `dueBeforeFor` follows. A hardcoded 23:30Z is
    // tomorrow morning for anyone east of UTC, so this test would pass or fail by timezone.
    const endOfLocalToday = new Date(NOW)
    endOfLocalToday.setHours(23, 59, 59, 999)
    const aMinuteBefore = new Date(endOfLocalToday.getTime() - 60_000).toISOString()

    const data = board([card({ caseCode: 'tonight', deadline: aMinuteBefore })])

    expect(inboxQueue(data, 'today', NOW).map((row) => row.caseCode)).toEqual(['tonight'])
  })

  it('puts a case due just after midnight into the week, not into today', () => {
    const startOfLocalTomorrow = new Date(NOW)
    startOfLocalTomorrow.setHours(23, 59, 59, 999)
    const justAfter = new Date(startOfLocalTomorrow.getTime() + 60_000).toISOString()

    const data = board([card({ caseCode: 'tomorrow', deadline: justAfter })])

    expect(inboxQueue(data, 'today', NOW)).toHaveLength(0)
    expect(inboxQueue(data, 'week', NOW).map((row) => row.caseCode)).toEqual(['tomorrow'])
  })

  it('reads unassigned from the pool rather than from a missing case manager', () => {
    const data = board([
      card({ caseCode: 'pooled', poolStatus: 'IN_POOL', assignedCm: null }),
      // Taken out of the pool but not yet naming a CM: not the assignment queue's problem.
      card({ caseCode: 'taken', poolStatus: 'ASSIGNED', assignedCm: null }),
    ])

    expect(inboxQueue(data, 'unassigned', NOW).map((row) => row.caseCode)).toEqual(['pooled'])
  })

  it('sees cases held in an exception lane', () => {
    const data = board([])
    data.exceptions.ON_HOLD_AWAITING_CLIENT = [card({ caseCode: 'held', deadlineRisk: null })]

    expect(inboxQueue(data, 'all', NOW).map((row) => row.caseCode)).toEqual(['held'])
  })
})

describe('draftReviewQueue', () => {
  it('takes only drafts awaiting the PM', () => {
    const data = board([
      card({ caseCode: 'waiting', pmApprovalStatus: 'PENDING' }),
      card({ caseCode: 'approved', pmApprovalStatus: 'APPROVED' }),
      card({ caseCode: 'not-submitted', pmApprovalStatus: null }),
    ])

    expect(draftReviewQueue(data).map((row) => row.caseCode)).toEqual(['waiting'])
  })
})
