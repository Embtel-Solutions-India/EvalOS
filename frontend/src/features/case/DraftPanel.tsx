import type { Role } from '../../lib/session'
import type { CaseDetail } from './caseApi'
import DraftHistory from './DraftHistory'
import UploadDraftDialog from './UploadDraftDialog'
import { mayUploadDraft } from './draftRules'

/**
 * Where the draft stands: the version count and the two approval chips the draft loops turn
 * on, and since Unit 66 the version history and the upload too — one panel about one subject. `ui-context.md` asks the Draft / Report column for these as sub-status chips; this is
 * the same information at full size.
 *
 * Both statuses are null until the loop they belong to has started, which is why "not yet"
 * is a state rather than a blank.
 */

type ChipTone = 'pending' | 'good' | 'bad' | 'idle'

const TONE: Record<ChipTone, { fg: string; bg: string }> = {
  pending: { fg: 'var(--status-amber)', bg: 'var(--status-amber-bg)' },
  good: { fg: 'var(--status-green)', bg: 'var(--status-green-bg)' },
  bad: { fg: 'var(--status-red)', bg: 'var(--status-red-bg)' },
  idle: { fg: 'var(--text-muted)', bg: 'var(--bg-raised)' },
}

function approvalTone(status: string | null): ChipTone {
  if (status === 'APPROVED') return 'good'
  if (status === 'PENDING') return 'pending'
  if (status === 'RETURNED' || status === 'REVISION_REQUESTED') return 'bad'
  return 'idle'
}

const APPROVAL_LABEL: Record<string, string> = {
  PENDING: 'awaiting review',
  APPROVED: 'approved',
  RETURNED: 'returned',
  REVISION_REQUESTED: 'revisions requested',
}

export default function DraftPanel({
  detail,
  role,
  onUploaded,
}: {
  detail: CaseDetail
  role: Role
  onUploaded: () => void
}) {
  const { id, currentStage, pmApprovalStatus, clientApprovalStatus, draftVersionCount } = detail.summary

  return (
    <section
      className="rounded-lg border p-4"
      style={{ background: 'var(--bg-surface)', borderColor: 'var(--border-default)' }}
    >
      <div className="flex items-baseline justify-between gap-2">
        <h2 className="text-sm font-semibold tracking-tight">
          Draft{draftVersionCount > 0 ? ` · v${draftVersionCount}` : ''}
        </h2>
        {mayUploadDraft(currentStage, role) ?
          <UploadDraftDialog
            caseId={id}
            nextVersion={draftVersionCount + 1}
            onUploaded={onUploaded}
            trigger={
              <button type="button" className="text-xs font-medium" style={{ color: 'var(--accent-primary)' }}>
                Upload new version
              </button>
            }
          />
        : <span className="font-num text-xs tabular-nums" style={{ color: 'var(--text-muted)' }}>
            {draftVersionCount === 0 ? 'no draft yet' : `version ${draftVersionCount}`}
          </span>
        }
      </div>

      <dl className="mt-3 space-y-2">
        <Row label="PM review" status={pmApprovalStatus} />
        <Row label="Client review" status={clientApprovalStatus} />
      </dl>

      {/* The versions' own Word / PDF links replace the old single `draftLink` (Unit 58). */}
      <div className="mt-3">
        <DraftHistory caseId={id} clientApprovalStatus={clientApprovalStatus} />
      </div>
    </section>
  )
}

function Row({ label, status }: { label: string; status: string | null }) {
  const tone = TONE[approvalTone(status)]
  return (
    <div className="flex items-center justify-between gap-2">
      <dt className="text-sm" style={{ color: 'var(--text-muted)' }}>
        {label}
      </dt>
      <dd
        className="rounded-md px-1.5 py-0.5 text-xs font-semibold"
        style={{ color: tone.fg, background: tone.bg }}
      >
        {status ? (APPROVAL_LABEL[status] ?? status.toLowerCase()) : 'not yet'}
      </dd>
    </div>
  )
}
