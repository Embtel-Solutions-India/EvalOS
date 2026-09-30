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
