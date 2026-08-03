import type { ExpertTier, FieldTag } from './expertRules'

/**
 * The shortlist's wire shape and the pure helpers the panel draws with.
 *
 * Mirrors `web/ExpertShortlistController`. There is deliberately no local scoring here: the
 * ranking is the server's, and a second implementation in the browser is a second answer to
 * "why did this expert come first" — the whole point of sending the breakdown is that there is
 * one.
 */

/** One weighted factor, as the PM is shown it. `earned` of the `weight` was scored. */
export type Factor = { label: string; weight: number; earned: number; why: string }

export type PerformanceFlag = 'SLOW_RESPONSE' | 'QUALITY_ISSUE' | 'DECLINED_CASES' | 'CLIENT_COMPLAINT'

export type ShortlistCard = {
  id: string
  fullName: string | null
  title: string | null
  institution: string | null
  tier: ExpertTier | null
  qualityScore: number | null
  score: number
  factors: Factor[]
  /** Shown as warnings, never folded into the score. The server already drops `DECLINED_CASES`. */
  flags: PerformanceFlag[]
  /** Derived from the cases, not `expert.current_active_count` — which is always 0. */
  activeLoad: number
}

/**
 * `emptyReason` names which factor emptied the list, and is null when it is not empty. Render
 * it verbatim: "no available expert carries the Mechanical Engineering tag" is something the PM
 * can act on, and "no matches" is not.
 */
export type ShortlistView = { experts: ShortlistCard[]; emptyReason: string | null }

/**
 * How much of a factor's weight was earned, as a fraction for the bar.
 *
 * Guarded on both ends. A zero weight would divide by zero and render `NaN%` as a width, which
 * CSS silently drops — so the bar would vanish rather than look wrong, which is the kind of
 * failure nobody reports. The clamp keeps a server that ever over-awards a factor from drawing
 * a bar past its track instead of making the discrepancy visible in the number beside it.
 */
export function factorShare(factor: Factor): number {
  if (factor.weight <= 0) return 0
  return Math.min(1, Math.max(0, factor.earned / factor.weight))
}

/**
 * Whether the breakdown adds up to the score above it.
 *
 * The server builds the score *as* this sum, so this is false only if the two ever disagree —
 * at which point the panel says so rather than showing a total the rows beneath it contradict.
 * A ranking whose arithmetic does not add up gets distrusted, which is the same outcome as no
 * ranking at all.
 */
export function breakdownAddsUp(card: ShortlistCard): boolean {
  return card.factors.reduce((total, factor) => total + factor.earned, 0) === card.score
}

/** The three positions, named rather than numbered — "Best match" is what a PM is looking for. */
export function rankLabel(index: number): string {
  return ['Best match', 'Second', 'Third'][index] ?? `#${index + 1}`
}

/** `MECHANICAL_ENGINEERING` → `Mechanical Engineering`, matching the server's empty-state wording. */
export function tagLabel(tag: FieldTag): string {
  return tag
    .split('_')
    .map((word) => word.charAt(0) + word.slice(1).toLowerCase())
    .join(' ')
}
