import { api, unwrap } from '../../lib/api'
import { performAction } from '../board/boardApi'
import { QUICK_ACTIONS } from '../board/boardRules'
import type { ChecklistCard, ChecklistItemStatus, ChecklistView } from './checklistRules'

/**
 * Everything the checklist screen sends over the wire.
 *
 * Every write answers the whole refreshed checklist, so the caller replaces its state rather
 * than patching a row and recomputing `checklistSatisfied` itself — which is the recomputation
 * the server is doing anyway, and the two could disagree.
 */

/**
 * `brandId` is the shell's brand switcher, and it can only ever narrow: the server applies it
 * after the scoped read, exactly as on the production board.
 */
export async function fetchChecklistBoard(
  brandId: string | null,
  signal?: AbortSignal,
): Promise<ChecklistCard[]> {
  const params: Record<string, string> = {}
  if (brandId) params.brandId = brandId
  return unwrap<ChecklistCard[]>(api.get('/checklists/board', { params, signal }))
}

export async function fetchChecklist(caseId: string, signal?: AbortSignal): Promise<ChecklistView> {
  return unwrap<ChecklistView>(api.get(`/cases/${caseId}/checklist`, { signal }))
}

/** PATCH, not POST: this sets a field on an existing item, it does not move the case. */
export async function setItemStatus(
  caseId: string,
  itemId: string,
  status: ChecklistItemStatus,
): Promise<ChecklistView> {
  return unwrap<ChecklistView>(api.patch(`/cases/${caseId}/checklist/${itemId}`, { status }))
}

export async function addChecklistItem(caseId: string, label: string): Promise<ChecklistView> {
  return unwrap<ChecklistView>(api.post(`/cases/${caseId}/checklist/items`, { label }))
}

/** GHL delivers the message; EvalOS only says that it should. */
export async function sendChase(caseId: string): Promise<ChecklistView> {
  return unwrap<ChecklistView>(api.post(`/cases/${caseId}/chase`, {}))
}

/**
 * The transition out of Document Collection — Unit 04's, reached through the board's own
 * `performAction` rather than a second POST written here.
 *
 * One path, one error-surfacing behaviour: pressing "Docs complete" on a board card and
 * pressing it on this screen have to be the same operation, or the two screens can disagree
 * about what happened. Throws if the entry ever leaves `QUICK_ACTIONS`, which is louder than
 * a button that silently stops working.
 */
export async function markDocsComplete(caseId: string): Promise<void> {
  const action = QUICK_ACTIONS.find((candidate) => candidate.path === 'docs-complete')
  if (!action) throw new Error('The docs-complete action is missing from the board rules')
  await performAction(caseId, action, {})
}
