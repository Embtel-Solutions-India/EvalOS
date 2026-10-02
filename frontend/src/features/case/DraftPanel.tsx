import type { Role } from '../../lib/session'
import type { CaseDetail } from './caseApi'
import DraftHistory from './DraftHistory'
import UploadDraftDialog from './UploadDraftDialog'
import { mayUploadDraft } from './draftRules'
import { FilePen, Upload } from 'lucide-react'
import { Panel } from '../../components/ui/panel'
import { button, sentence, type Tone } from './caseUi'
import { StatusPill } from './StatusPill'

/**
 * Where the draft stands: the version count and the two approval chips the draft loops turn
 * on, and since Unit 66 the version history and the upload too — one panel about one subject. `ui-context.md` asks the Draft / Report column for these as sub-status chips; this is
 * the same information at full size.
 *
 * Both statuses are null until the loop they belong to has started, which is why "not yet"
 * is a state rather than a blank.
 */

const APPROVAL: Record<string, { tone: Tone; label: string }> = {
  PENDING: { tone: 'pending', label: 'Awaiting review' },
  APPROVED: { tone: 'done', label: 'Approved' },
  RETURNED: { tone: 'blocked', label: 'Returned' },
  REVISION_REQUESTED: { tone: 'blocked', label: 'Changes requested' },
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
    <Panel
      title="Draft"
      icon={<FilePen />}
      action={
        mayUploadDraft(currentStage, role) ?
          <UploadDraftDialog
            caseId={id}
            nextVersion={draftVersionCount + 1}
            onUploaded={onUploaded}
            trigger={
              <button type="button" className={button.tertiary}>
                <Upload aria-hidden />
                {draftVersionCount === 0 ? 'Upload first draft' : 'Upload new version'}
              </button>
            }
          />
        : draftVersionCount > 0 && (
            <StatusPill tone="idle">
              <span className="font-num tabular-nums">Version {draftVersionCount}</span>
            </StatusPill>
          )
      }
    >
      <dl className="grid gap-2 sm:grid-cols-2">
        <Row label="PM review" status={pmApprovalStatus} />
        <Row label="Client review" status={clientApprovalStatus} />
      </dl>

      {/* The versions' own Word / PDF links replace the old single `draftLink` (Unit 58). */}
      <div className="mt-4 border-t pt-4" style={{ borderColor: 'var(--border-default)' }}>
        {/* The versions are case content: a role that may not read them (the ENM) does not ask
            for them, rather than asking and showing the refusal as an error (audit F-10). */}
        {detail.maySeeCaseContent ?
          <DraftHistory caseId={id} clientApprovalStatus={clientApprovalStatus} />
        : <p className="text-sm" style={{ color: 'var(--text-muted)' }}>
            The draft itself is not available to your role.
          </p>
        }
      </div>
    </Panel>
  )
}

function Row({ label, status }: { label: string; status: string | null }) {
  const shown = status ? (APPROVAL[status] ?? { tone: 'idle' as Tone, label: sentence(status) }) : null
  return (
    <div
      className="flex items-center justify-between gap-2 rounded-md px-3 py-2"
      style={{ background: 'var(--bg-raised)' }}
    >
      <dt className="text-sm" style={{ color: 'var(--text-muted)' }}>
        {label}
      </dt>
      <dd>
        <StatusPill tone={shown?.tone ?? 'idle'}>{shown?.label ?? 'Not yet'}</StatusPill>
      </dd>
    </div>
  )
}
