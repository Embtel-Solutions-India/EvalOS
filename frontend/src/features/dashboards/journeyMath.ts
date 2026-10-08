import type { Journey, JourneyMonth } from './journeyApi'

/** Whole-percent change against the previous figure; null when there is nothing to compare, never ∞ or 100. */
export function changePct(current: number, previous: number | undefined): number | null {
  if (previous === undefined || previous <= 0) return null
  return Math.round(((current - previous) / previous) * 100)
}

/** Progress as a whole percent, null without a target. Not capped: 130% is a result worth showing. */
export function achievementPct(progress: number, target: number | null): number | null {
  return target === null || target <= 0 ? null : Math.round((progress / target) * 100)
}

/** What is left to hit the target, floored at zero once it is beaten. */
export function remaining(progress: number, target: number | null): number | null {
  return target === null ? null : Math.max(target - progress, 0)
}

/** The month the target is for, as a 1-based index into `months` — read off the API's `YYYY-MM-01`. */
export function targetMonthIndex(month: string): number {
  return Number(month.slice(5, 7))
}

export type Pulse = { current: JourneyMonth | undefined; previous: JourneyMonth | undefined }

/** The target's month and the one before it. January has no previous in this year's payload. */
export function pulse(journey: Journey): Pulse {
  const index = targetMonthIndex(journey.target.month)
  return { current: journey.months[index - 1], previous: journey.months[index - 2] }
}

export const sum = (months: readonly JourneyMonth[], pick: (month: JourneyMonth) => number) =>
  months.reduce((total, month) => total + pick(month), 0)
