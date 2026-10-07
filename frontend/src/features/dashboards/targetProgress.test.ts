import { describe, expect, it } from 'vitest'
import { progressPct } from './targetProgress'

describe('progressPct', () => {
  it('is null when no target is set, so "not set" never reads as 0%', () => {
    expect(progressPct(900, null)).toBeNull()
  })
  it('is null for a zero target rather than dividing by zero', () => {
    expect(progressPct(900, 0)).toBeNull()
  })
  it('rounds to a whole percent', () => {
    expect(progressPct(900, 1000)).toBe(90)
    expect(progressPct(7, 20)).toBe(35)
  })
  it('does not cap a member who beat their target', () => {
    expect(progressPct(1500, 1000)).toBe(150)
  })
})
