import { describe, expect, it } from 'vitest'
import type { ChecklistCaseRow } from './pmMetricsApi'
import { feeText, OFFER_LABEL, segmentPercents } from './cmWorkRules'

const row = (overrides: Partial<ChecklistCaseRow>): ChecklistCaseRow => ({
  caseId: 'c', caseCode: 'IE-1', total: 0, approved: 0, uploaded: 0, required: 0, missing: 0, incorrect: 0,
  ...overrides,
})

describe('cmWorkRules', () => {
  it('drops empty segments and the rest add up to the whole bar', () => {
    const segments = segmentPercents(row({ total: 4, approved: 2, missing: 1, incorrect: 1 }))
    expect(segments.map((s) => s.key)).toEqual(['approved', 'missing', 'incorrect'])
    expect(segments.reduce((sum, s) => sum + s.pct, 0)).toBe(100)
  })
  it('a case with no items has no bar at all, not a 0-wide one', () => {
    expect(segmentPercents(row({ total: 0 }))).toEqual([])
  })
  it('an unpriced offer is a dash, never 0, and a brand with no currency shows the bare number', () => {
    expect(feeText(null, 'USD')).toBe('—')
    expect(feeText(350, 'USD')).toBe('$350.00')
    expect(feeText(350, null)).toBe('350')
  })
  it('every outcome has words, so status is never colour alone', () => {
    expect(OFFER_LABEL.OFFERED).toBe('Waiting for expert')
    expect(Object.keys(OFFER_LABEL)).toHaveLength(5)
  })
})
