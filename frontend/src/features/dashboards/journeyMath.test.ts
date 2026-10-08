import { describe, expect, it } from 'vitest'
import { achievementPct, changePct, remaining, targetMonthIndex } from './journeyMath'

describe('journeyMath', () => {
  it('never reports a change from nothing', () => {
    expect(changePct(5, 0)).toBeNull()
    expect(changePct(5, undefined)).toBeNull()
    expect(changePct(15, 10)).toBe(50)
    expect(changePct(5, 10)).toBe(-50)
  })

  it('treats a missing target as not set, not as zero', () => {
    expect(achievementPct(100, null)).toBeNull()
    expect(achievementPct(100, 0)).toBeNull()
    expect(remaining(100, null)).toBeNull()
  })

  it('lets a beaten target exceed 100% and floors what remains at zero', () => {
    expect(achievementPct(130, 100)).toBe(130)
    expect(remaining(130, 100)).toBe(0)
    expect(remaining(40, 100)).toBe(60)
  })

  it('reads the target month off the date string', () => {
    expect(targetMonthIndex('2026-10-01')).toBe(10)
  })
})
