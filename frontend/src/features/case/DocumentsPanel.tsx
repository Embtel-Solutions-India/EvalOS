import { useQuery, useQueryClient } from '@tanstack/react-query'
import type { Role } from '../../lib/session'
import { fetchChecklist } from '../checklist/checklistApi'
import type { ChecklistView } from '../checklist/checklistRules'
import type { CaseDetail } from './caseApi'
import ChecklistSheet from './ChecklistSheet'
import { useOpenDocument } from './useOpenDocument'
import { fetchCaseDocuments, type DraftVersion } from './caseApi'
import { isViewable } from './draftRules'
import { mayManageChecklist } from './caseRules'
import {
  AlertTriangle,
  CheckCircle2,
  Clock,
  Download,
  Eye,
  FileText,
  FolderOpen,
  ListChecks,
  Send,
  UploadCloud,
} from 'lucide-react'
import type { ReactNode } from 'react'
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
  // The client's files, under the same key `DocumentList` uses, so an upload anywhere refreshes both.
  const files = useQuery<DraftVersion[]>({
    queryKey: ['case', detail.summary.id, 'documents', 'CLIENT_UPLOAD'],
    queryFn: ({ signal }) => fetchCaseDocuments(detail.summary.id, 'CLIENT_UPLOAD', signal),
    enabled: detail.maySeeCaseContent,
  })
  const { open, error: openError } = useOpenDocument(detail.summary.id)
  const labels = new Set(view?.items.map((item) => item.label) ?? [])
  const unmatched = (files.data ?? []).filter((f) => !f.notes || !labels.has(f.notes))

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

      {/* The checklist and what the client sent are one list (2026-10-30): each upload is filed against the
          item it answers (`case_document.notes` carries the item's label, PortalCaseService.uploadTo), so a
          file sits under its item with View and Download beside it. */}
      {view && view.items.length > 0 && (
        <ul className="mt-3 flex flex-col">
          {view.items.map((item) => (
            <ChecklistRow
              key={item.id}
              item={item}
              files={detail.maySeeCaseContent ? (files.data ?? []).filter((f) => f.notes === item.label) : null}
              onOpen={open}
            />
          ))}
        </ul>
      )}

      {/* An upload whose item was since removed or renamed is still the client's: shown, never dropped. */}
      {detail.maySeeCaseContent && unmatched.length > 0 && (
        <div className="mt-3 rounded-md p-2" style={{ background: 'var(--bg-raised)' }}>
          <p className="mb-1 text-xs font-semibold" style={{ color: 'var(--text-muted)' }}>
            Other uploads
          </p>
          <FileRows files={unmatched} onOpen={open} />
        </div>
      )}

      {!detail.maySeeCaseContent && (
        <p className="mt-2 text-xs" style={{ color: 'var(--text-muted)' }}>
          The client&apos;s files are not available to your role.
        </p>
      )}
      {files.isError && (
        <p className="mt-2 text-xs" style={{ color: 'var(--status-red)' }}>
          The client&apos;s files could not be loaded.
        </p>
      )}
      {openError && (
        <p role="alert" className="mt-2 text-xs" style={{ color: 'var(--status-red)' }}>
          {openError}
        </p>
      )}

      {view && (view.unsent > 0 || view.lastChasedAt) && (
        <p className="mt-2 text-xs" style={{ color: 'var(--text-muted)' }}>
          {[
            view.unsent > 0 ? `${view.unsent} item${view.unsent === 1 ? ' waits' : 's wait'} for the next Send.` : null,
            view.lastChasedAt ? `Client last chased ${new Date(view.lastChasedAt).toLocaleDateString()}.` : null,
          ]
            .filter(Boolean)
            .join(' ')}
        </p>
      )}
    </Panel>
  )
}

type Item = ChecklistView['items'][number]

/** What a row says under its label, and the icon beside it: the item's state in one glance. */
function stateOf(item: Item, received: number) {
  if (!item.sentAt) return { Icon: Send, color: 'var(--text-muted)', hint: 'Not sent to the client yet' }
  switch (item.status) {
    case 'APPROVED':
      return { Icon: CheckCircle2, color: 'var(--status-green)', hint: 'Approved' }
    case 'UPLOADED':
      return {
        Icon: UploadCloud,
        color: 'var(--accent-primary)',
        hint: received === 1 ? '1 file received, to review' : `${received} files received, to review`,
      }
    case 'MISSING':
      return { Icon: AlertTriangle, color: 'var(--status-red)', hint: 'Marked missing' }
    case 'INCORRECT':
      return { Icon: AlertTriangle, color: 'var(--status-red)', hint: 'Marked incorrect, the client is asked again' }
    default:
      return { Icon: Clock, color: 'var(--status-amber)', hint: 'Waiting for the client' }
  }
}

function ChecklistRow({
  item,
  files,
  onOpen,
}: {
  item: Item
  /** Null when the reader may not see the client's files. */
  files: DraftVersion[] | null
  onOpen: (documentId: string, view: boolean) => Promise<void>
}) {
  const { Icon, color, hint } = stateOf(item, files?.length ?? 0)
  return (
    <li className="border-t py-2.5 first:border-t-0" style={{ borderColor: 'var(--border-default)' }}>
      <div className="flex items-start gap-2.5">
        <Icon aria-hidden className="mt-0.5 h-4 w-4 shrink-0" style={{ color }} />
        <div className="min-w-0 flex-1">
          <p className="text-sm font-medium">{item.label}</p>
          <p className="text-xs" style={{ color: 'var(--text-muted)' }}>
            {hint}
          </p>
        </div>
        {/* An unsent item is invisible to the client, whatever its status says (D60). */}
        <StatusPill tone={checklistTone(item.status, item.sentAt !== null)}>
          {item.sentAt ? sentence(item.status) : 'Not sent'}
        </StatusPill>
      </div>
      {files && files.length > 0 && (
        <div className="mt-1.5 pl-6.5">
          <FileRows files={files} onOpen={onOpen} />
        </div>
      )}
    </li>
  )
}

/** One line per file: name, when it came, and View / Download as icon buttons. */
function FileRows({
  files,
  onOpen,
}: {
  files: DraftVersion[]
  onOpen: (documentId: string, view: boolean) => Promise<void>
}) {
  return (
    <ul className="flex flex-col gap-1">
      {files.map((file) => {
        const name = file.filename ?? `Document ${file.version}`
        return (
          <li
            key={file.id}
            className="flex items-center gap-2 rounded-md border px-2 py-1 text-xs"
            style={{ borderColor: 'var(--border-default)', background: 'var(--bg-surface)' }}
          >
            <FileText aria-hidden className="h-3.5 w-3.5 shrink-0" style={{ color: 'var(--text-muted)' }} />
            <span className="min-w-0 flex-1 truncate" title={name}>
              {name}
            </span>
            <span className="shrink-0 tabular-nums" style={{ color: 'var(--text-muted)' }}>
              {new Date(file.uploadedAt).toLocaleDateString()}
            </span>
            {/* View for what a browser shows (PDF, image); every file downloads (Unit 74). */}
            {isViewable(file.filename) && (
              <IconButton label={`View ${name}`} onClick={() => void onOpen(file.id, true)}>
                <Eye aria-hidden className="h-3.5 w-3.5" />
              </IconButton>
            )}
            <IconButton label={`Download ${name}`} onClick={() => void onOpen(file.id, false)}>
              <Download aria-hidden className="h-3.5 w-3.5" />
            </IconButton>
          </li>
        )
      })}
    </ul>
  )
}

function IconButton({ label, onClick, children }: { label: string; onClick: () => void; children: ReactNode }) {
  return (
    <button
      type="button"
      onClick={onClick}
      aria-label={label}
      title={label}
      className="grid h-7 w-7 shrink-0 place-items-center rounded-md hover:bg-(--bg-raised) focus-visible:shadow-[var(--ring-focus)] focus-visible:outline-none"
      style={{ color: 'var(--accent-primary)' }}
    >
      {children}
    </button>
  )
}
