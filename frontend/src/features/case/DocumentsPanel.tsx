import { useQuery, useQueryClient } from '@tanstack/react-query'
import type { Role } from '../../lib/session'
import { fetchChecklist } from '../checklist/checklistApi'
import type { ChecklistView } from '../checklist/checklistRules'
import type { CaseDetail } from './caseApi'
import ChecklistSheet from './ChecklistSheet'
import DocumentList from './DocumentList'
import { mayManageChecklist } from './caseRules'
import { FolderOpen, ListChecks } from 'lucide-react'
import { Panel } from '../../components/ui/panel'
import { button, checklistTone, sentence } from './caseUi'
import { StatusPill } from './StatusPill'

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
  // Unit 70a: the case's checklist key, shared with the checklist screen; a write refreshes it.
  const queryClient = useQueryClient()
  const checklistKey = ['case', detail.summary.id, 'checklist']
  const { data: view } = useQuery<ChecklistView>({
    queryKey: checklistKey,
    queryFn: ({ signal }) => fetchChecklist(detail.summary.id, signal),
  })
  const setView = (fresh: ChecklistView) => queryClient.setQueryData(checklistKey, fresh)

  // The sheet's writes hand back a fresh view before the page reloads, so prefer it.
  const checklistTotal = view?.total ?? detail.checklistTotal
  const checklistComplete = view?.complete ?? detail.checklistComplete
  const outstanding = checklistTotal - checklistComplete
  const done = checklistTotal > 0 && outstanding === 0

  return (
    <Panel
      title="Documents & checklist"
      icon={<FolderOpen />}
      action={
        // Gated on the server's COORDINATION rule, not the nav table: the CM may manage it here
        // without being able to reach the Coordinator's /checklists screen.
        mayManageChecklist(role) && (
          <ChecklistSheet
            caseId={detail.summary.id}
            onChecklistChanged={setView}
            onCaseLeftTheStage={onChanged}
            trigger={
              <button type="button" className={button.tertiary}>
                <ListChecks aria-hidden />
                Manage checklist
              </button>
            }
          />
        )
      }
    >
      <div className="flex items-center justify-between gap-3">
        <p className="text-sm font-medium">
          {checklistTotal === 0 ?
            'No checklist yet'
          : done ?
            'All documents are in'
          : `${outstanding} of ${checklistTotal} still outstanding`}
        </p>
        <StatusPill tone={checklistTotal === 0 ? 'idle' : done ? 'done' : 'pending'}>
          <span className="font-num tabular-nums">
            {checklistComplete} / {checklistTotal}
          </span>
        </StatusPill>
      </div>
      {checklistTotal > 0 && (
        <div
          className="mt-2 h-1.5 overflow-hidden rounded-full"
          style={{ background: 'var(--bg-raised)' }}
          role="progressbar"
          aria-label="Checklist complete"
          aria-valuemin={0}
          aria-valuemax={checklistTotal}
          aria-valuenow={checklistComplete}
        >
          <div
            className="h-full rounded-full"
            style={{
              width: `${(checklistComplete / checklistTotal) * 100}%`,
              background: done ? 'var(--status-green)' : 'var(--accent-primary)',
            }}
          />
        </div>
      )}

      {view && view.items.length > 0 && (
        <>
          <ul className="mt-3 flex flex-col">
            {view.items.map((item) => (
              <li
                key={item.id}
                className="flex items-center justify-between gap-3 border-t py-2 text-sm"
                style={{ borderColor: 'var(--border-default)' }}
              >
                <span className="min-w-0">{item.label}</span>
                {/* An unsent item is invisible to the client, whatever its status says (D60). */}
                <StatusPill tone={checklistTone(item.status, item.sentAt !== null)}>
                  {item.sentAt ? sentence(item.status) : 'Not sent'}
                </StatusPill>
              </li>
            ))}
          </ul>
          {(view.unsent > 0 || view.lastChasedAt) && (
            <p className="mt-1 text-xs" style={{ color: 'var(--text-muted)' }}>
              {[
                view.unsent > 0 ? `${view.unsent} item${view.unsent === 1 ? ' waits' : 's wait'} for the next Send.` : null,
                view.lastChasedAt ? `Client last chased ${new Date(view.lastChasedAt).toLocaleDateString()}.` : null,
              ]
                .filter(Boolean)
                .join(' ')}
            </p>
          )}
        </>
      )}

      <h3 className="mt-5 text-xs font-semibold" style={{ color: 'var(--text-muted)' }}>
        Sent by the client
      </h3>
      {/* Objects in the document store, each opened through a URL minted at the click (Unit 30). */}
      <DocumentList caseId={detail.summary.id} maySee={detail.maySeeCaseContent} />
    </Panel>
  )
}
