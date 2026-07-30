import { describe, expect, it } from 'vitest'
import {
  agingBand,
  agingHours,
  agingLabel,
  applyChecklistToCard,
  completionPercent,
  needsChase,
  splitByChase,
  type ChecklistCard,
  type ChecklistView,
} from './checklistRules'

/**
 * The two judgements this screen makes that the server does not: how old a wait is, and
 * whether a chase is due. Both are off-by-one country — a wrong boundary either nags a client
 * who was contacted an hour ago or hides a case that has been stuck for two days.
 */

const NOW = new Date('2026-07-30T12:00:00Z')

/** Hours before NOW, as an ISO string. */
function hoursAgo(hours: number): string {
  return new Date(NOW.getTime() - hours * 60 * 60 * 1000).toISOString()
}

function card(overrides: Partial<ChecklistCard> = {}): ChecklistCard {
  return {
    id: 'c1',
    caseCode: 'IE-2026-0001',
    clientName: 'Anita Rao',
    serviceType: 'CREDENTIAL_EVALUATION',
    deadline: null,
    slaStatus: 'ON_TRACK',
    exceptionState: 'NONE',
    stageEnteredAt: hoursAgo(30),
    assignedCoordinator: null,
    paid: true,
    total: 4,
    complete: 2,
    checklistSatisfied: false,
    lastChasedAt: null,
    ...overrides,
  }
}

function view(overrides: Partial<ChecklistView> = {}): ChecklistView {
  return {
    caseId: 'c1',
    driveLink: 'https://drive.example/abc',
    items: [],
    total: 4,
    complete: 2,
    checklistSatisfied: false,
    lastChasedAt: null,
    ...overrides,
  }
}

describe('applyChecklistToCard', () => {
  it('takes the four derived fields from the refreshed checklist and leaves the rest alone', () => {
    const patched = applyChecklistToCard(
      card({ total: 4, complete: 2, checklistSatisfied: false, lastChasedAt: null }),
      view({ total: 5, complete: 5, checklistSatisfied: true, lastChasedAt: hoursAgo(1) }),
    )

    expect(patched.total).toBe(5)
    expect(patched.complete).toBe(5)
    expect(patched.checklistSatisfied).toBe(true)
    expect(patched.lastChasedAt).toBe(hoursAgo(1))

    // Identity and ranking are the board's, not the panel's — a write to one case's documents
    // must not be able to re-sort the queue or rename a row.
    expect(patched.id).toBe('c1')
    expect(patched.caseCode).toBe('IE-2026-0001')
    expect(patched.stageEnteredAt).toBe(hoursAgo(30))
    expect(patched.paid).toBe(true)
  })

  it('retires the row from the pending-docs queue when the last document arrives', () => {
    // The defect this exists to stop: a status change refreshed the open panel only, so a
    // finished case sat under "Due a chase" showing a stale fraction until the next full reload.
    const waiting = card({ stageEnteredAt: hoursAgo(30), checklistSatisfied: false })
    expect(needsChase(waiting, NOW)).toBe(true)

    const finished = applyChecklistToCard(
      waiting,
      view({ total: 4, complete: 4, checklistSatisfied: true }),
    )
    expect(needsChase(finished, NOW)).toBe(false)
    expect(splitByChase([finished], NOW).chase).toHaveLength(0)
  })

  it('resets the 24-hour clock when the write was a chase', () => {
    // Still unsatisfied here, so the row leaves the queue on the chase clock alone.
    const waiting = card({ stageEnteredAt: hoursAgo(30), lastChasedAt: null })
    expect(needsChase(waiting, NOW)).toBe(true)

    const chased = applyChecklistToCard(waiting, view({ lastChasedAt: hoursAgo(1) }))
    expect(chased.checklistSatisfied).toBe(false)
    expect(needsChase(chased, NOW)).toBe(false)
  })
})

describe('aging', () => {
  it('measures wall-clock hours since the case entered the stage', () => {
    expect(agingHours(hoursAgo(30), NOW)).toBeCloseTo(30)
    expect(agingHours(null, NOW)).toBeNull()
    expect(agingHours('not a date', NOW)).toBeNull()
  })

  it('never reports a negative wait for a clock that is slightly ahead', () => {
    // Server and browser clocks disagree by seconds all the time; "-0h waiting" is nonsense.
    expect(agingHours(hoursAgo(-0.5), NOW)).toBe(0)
  })

  it('bands at the spec boundaries, amber past 24h and red past 48h', () => {
    expect(agingBand(agingHours(hoursAgo(1), NOW))).toBe('fresh')
    // Exactly 24 is still fresh: the band is "past 24 hours", not "24 or more".
    expect(agingBand(24)).toBe('fresh')
    expect(agingBand(24.1)).toBe('warn')
    expect(agingBand(48)).toBe('warn')
    expect(agingBand(48.1)).toBe('late')
  })

  it('keeps an untimed case out of the green band rather than calling it healthy', () => {
    // The same reasoning `slaMix` gives for its separate `unknown` band: a case we cannot
    // time is not a case that is doing well, and green would report a stall as fine.
    expect(agingBand(null)).toBe('unknown')
    expect(agingLabel(null)).toBe('not started')
  })

  it('switches from hours to days once nobody is triaging on the hour', () => {
    expect(agingLabel(31.8)).toBe('31h')
    expect(agingLabel(48)).toBe('2d')
    expect(agingLabel(73)).toBe('3d')
  })
})

describe('the pending-docs queue', () => {
  it('holds a case waiting more than a day with nothing sent', () => {
    expect(needsChase(card({ stageEnteredAt: hoursAgo(30) }), NOW)).toBe(true)
  })

  it('leaves a case that arrived this morning alone', () => {
    expect(needsChase(card({ stageEnteredAt: hoursAgo(3) }), NOW)).toBe(false)
    // The boundary itself is not yet due, matching the aging band.
    expect(needsChase(card({ stageEnteredAt: hoursAgo(24) }), NOW)).toBe(false)
  })

  it('goes quiet for a day after a chase, then asks again', () => {
    // Otherwise the queue nags the Coordinator about a client contacted an hour ago, and the
    // one action this screen offers stops emptying it.
    expect(needsChase(card({ lastChasedAt: hoursAgo(2) }), NOW)).toBe(false)
    expect(needsChase(card({ lastChasedAt: hoursAgo(26) }), NOW)).toBe(true)
  })

  it('never chases a case whose documents are all in', () => {
    // It needs pushing to the PM, not chasing — and the client has done nothing wrong.
    expect(needsChase(card({ checklistSatisfied: true, stageEnteredAt: hoursAgo(200) }), NOW)).toBe(false)
  })

  it('leaves an untimed case out of the queue rather than at the top of it', () => {
    expect(needsChase(card({ stageEnteredAt: null }), NOW)).toBe(false)
  })

  it('splits without re-sorting, so the server order survives in both halves', () => {
    const stale = card({ id: 'stale', stageEnteredAt: hoursAgo(100) })
    const fresh = card({ id: 'fresh', stageEnteredAt: hoursAgo(2) })
    const alsoStale = card({ id: 'also-stale', stageEnteredAt: hoursAgo(50) })

    const { chase, rest } = splitByChase([stale, fresh, alsoStale], NOW)

    expect(chase.map((c) => c.id)).toEqual(['stale', 'also-stale'])
    expect(rest.map((c) => c.id)).toEqual(['fresh'])
  })
})

describe('the completeness bar', () => {
  it('draws the fraction it is given', () => {
    expect(completionPercent(3, 4)).toBe(75)
    expect(completionPercent(4, 4)).toBe(100)
  })

  it('draws an empty checklist as empty, never as full', () => {
    // 0/0 is not "everything is in" — it is a case whose intake template produced nothing,
    // which is exactly what markDocsComplete refuses. A full bar would say the opposite.
    expect(completionPercent(0, 0)).toBe(0)
  })
})
