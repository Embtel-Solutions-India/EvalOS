import { describe, expect, it } from 'vitest'
import { formatCount, formatMoney } from './money'

describe('formatMoney', () => {
  it('carries the currency symbol', () => {
    expect(formatMoney(86950)).toBe('$86,950')
  })

  it('groups thousands — "$86950" is not how an amount reads', () => {
    expect(formatMoney(1234567)).toBe('$1,234,567')
  })

  /**
   * Cents are ROUNDED away, not truncated — `maximumFractionDigits: 0` rounds half-up, and this
   * asserts both directions so the claim is actually pinned. The previous version of this test
   * said it "drops cents rather than rounding a figure up a whole unit" and only checked `.4`,
   * which rounds down anyway: it passed while asserting the opposite of what the code does.
   *
   * Rounding is the wanted behaviour for a total — truncating every figure would understate a
   * summed column systematically, and always in the same direction. Add `roundingMode: 'trunc'`
   * only if a figure ever needs to be provably not-overstated.
   */
  it('rounds cents away to whole units, in both directions', () => {
    expect(formatMoney(935.4)).toBe('$935')
    expect(formatMoney(935.6)).toBe('$936')
    expect(formatMoney(0)).toBe('$0')
  })
})

describe('formatCount', () => {
  /**
   * A count must never carry a currency symbol: "$93" on a deal *count* reads as a real amount and
   * is false.
   *
   * This is the mistake the two-function split exists to prevent, not one this repo shipped — the
   * card previously rendered a bare `{value ?? 0}` with no currency anywhere in `card.tsx`. An
   * earlier version of this comment credited it to a `KpiCard` regression that is not in the
   * history, which is misleading for whoever next weighs collapsing these back into one function.
   */
  it('never carries a currency symbol', () => {
    expect(formatCount(93)).toBe('93')
    expect(formatCount(0)).toBe('0')
    expect(formatCount(11400)).not.toContain('$')
  })

  it('still groups thousands', () => {
    expect(formatCount(11400)).toBe('11,400')
  })
})
