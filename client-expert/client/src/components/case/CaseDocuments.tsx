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
  MAX_UPLOAD_MB,
  needsClientAction,
  type ChecklistItem,
} from '@shared/lib/portal'
import { statusOf } from '@shared/services/apiClient'
import { formatDateShort } from '@shared/utils/formatters'
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
            <div key={document.id} className="flex items-center justify-between gap-2 text-sm">
              <span className="min-w-0 truncate">
                {document.filename ?? 'Document'}
                <span className="ml-2 text-xs text-muted-foreground">
                  v{document.version} · {document.checklistLabel ?? '—'} · {formatDateShort(document.uploadedAt)}
                </span>
              </span>
              <Button variant="ghost" size="sm" onClick={() => void openFile(() => documentUrl(caseId, document.id))}>
                Download
              </Button>
            </div>
          ))}
        </div>
      )}
    </div>
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
    <Card className="p-4">
      <div className="mb-3 flex items-center justify-between gap-3">
        <p className="text-sm font-medium text-foreground">{item.label}</p>
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
