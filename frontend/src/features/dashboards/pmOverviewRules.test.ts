import { describe, expect, it } from 'vitest'
import { ageText, barPercent, FUNNEL_STAGES, stageHref } from './pmOverviewRules'

describe('pmOverviewRules', () => {
  it('funnel is the eleven stages without CLOSED, in pipeline order', () => {
    expect(FUNNEL_STAGES).toHaveLength(11)
    expect(FUNNEL_STAGES[0]).toBe('DOC_COLLECTION')
    expect(FUNNEL_STAGES.at(-1)).toBe('DELIVERED')
    expect(FUNNEL_STAGES).not.toContain('CLOSED')
  })
  it('a non-empty stage always has a visible bar, an empty one none', () => {
    expect(barPercent(1, 200)).toBeGreaterThanOrEqual(2)
    expect(barPercent(0, 200)).toBe(0)
    expect(barPercent(5, 0)).toBe(0)
    expect(barPercent(200, 200)).toBe(100)
  })
  it('age is hours, and unknown is a dash rather than 0h', () => {
    expect(ageText(null)).toBe('—')
    expect(ageText(0)).toBe('0h')
    expect(ageText(31)).toBe('31h')
  })
  it('delivered cases are not on the board, so that row does not link to the inbox', () => {
    expect(stageHref('DELIVERED')).toBeNull()
    expect(stageHref('FINAL_QC')).toBe('/inbox?stage=FINAL_QC')
  })
})
