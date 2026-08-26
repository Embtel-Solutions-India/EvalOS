/**
 * The board's own date filter: **how far ahead** to look for deadlines.
 *
 * <p><strong>Its own type because it points the other way.</strong> The board asks "what is due
 * between now and X" — a forward cutoff. The shell's `DateRange` asks "what happened between X and
 * now". Those were one shared value for two units, with `ui-context.md` recording the collision,
 * and it cost a real defect: the shell default was set to `year` for a marketing screen and moved
 * the board's deadline window from one month out to twelve, leaving the production board
 * effectively unfiltered for every role on first load.
 *
 * <p>What forced the split rather than another comment: the shell filter now offers `last-month`,
 * `last-year` and an arbitrary date-to-date interval. None of those is expressible as a "due
 * before" cutoff — a completed past month is not a deadline horizon, and an interval is not a
 * single edge. A shared type where half the values are meaningless to half the callers is worse
 * than two small types, and the compiler now says so: passing a `DateRange` here does not compile.
 */
export type DeadlineWindow = 'week' | 'month' | 'year'

/**
 * The default horizon, and it matches what the board effectively had before this control existed
 * (the shell's `month`). Kept the same so the split changed the board's *ownership* of the filter
 * and not the board's behaviour — two changes at once is how a regression gets attributed to the
 * wrong one.
 */
export const DEFAULT_DEADLINE_WINDOW: DeadlineWindow = 'month'

export const DEADLINE_WINDOWS: readonly { value: DeadlineWindow; label: string }[] = [
  { value: 'week', label: '1 week' },
  { value: 'month', label: '1 month' },
  { value: 'year', label: '1 year' },
]
