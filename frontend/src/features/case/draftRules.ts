import type { Role } from '../../lib/session'

/** Who may upload the next draft version (Unit 58): the case team, while the draft is being written. */
const UPLOADERS: Role[] = ['CASE_MANAGER', 'PROJECT_COORDINATOR', 'PROJECT_MANAGER', 'GM']

export function mayUploadDraft(stage: string, role: Role): boolean {
  return stage === 'DRAFT_IN_PROGRESS' && UPLOADERS.includes(role)
}

/** The server's rule, mirrored so the box is not offered where it would 409 (DRAFT_NOT_CURRENT). */
export function mayComment(status: string, clientApprovalStatus: string | null): boolean {
  return status === 'PM_APPROVED' && clientApprovalStatus === 'PENDING'
}

export type DraftSteps = { upload: () => Promise<void>; note: (text: string) => Promise<void> }

/**
 * Upload, then the optional note to the PM (Unit 66). The upload route takes files only, so the note
 * is a case note written after it — never before, or a failed upload would leave a note about a draft
 * that does not exist. An upload failure throws to the dialog; a note failure does not undo the draft.
 */
export async function submitDraft(steps: DraftSteps, note: string): Promise<'submitted' | 'note-failed'> {
  await steps.upload()
  const text = note.trim()
  if (!text) return 'submitted'
  try {
    await steps.note(text)
    return 'submitted'
  } catch {
    return 'note-failed'
  }
}

/**
 * Whether the browser has a viewer for this file (Unit 74): a PDF, PNG or JPEG. The server applies
 * the same rule (`DocumentStore.viewTypeOf`) and forces the type; a Word file only downloads.
 */
export function isViewable(filename: string | null | undefined): boolean {
  return /\.(pdf|png|jpe?g)$/i.test(filename ?? '')
}
