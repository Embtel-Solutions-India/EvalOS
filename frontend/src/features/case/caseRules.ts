import {
  STAGE_NEXT_ACTION,
  STAGE_OWNER,
  type ExceptionState,
  type QuickAction,
  type Stage,
} from '../board/boardRules'
import { ROLE_LABELS, type Role } from '../../lib/session'

/**
 * The case page's small decisions (Unit 66), pure so they are tested without a DOM. Each role list
 * mirrors a server gate named beside it; the server still decides.
 */

/**
 * Transitions out of the stage and ways out of an exception are the header's buttons; the rest —
 * staffing, and any stage-preserving `patch` — go under More.
 */
export function splitActions(actions: readonly QuickAction[]): { primary: QuickAction[]; more: QuickAction[] } {
  const primary = actions.filter(
    (action) => (action.stages !== null && action.method !== 'patch') || action.requiresException !== undefined,
  )
  return { primary, more: actions.filter((action) => !primary.includes(action)) }
}

export function nextStep(stage: Stage, exception: ExceptionState, role: Role): string | null {
  const owner = STAGE_OWNER[stage]
  if (exception !== 'NONE' || !owner) return null
  const todo = STAGE_NEXT_ACTION[stage].toLowerCase()
  if (owner === role) return `Your next step: ${todo}`
  if (role === 'GM' || role === 'BRAND_MANAGER') return `${ROLE_LABELS[owner]}: ${todo}`
  return `Waiting on the ${ROLE_LABELS[owner]}`
}

/**
 * What the expert asked for, while the case is still held on it — else null (2026-10-01).
 *
 * The request is the expert's own timeline row (`CaseTimelineService` names portal experts "The
 * expert") that put the case on hold. A later row back at `NONE` means a Resume ended that hold, so
 * a different hold that follows cannot inherit the old request's banner.
 */
export function expertEvidenceRequest(
  timeline: readonly { actorName: string; exceptionState: string | null; note: string | null }[],
  exceptionState: string,
): string | null {
  if (exceptionState !== 'ON_HOLD_AWAITING_CLIENT') return null
  for (let i = timeline.length - 1; i >= 0; i--) {
    const entry = timeline[i]
    if (entry.exceptionState === 'NONE') return null
    if (entry.actorName === 'The expert' && entry.exceptionState === 'ON_HOLD_AWAITING_CLIENT') return entry.note
  }
  return null
}

/** `CaseController` PATCH /{id}/deadline: GM or PM. */
export function maySetDeadline(role: Role): boolean {
  return role === 'GM' || role === 'PROJECT_MANAGER'
}

/** `ChecklistController.COORDINATION`: GM, BM, PC, CM. */
const COORDINATION: readonly Role[] = ['GM', 'BRAND_MANAGER', 'PROJECT_COORDINATOR', 'CASE_MANAGER']
export function mayManageChecklist(role: Role): boolean {
  return COORDINATION.includes(role)
}

/** A date-only choice means "by the end of that day where I am", so it is parsed as local time. */
export function endOfDayIso(date: string): string {
  return new Date(`${date}T23:59:59.999`).toISOString()
}

export function toDateInput(iso: string | null): string {
  if (!iso) return ''
  const d = new Date(iso)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}
