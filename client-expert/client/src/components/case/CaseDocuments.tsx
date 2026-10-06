import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { FileText } from 'lucide-react'
import { useState } from 'react'
import { toast } from 'sonner'
import { Badge } from '@shared/components/ui/badge'
import { Button } from '@shared/components/ui/button'
import { Card } from '@shared/components/ui/card'
import { Progress } from '@shared/components/ui/progress'
import { EmptyState } from '@shared/components/common/EmptyState'
import { ErrorState } from '@shared/components/common/ErrorState'
import { FileDropzone, validateFile } from '@shared/components/common/FileDropzone'
import { ListSkeleton } from '@shared/components/common/LoadingState'
import {
  actionFirst,
  CHECKLIST_STATUS,
  failureMessage,
  isViewable,
  MAX_UPLOAD_MB,
  needsClientAction,
  type ChecklistItem,
} from '@shared/lib/portal'
import { statusOf } from '@shared/services/apiClient'
import { formatDate } from '@shared/utils/formatters'
import { openFile } from '@/lib/openFile'
import { documentUrl, listDocuments, uploadDocument } from '@/services/caseService'

/** The types the checklist accepts. The real cap is the server's 15 MB multipart limit. */
const ACCEPTED = ['PDF', 'JPG', 'JPEG', 'PNG']

/**
 * This case's checklist and the client's own uploads (Unit 34c, per case since Unit 58).
 *
 * Every word on a checklist row is a label for a value EvalOS sent; nothing here computes a status.
 */
export function CaseDocuments({ caseId }: { caseId: string }) {
  const queryClient = useQueryClient()
  const key = ['portal', 'case', caseId, 'documents']
  const { data, isLoading, isError, error, refetch } = useQuery({
    queryKey: key,
    queryFn: ({ signal }) => listDocuments(caseId, signal),
    retry: false,
  })

  if (isLoading) return <ListSkeleton />
  if (isError) return <ErrorState description={failureMessage(statusOf(error))} onRetry={() => void refetch()} />
  if (!data) return null

  return (
    <div className="space-y-4">
      {data.checklist.length === 0 ? (
        <EmptyState icon={FileText} title="Nothing outstanding" description="There is no document waiting on you right now. When your case team needs documents, the list appears here." />
      ) : (
        actionFirst(data.checklist).map((item) => (
          <ChecklistRow
            key={item.id}
            caseId={caseId}
            item={item}
            onUploaded={() => void queryClient.invalidateQueries({ queryKey: ['portal', 'case', caseId] })}
          />
        ))
      )}

      {data.uploaded.length > 0 && (
        <div className="space-y-1">
          <p className="text-xs font-semibold text-muted-foreground">What you have sent</p>
          {data.uploaded.map((document) => (
            <div key={document.id} className="flex flex-col gap-1 text-sm sm:flex-row sm:items-center sm:justify-between sm:gap-2">
              <span className="min-w-0 break-words sm:truncate">
                {document.filename ?? 'Document'}
                <span className="ml-2 text-xs text-muted-foreground">
                  v{document.version} · {document.checklistLabel ?? '—'} · {formatDate(document.uploadedAt, 'short')}
                </span>
              </span>
              {/* View first (Unit 74): a PDF or image opens in the browser; every file downloads. */}
              <span className="flex shrink-0 gap-1 self-start sm:self-auto">
                {isViewable(document.filename) && (
                  <Button variant="ghost" size="sm" onClick={() => void openFile(() => documentUrl(caseId, document.id, true))}>
                    View
                  </Button>
                )}
                <Button variant="ghost" size="sm" onClick={() => void openFile(() => documentUrl(caseId, document.id))}>
                  Download
                </Button>
              </span>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}

/**
 * The same checklist at a glance, beside the uploads: label and status only, each a jump to its
 * upload row. Shares the documents query, so it costs no second request.
 */
export function CaseChecklist({ caseId }: { caseId: string }) {
  const { data, isLoading } = useQuery({
    queryKey: ['portal', 'case', caseId, 'documents'],
    queryFn: ({ signal }) => listDocuments(caseId, signal),
    retry: false,
  })

  if (isLoading) return <ListSkeleton />
  if (!data || data.checklist.length === 0) {
    return <p className="text-sm text-muted-foreground">Nothing is waiting on you right now.</p>
  }

  return (
    <ul className="divide-y">
      {actionFirst(data.checklist).map((item) => {
        const status = CHECKLIST_STATUS[item.status]
        return (
          <li key={item.id}>
            <a
              href={`#doc-${item.id}`}
              onClick={(event) => {
                event.preventDefault()
                document.getElementById(`doc-${item.id}`)?.scrollIntoView({ behavior: 'smooth' })
              }}
              className="flex items-center justify-between gap-3 py-2.5 text-sm hover:text-primary"
            >
              <span className="min-w-0 break-words">{item.label}</span>
              <Badge variant={status.variant} className="shrink-0">
                {status.label}
              </Badge>
            </a>
          </li>
        )
      })}
    </ul>
  )
}

function ChecklistRow({ caseId, item, onUploaded }: { caseId: string; item: ChecklistItem; onUploaded: () => void }) {
  const [progress, setProgress] = useState<number | null>(null)
  const [rejection, setRejection] = useState<string | undefined>()
  const status = CHECKLIST_STATUS[item.status]

  const upload = useMutation({
    mutationFn: (file: File) => uploadDocument(caseId, item.id, file, setProgress),
    onSuccess: () => {
      setProgress(null)
      toast.success(`${item.label} received.`)
      onUploaded()
    },
    onError: (uploadError: unknown) => {
      setProgress(null)
      toast.error(failureMessage(statusOf(uploadError)))
    },
  })

  function onFileSelected(file: File) {
    const problem = validateFile(file, ACCEPTED, MAX_UPLOAD_MB)
    setRejection(problem ?? undefined)
    if (!problem) upload.mutate(file)
  }

  return (
    <Card id={`doc-${item.id}`} className="scroll-mt-20 p-4">
      <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
        <p className="min-w-0 break-words text-sm font-medium text-foreground">{item.label}</p>
        <Badge variant={status.variant}>{status.label}</Badge>
      </div>
      {progress !== null ? (
        <Progress value={progress} />
      ) : (
        <FileDropzone
          accept={ACCEPTED}
          maxSizeMb={MAX_UPLOAD_MB}
          onFileSelected={onFileSelected}
          disabled={upload.isPending}
          error={rejection}
        />
      )}
      {!needsClientAction(item.status) && (
        <p className="mt-2 text-xs text-muted-foreground">
          We have this one. You can send a newer copy if something has changed.
        </p>
      )}
    </Card>
  )
}
