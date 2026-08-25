import { api, unwrap } from '../../lib/api'
import type { DeadlineRisk } from '../board/boardRules'

/**
 * The draft review workspace's wire shapes.
 *
 * Two of these look like stored fields and are not, which is worth knowing before anyone adds a
 * column for them: `status` is derived from the PM approval status plus the stage, and `priority`
 * is the deadline band. Unit 22 decision 5 refused an urgency column outright.
 */

export type DraftStatus = 'PENDING_REVIEW' | 'REVISIONS_REQUESTED' | 'READY_FOR_QC' | 'APPROVED'

/** One step of the progress checklist — every one an observable fact, never a stored tick. */
export type Milestone = { label: string; done: boolean }

export type DraftRow = {
  id: string
  caseCode: string
  clientName: string | null
  serviceType: string | null
  expertName: string | null
  /** When the current wait began — `stage_entered_at`, the clock the 12h PM budget runs on. */
  draftUpdated: string | null
  status: DraftStatus
  /** The deadline band under another name: high = red, medium = amber, low = green. */
  priority: DeadlineRisk | null
  deadline: string | null
  daysLeft: number | null
  draftVersionCount: number
  draftLink: string | null
  milestonesComplete: number
  milestones: Milestone[]
}

export type DraftSummary = {
  total: number
  pendingReview: number
  revisionsRequested: number
  readyForQc: number
  approved: number
  avgDraftAgeHours: number
  /** Null when nothing is pending — an empty queue is not 0% compliance. */
  slaCompliancePct: number | null
}

export type DraftReview = { summary: DraftSummary; rows: DraftRow[] }

export async function fetchDraftReview(
  brandId: string | null,
  signal?: AbortSignal,
): Promise<DraftReview> {
  return unwrap<DraftReview>(
    api.get('/metrics/drafts', { params: brandId ? { brandId } : {}, signal }),
  )
}

/** The tabs, in the order the review workflow moves through them. */
export const DRAFT_TABS: readonly { status: DraftStatus | 'ALL'; label: string }[] = [
  { status: 'ALL', label: 'All drafts' },
  { status: 'PENDING_REVIEW', label: 'Pending review' },
  { status: 'REVISIONS_REQUESTED', label: 'Revisions requested' },
  { status: 'READY_FOR_QC', label: 'Ready for QC' },
  { status: 'APPROVED', label: 'Approved' },
]

const STATUS_TONE: Record<DraftStatus, { fg: string; bg: string; label: string }> = {
  PENDING_REVIEW: { fg: 'var(--status-amber)', bg: 'var(--status-amber-bg)', label: 'Pending review' },
  REVISIONS_REQUESTED: { fg: 'var(--status-red)', bg: 'var(--status-red-bg)', label: 'Revisions requested' },
  READY_FOR_QC: { fg: 'var(--accent-primary)', bg: 'var(--accent-soft)', label: 'Ready for QC' },
  APPROVED: { fg: 'var(--status-green)', bg: 'var(--status-green-bg)', label: 'Approved' },
}

export function statusTone(status: DraftStatus) {
  return STATUS_TONE[status]
}

/**
 * The reference design's High / Medium / Low, which is the RAG deadline band relabelled.
 *
 * Kept as a translation rather than a second vocabulary on the server: the band is the fact, and
 * "High" is how this one screen says it.
 */
export function priorityLabel(priority: DeadlineRisk | null): string {
  if (priority === 'OVERDUE') return 'High'
  if (priority === 'AT_RISK') return 'Medium'
  if (priority === 'ON_TRACK') return 'Low'
  return 'No clock'
}
