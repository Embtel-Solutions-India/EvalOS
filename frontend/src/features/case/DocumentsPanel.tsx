import { useEffect, useState } from 'react'
import type { Role } from '../../lib/session'
import { fetchChecklist } from '../checklist/checklistApi'
import type { ChecklistView } from '../checklist/checklistRules'
import type { CaseDetail } from './caseApi'
import ChecklistSheet from './ChecklistSheet'
import DocumentList from './DocumentList'
import { mayManageChecklist } from './caseRules'

/**
 * The client's own documents (Unit 30).
 *
 * Objects in the S3 document store, listed here and opened one at a time through a URL minted at
 * the click and good for five minutes. Until Unit 30 this was a link to a Google Drive folder whose
 * contents and sharing EvalOS did not control.
 *
 * Since Unit 66 it also shows what was asked for: each checklist item, what waits for the next Send
 * and when the client was last chased, with the checklist itself one Sheet away.
 */
export default function DocumentsPanel({
  detail,
  role,
  onChanged,
}: {
  detail: CaseDetail
  role: Role
  onChanged: () => void
}) {
  const [view, setView] = useState<ChecklistView | null>(null)
  useEffect(() => {
    const controller = new AbortController()
    fetchChecklist(detail.summary.id, controller.signal)
      .then(setView)
      .catch(() => undefined)
    return () => controller.abort()
  }, [detail.summary.id, detail.checklistComplete, detail.checklistTotal])

  // The sheet's writes hand back a fresh view before the page reloads, so prefer it.
  const checklistTotal = view?.total ?? detail.checklistTotal
  const checklistComplete = view?.complete ?? detail.checklistComplete
  const outstanding = checklistTotal - checklistComplete
  const done = checklistTotal > 0 && outstanding === 0

  return (
    <section
      className="rounded-lg border p-4"
      style={{ background: 'var(--bg-surface)', borderColor: 'var(--border-default)' }}
    >
      <h2 className="text-sm font-semibold tracking-tight">Documents &amp; checklist</h2>

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

      {view && view.items.length > 0 && (
        <>
          <ul className="mt-2 flex flex-col text-sm">
            {view.items.map((item) => (
              <li
                key={item.id}
                className="flex justify-between gap-2 border-t py-1"
                style={{ borderColor: 'var(--bg-raised)' }}
              >
                <span>{item.label}</span>
                {/* An unsent item is invisible to the client, whatever its status says (D60). */}
                <span className="text-xs" style={{ color: 'var(--text-muted)' }}>
                  {item.sentAt ? item.status.toLowerCase().replaceAll('_', ' ') : 'not sent'}
                </span>
              </li>
            ))}
          </ul>
          <p className="mt-1 text-xs" style={{ color: 'var(--text-muted)' }}>
            {[
              view.unsent > 0 ? `${view.unsent} item${view.unsent === 1 ? '' : 's'} wait for the next Send` : null,
              view.lastChasedAt ? `chased ${new Date(view.lastChasedAt).toLocaleDateString()}` : null,
            ]
              .filter(Boolean)
              .join(' · ')}
          </p>
        </>
      )}

      {/* Gated on the server's COORDINATION rule, not the nav table: the CM may manage it here
          without being able to reach the Coordinator's /checklists screen. */}
      {mayManageChecklist(role) && (
        <ChecklistSheet
          caseId={detail.summary.id}
          onChecklistChanged={setView}
          onCaseLeftTheStage={onChanged}
          trigger={
            <button type="button" className="mt-2 text-sm font-medium" style={{ color: 'var(--accent-primary)' }}>
              Manage checklist →
            </button>
          }
        />
      )}
    </section>
  )
}
