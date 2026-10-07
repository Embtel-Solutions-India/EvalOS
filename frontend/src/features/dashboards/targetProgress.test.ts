import { describe, expect, it } from 'vitest'
import { progressPct, targetInput, targetState } from './targetProgress'

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

describe('targetState', () => {
  it('is unknown while the targets are still loading or failed to load, never "not set"', () => {
    expect(targetState(null, false)).toBe('unknown')
    expect(targetState(500, false)).toBe('unknown')
  })
  it('is unset only once the targets are known and this desk has none', () => {
    expect(targetState(null, true)).toBe('unset')
  })
  it('keeps a zero target as a target', () => {
    expect(targetState(0, true)).toBe('set')
  })
})

describe('targetInput', () => {
  it('lets a Sales target keep and take cents, and starts from the exact stored amount', () => {
    expect(targetInput('SALES', 1500.5)).toEqual({ step: '0.01', initial: '1500.5' })
  })
  it('keeps a Marketing target to whole leads', () => {
    expect(targetInput('MARKETING', 40)).toEqual({ step: '1', initial: '40' })
  })
  it('starts empty when nothing is set', () => {
    expect(targetInput('SALES', null).initial).toBe('')
  })
})
