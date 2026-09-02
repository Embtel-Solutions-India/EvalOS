import { Link } from 'react-router-dom'
import { useMe } from '../../lib/authContext'
import { mayReach } from '../shell/navigation'
import type { CaseDetail } from './caseApi'
import DocumentList from './DocumentList'

/**
 * The client's own documents (Unit 30).
 *
 * Objects in the S3 document store, listed here and opened one at a time through a URL minted at
 * the click and good for five minutes. Until Unit 30 this was a link to a Google Drive folder whose
 * contents and sharing EvalOS did not control.
 */
export default function DocumentsPanel({ detail }: { detail: CaseDetail }) {
  const me = useMe()
  const { checklistTotal, checklistComplete } = detail
  const outstanding = checklistTotal - checklistComplete
  const done = checklistTotal > 0 && outstanding === 0

  return (
    <section
      className="rounded-lg border p-4"
      style={{ background: 'var(--bg-surface)', borderColor: 'var(--border-default)' }}
    >
      <h2 className="text-sm font-semibold tracking-tight">Documents</h2>

      {/*
        **The client's documents, not a folder link.** Until Unit 30 this pointed at a Google Drive
        folder whose contents and sharing EvalOS did not control. Documents are S3 objects now, and
        each one opens through a URL minted at the click and good for five minutes.
      */}
      <DocumentList caseId={detail.summary.id} maySee={detail.maySeeCaseContent} />

      <div className="mt-3 flex items-center gap-2">
        <span
          className="font-num rounded-md px-1.5 py-0.5 text-xs font-semibold tabular-nums"
          style={{
            color: done ? 'var(--status-green)' : 'var(--status-amber)',
            background: done ? 'var(--status-green-bg)' : 'var(--status-amber-bg)',
          }}
        >
          {checklistComplete} / {checklistTotal}
        </span>
        <span className="text-xs" style={{ color: 'var(--text-muted)' }}>
          {checklistTotal === 0
            ? 'no checklist yet'
            : done
              ? 'all documents in'
              : `${outstanding} still outstanding`}
        </span>
      </div>

      {/*
        Gated on the same nav table the router guards against. `/checklists` is the Coordinator's
        screen, so this link answered 403 for every other role that can open a case — the GM
        included, since the client nav table has no superuser row. Found by clicking it as a
        Project Manager. A link nobody else can follow is worse than no link: it reads as a
        permission problem with the reader's account rather than a screen that is not theirs.
      */}
      {mayReach(me.role, '/checklists') && (
        <Link
          to="/checklists"
          className="mt-3 inline-block text-sm font-medium"
          style={{ color: 'var(--accent-primary)' }}
        >
          Manage the checklist
        </Link>
      )}
    </section>
  )
}
