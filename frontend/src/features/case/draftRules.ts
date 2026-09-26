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
