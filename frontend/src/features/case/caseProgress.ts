import type { Stage } from '../board/boardRules'
import type { TimelineEntry } from './caseApi'

/**
 * The case's path through the twelve stages, derived from the timeline the page already loads
 * (Unit 66). Each entry's `stage` is the stage *after* the event (`CaseTimelineService`), so a
 * change of stage between consecutive entries is an entry into that stage. No second record.
 */

export const STAGE_ORDER: readonly Stage[] = [
  'DOC_COLLECTION', 'PM_REVIEW', 'DRAFT_IN_PROGRESS', 'DRAFT_REVIEW', 'READY_TO_SEND', 'CLIENT_REVIEW',
  'CLIENT_APPROVAL', 'EXPERT_SIGNING', 'FINAL_QC', 'READY_TO_DELIVER', 'DELIVERED', 'CLOSED',
]

export const STAGE_SHORT: Record<Stage, string> = {
  DOC_COLLECTION: 'Docs', PM_REVIEW: 'PM review', DRAFT_IN_PROGRESS: 'Drafting', DRAFT_REVIEW: 'Draft review',
  READY_TO_SEND: 'Ready to send', CLIENT_REVIEW: 'Client review', CLIENT_APPROVAL: 'Client approved',
  EXPERT_SIGNING: 'Signing', FINAL_QC: 'Final QC', READY_TO_DELIVER: 'Ready', DELIVERED: 'Delivered', CLOSED: 'Closed',
}

export type StepState = 'reached' | 'current' | 'not-reached'
export type Step = {
  stage: Stage
  state: StepState
  firstAt: string | null
  lastAt: string | null
  visits: number
  spentMs: number
  /** The note on the entry that last moved the case INTO this stage — a return's reason. */
  lastNote: string | null
}

export function progress(entries: readonly TimelineEntry[], current: Stage, now: Date): Step[] {
  const steps = new Map<Stage, Step>(
    STAGE_ORDER.map((stage) => [
      stage,
      { stage, state: 'not-reached', firstAt: null, lastAt: null, visits: 0, spentMs: 0, lastNote: null },
    ]),
  )
  let open = null as { stage: Stage; at: number } | null
  for (const entry of entries) {
    const step = entry.stage ? steps.get(entry.stage as Stage) : undefined
    if (!step || open?.stage === step.stage) continue
    const at = Date.parse(entry.at)
    if (open) steps.get(open.stage)!.spentMs += at - open.at
    step.visits += 1
    step.firstAt ??= entry.at
    step.lastAt = entry.at
    step.lastNote = entry.note
    open = { stage: step.stage, at }
  }
  if (open && open.stage !== 'CLOSED') steps.get(open.stage)!.spentMs += now.getTime() - open.at

  return STAGE_ORDER.map((stage) => {
    const step = steps.get(stage)!
    return { ...step, state: stage === current ? 'current' : step.visits > 0 ? 'reached' : 'not-reached' }
  })
}

export function formatSpan(ms: number): string {
  const minutes = Math.floor(ms / 60_000)
  if (minutes < 1) return '<1m'
  const days = Math.floor(minutes / 1440)
  const hours = Math.floor((minutes % 1440) / 60)
  const mins = minutes % 60
  if (days > 0) return hours > 0 ? `${days}d ${hours}h` : `${days}d`
  if (hours > 0) return mins > 0 ? `${hours}h ${mins}m` : `${hours}h`
  return `${mins}m`
}
