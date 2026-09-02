import { api, unwrap } from '../../lib/api'
import type { Role } from '../../lib/session'
import type { BoardData, PickerOption, QuickAction } from './boardRules'

/**
 * Everything the board sends over the wire. The decision tables it draws itself from are in
 * `boardRules.ts`, which imports nothing and is therefore testable on its own.
 */
/**
 * `brandId` is the shell's brand switcher. Sending it is safe for every role because the
 * server applies it *after* the scope: for a non-GM it names their own brand and changes
 * nothing, and no value can reach past what the caller may already read.
 */
export async function fetchBoard(
  dueBefore: string | null,
  brandId: string | null,
  signal?: AbortSignal,
): Promise<BoardData> {
  const params: Record<string, string> = {}
  if (dueBefore) params.dueBefore = dueBefore
  if (brandId) params.brandId = brandId
  return unwrap<BoardData>(api.get('/cases/board', { params, signal }))
}

export async function fetchAssignable(role: Role, signal?: AbortSignal): Promise<PickerOption[]> {
  const members = await unwrap<{ id: string; displayName: string }[]>(
    api.get('/team-members/assignable', { params: { role }, signal }),
  )
  return members.map((member) => ({ id: member.id, label: member.displayName }))
}

/**
 * The experts this case can be put to.
 *
 * **`forCase` is passed unconditionally, including for `assign-cm`.** The server drops the expert
 * already on the case, which is the one `reassignExpert` answers 409 to; a case with no expert yet
 * is a no-op, so there is nothing here to branch on. The client cannot apply this filter itself —
 * `BoardCard` carries no `expertId`, deliberately.
 */
export async function fetchAvailableExperts(caseId: string, signal?: AbortSignal): Promise<PickerOption[]> {
  const experts = await unwrap<{ id: string; fullName: string }[]>(
    api.get('/experts', { params: { forCase: caseId }, signal }),
  )
  return experts.map((expert) => ({ id: expert.id, label: expert.fullName ?? expert.id }))
}

/** Posts one transition. The caller shows `message` inline on a 409 and does not move the card. */
export async function performAction(
  caseId: string,
  action: QuickAction,
  values: Record<string, string>,
): Promise<void> {
  const body: Record<string, string | number> = {}
  for (const field of action.fields ?? []) {
    const raw = values[field.name]?.trim()
    if (!raw) continue
    body[field.name] = field.kind === 'amount' ? Number(raw) : raw
  }
  await unwrap(api.post(`/cases/${caseId}/${action.path}`, body))
}
