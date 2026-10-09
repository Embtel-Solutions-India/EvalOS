import { STAGE_ORDER } from '../case/caseProgress'
import type { Stage } from '../board/boardRules'

/** The funnel draws every stage but CLOSED, so its rows reconcile with "active" plus delivered. */
export const FUNNEL_STAGES: readonly Stage[] = STAGE_ORDER.filter((stage) => stage !== 'CLOSED')

/** Bar width in percent. A non-empty stage is never invisible; an empty one has no bar. */
export function barPercent(count: number, max: number): number {
  if (count <= 0 || max <= 0) return 0
  return Math.max(2, Math.round((count / max) * 100))
}

/** Business hours, not clock hours — the funnel's note says so. Unknown is not zero. */
export function ageText(hours: number | null): string {
  return hours === null ? '—' : `${hours}h`
}

/** Drill target. `/api/cases/board` omits DELIVERED, so that row cannot open an inbox. */
export function stageHref(stage: Stage): string | null {
  return stage === 'DELIVERED' ? null : `/inbox?stage=${stage}`
}
