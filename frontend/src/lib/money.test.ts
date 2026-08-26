import { describe, expect, it } from 'vitest'
import { formatCount, formatMoney } from './money'

describe('formatMoney', () => {
  it('carries the currency symbol', () => {
    expect(formatMoney(86950)).toBe('$86,950')
  })

  it('groups thousands — "$86950" is not how an amount reads', () => {
    expect(formatMoney(1234567)).toBe('$1,234,567')
  })

  it('drops cents rather than rounding a figure up a whole unit', () => {
    expect(formatMoney(935.4)).toBe('$935')
    expect(formatMoney(0)).toBe('$0')
  })
})

describe('formatCount', () => {
  /**
   * The regression this file exists for. `KpiCard` printed a `$` in front of every value, so a
   * deal count rendered as "$93" — a currency symbol on a quantity, which reads as a real figure
   * and is false. A count must never carry one.
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
