import { describe, expect, it } from 'vitest'
import { asDeadlineWindow, DEADLINE_WINDOWS, DEFAULT_DEADLINE_WINDOW } from './deadlineWindow'

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

  it('refuses anything that is not one of its own values', () => {
    expect(asDeadlineWindow('month')).toBe('month')
    // The shell's period names must NOT be accepted here even where they happen to spell the same
    // word — `last-month` and `today` are the ones that would silently mean something else.
    expect(asDeadlineWindow('last-month')).toBeNull()
    expect(asDeadlineWindow('today')).toBeNull()
    expect(asDeadlineWindow('custom')).toBeNull()
    expect(asDeadlineWindow(null)).toBeNull()
    expect(asDeadlineWindow('')).toBeNull()
  })
})
