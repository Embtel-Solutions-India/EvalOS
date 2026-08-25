import { Link } from 'react-router-dom'
import { useMe } from '../../lib/authContext'
import { mayReach } from '../shell/navigation'
import type { CaseDetail } from './caseApi'

/**
 * Documents live in Google Drive; EvalOS holds the link and never the bytes (invariant 14).
 * So this panel is a link and a count — there is deliberately no upload control, because
 * there is nowhere for a file to go.
 *
 * The checklist itself is Unit 10; this is the summary chip and the way in.
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

      {detail.driveLink ? (
        <a
          href={detail.driveLink}
          target="_blank"
          rel="noreferrer noopener"
          className="mt-2 inline-block text-sm font-medium"
          style={{ color: 'var(--accent-primary)' }}
        >
          Open the Drive folder ↗
        </a>
      ) : (
        <p className="mt-2 text-sm" style={{ color: 'var(--text-muted)' }}>
          {detail.maySeeCaseContent
            ? 'No Drive folder linked yet.'
            : 'The client document folder is not available to your role.'}
        </p>
      )}

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
