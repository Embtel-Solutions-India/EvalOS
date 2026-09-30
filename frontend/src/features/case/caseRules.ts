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

/** Transitions out of the stage and ways out of an exception are the header's buttons; the rest go under More. */
export function splitActions(actions: readonly QuickAction[]): { primary: QuickAction[]; more: QuickAction[] } {
  const primary = actions.filter((action) => action.stages !== null || action.requiresException !== undefined)
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

/** `OfferFeeController` PATCH …/offer/fee: GM, PM, PC, ENM, and only while the offer is open (D59). */
const FEE_SETTERS: readonly Role[] = ['GM', 'PROJECT_MANAGER', 'PROJECT_COORDINATOR', 'EXPERT_NETWORK_MANAGER']
export function mayEditFee(role: Role, outcome: string | null): boolean {
  return outcome === 'OFFERED' && FEE_SETTERS.includes(role)
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
