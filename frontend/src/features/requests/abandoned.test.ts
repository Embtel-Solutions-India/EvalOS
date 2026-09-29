import { describe, expect, it } from 'vitest'
import { daysSince } from './abandoned'

describe('daysSince', () => {
  it('counts whole days, rounding down', () => {
    const now = Date.parse('2026-09-28T12:00:00Z')
    expect(daysSince('2026-09-26T12:00:00Z', now)).toBe(2)
    expect(daysSince('2026-09-26T13:00:00Z', now)).toBe(1)
  })
})
