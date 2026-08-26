import { describe, expect, it } from 'vitest'
import { rangeLabel, rangeParams, sameRange, DEFAULT_RANGE, type DateRange } from './filtersContext'

/**
 * The three helpers every consumer of the period filter goes through.
 *
 * These are small, and they are tested because each one is the single place a whole class of bug
 * would live: a wrong query parameter set silently changes what the server answers, a wrong label
 * makes a heading disagree with the figures under it, and a wrong equality lights the wrong button.
 */
describe('rangeParams', () => {
  it('sends only the range name for a named period', () => {
    // **The absence of from/to is the assertion.** The server refuses explicit dates on a named
    // range with a 400 rather than ignoring them, so leaking a stale custom `from` alongside
    // `month` would break the request outright.
    expect(rangeParams({ kind: 'month' })).toEqual({ range: 'month' })
    expect(rangeParams({ kind: 'last-year' })).toEqual({ range: 'last-year' })
  })

  it('hyphenates the completed periods exactly as the server parses them', () => {
    // `last_month` or `lastMonth` would be refused by `DateRange.parse`. One spelling, both sides.
    expect(rangeParams({ kind: 'last-month' }).range).toBe('last-month')
  })

  it('sends both edges for a custom period', () => {
    expect(rangeParams({ kind: 'custom', from: '2026-01-01', to: '2026-03-31' })).toEqual({
      range: 'custom',
      from: '2026-01-01',
      to: '2026-03-31',
    })
  })
})

describe('rangeLabel', () => {
  it('labels the named periods the way the buttons do', () => {
    expect(rangeLabel({ kind: 'week' })).toBe('This week')
    expect(rangeLabel({ kind: 'last-month' })).toBe('Last month')
  })

  it('labels a custom period with its actual dates', () => {
    // Not "Custom": a heading has to say which period the figures under it cover, and every
    // custom range would otherwise render the same word over different numbers.
    expect(rangeLabel({ kind: 'custom', from: '2026-01-01', to: '2026-03-31' })).toContain('2026-01-01')
    expect(rangeLabel({ kind: 'custom', from: '2026-01-01', to: '2026-03-31' })).toContain('2026-03-31')
  })

  it('has a label for every named period', () => {
    // A missing entry would render `undefined` in a heading. Driven off the union via a list that
    // fails to compile if a member is added without a label.
    const all: DateRange[] = [
      { kind: 'today' },
      { kind: 'week' },
      { kind: 'month' },
      { kind: 'year' },
      { kind: 'last-month' },
      { kind: 'last-year' },
      { kind: 'custom', from: '2026-01-01', to: '2026-01-02' },
    ]
    for (const range of all) {
      expect(rangeLabel(range), range.kind).toBeTruthy()
      expect(rangeLabel(range)).not.toContain('undefined')
    }
  })
})

describe('sameRange', () => {
  it('distinguishes two custom periods', () => {
    // The reason this is not `===`: two custom ranges are different questions, and treating them
    // as one would light the wrong control and — before the server was keyed on the window — could
    // have served one period's figures for the other.
    const january: DateRange = { kind: 'custom', from: '2026-01-01', to: '2026-01-31' }
    const march: DateRange = { kind: 'custom', from: '2026-03-01', to: '2026-03-31' }

    expect(sameRange(january, january)).toBe(true)
    expect(sameRange(january, march)).toBe(false)
  })

  it('matches named periods by name and never across kinds', () => {
    expect(sameRange({ kind: 'month' }, { kind: 'month' })).toBe(true)
    expect(sameRange({ kind: 'month' }, { kind: 'last-month' })).toBe(false)
    expect(sameRange({ kind: 'month' }, { kind: 'custom', from: '2026-01-01', to: '2026-01-02' })).toBe(false)
  })
})

describe('the default period', () => {
  it('is this month, not this year', () => {
    // Pinned because it has been changed twice and reverted once. It no longer affects the
    // production board — that owns its own forward filter — but a dashboard opening on a
    // twelve-month window is still one the reader has to narrow before it answers anything.
    expect(DEFAULT_RANGE).toEqual({ kind: 'month' })
  })
})
