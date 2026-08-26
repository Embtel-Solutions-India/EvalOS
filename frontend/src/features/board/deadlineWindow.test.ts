import { describe, expect, it } from 'vitest'
import { DEADLINE_WINDOWS, DEFAULT_DEADLINE_WINDOW } from './deadlineWindow'

/**
 * The board's forward filter, split from the shell's backward one.
 *
 * The split is the point of these tests: what is asserted is that the two vocabularies no longer
 * overlap, so a past period cannot be handed to a deadline cutoff.
 */
describe('the board deadline window', () => {
  it('offers only forward horizons', () => {
    // No `today`, no `last-month`, no custom interval — none of which is a "due before" cutoff.
    // A completed past month as a deadline horizon would return every open case, which is how the
    // board was once left effectively unfiltered.
    expect(DEADLINE_WINDOWS.map((option) => option.value)).toEqual(['week', 'month', 'year'])
  })

  it('defaults to one month, matching what the board had before it owned this filter', () => {
    // The split was meant to change ownership, not behaviour. Two changes at once is how a
    // regression gets blamed on the wrong one.
    expect(DEFAULT_DEADLINE_WINDOW).toBe('month')
  })

  it('shares no value with the shell filter that would mean something else', () => {
    // The two vocabularies overlap on `week`/`month`/`year` and MUST NOT on anything directional.
    // `today` and `last-month` are the dangerous pair: as a deadline horizon the first is
    // near-empty and the second returns every open case. The type already prevents passing one
    // for the other; this asserts the *sets* stay disjoint where it matters, which the type
    // cannot say.
    const forward: string[] = DEADLINE_WINDOWS.map((option) => option.value)
    for (const backwardOnly of ['today', 'last-month', 'last-year', 'custom']) {
      expect(forward, backwardOnly).not.toContain(backwardOnly)
    }
  })
})
