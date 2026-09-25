import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { FileText, X } from 'lucide-react'
import { Link } from 'react-router-dom'
import { toast } from 'sonner'
import { Button } from '@shared/components/ui/button'
import { Card } from '@shared/components/ui/card'
import { FileDropzone, validateFile } from '@shared/components/common/FileDropzone'
import {
  attachDocument,
  listDocuments,
  removeDocument,
  type RequestDocument,
} from '@/services/applicationService'
import { LEGAL } from '@/constants/legal'

/**
 * The documents that go with a request — Unit 53 (D33), the DOCUMENT SUBMISSION step
 * `workflows.md` §2 has always named and Unit 43 deferred.
 *
 * **Sending is never gated on this** (`43` §5, unchanged): a missing document is something Sales
 * asks about on the call, not a wall in front of a lead. So there is no required-count, no
 * completeness bar and no disabled Send button anywhere near it — the copy says "if you have them"
 * and means it.
 *
 * **On the review step rather than a fourth wizard step.** The client has just read back what they
 * are about to send; attaching the transcript it describes belongs in the same glance. A fourth
 * step would have added a stage to a funnel whose whole design is to not lose people in one.
 *
 * **Removal only while the request is a draft.** After sending, the file is evidence Sales may
 * already have opened, and the server refuses with wording that tells the client to ask us. The
 * button is hidden rather than disabled once sent — a control that exists only to explain why it
 * cannot be used is worse than its absence.
 */
const ACCEPTED = ['pdf', 'jpg', 'jpeg', 'png', 'doc', 'docx']
const MAX_MB = 20

export default function RequestDocuments({
  applicationId,
  canRemove,
}: {
  applicationId: string
  canRemove: boolean
}) {
  const queryClient = useQueryClient()
  const key = ['portal', 'applications', applicationId, 'documents']

  const documents = useQuery({
    queryKey: key,
    queryFn: ({ signal }) => listDocuments(applicationId, signal),
  })

  const attach = useMutation({
    mutationFn: (file: File) => attachDocument(applicationId, file),
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: key }),
    onError: (error) =>
      toast.error(error instanceof Error ? error.message : 'We could not attach that file.'),
  })

  const remove = useMutation({
    mutationFn: (documentId: string) => removeDocument(applicationId, documentId),
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: key }),
    onError: (error) =>
      toast.error(error instanceof Error ? error.message : 'We could not remove that file.'),
  })

  function onFile(file: File) {
    // Checked here as well as on the server, and neither is redundant: this one gives the client a
    // sentence instead of a rejected upload, while the server sniffs the actual bytes because a
    // filename and a declared type are both attacker-controlled.
    const problem = validateFile(file, ACCEPTED, MAX_MB)
    if (problem) {
      toast.error(problem)
      return
    }
    attach.mutate(file)
  }

  const attached: RequestDocument[] = documents.data ?? []

  return (
    <Card className="space-y-4 p-5">
      <div>
        <p className="text-xs font-medium uppercase tracking-wide text-muted-foreground">
          Documents
        </p>
        <p className="mt-1 text-sm text-muted-foreground">
          {/*
            "If you have them" is load-bearing copy, not a softener. The funnel must not read as
            though a missing transcript stops the request — that is exactly the lead-loss `43` §5
            refused, and it is the client's most likely reason to abandon here.
          */}
          Send us your transcripts, degree certificates or anything else that supports this
          request, if you have them to hand. You can also send them later.
        </p>
      </div>

      {attached.length > 0 && (
        <ul className="space-y-2">
          {attached.map((document) => (
            <li
              key={document.id}
              className="flex items-center gap-3 rounded-md border border-border p-3"
            >
              <FileText className="h-4 w-4 shrink-0 text-primary" aria-hidden="true" />
              <span className="min-w-0 flex-1 truncate text-sm text-foreground">
                {document.filename}
              </span>
              <span className="shrink-0 text-xs text-muted-foreground">
                {formatSize(document.sizeBytes)}
              </span>
              {canRemove && (
                <Button
                  variant="ghost"
                  size="sm"
                  aria-label={`Remove ${document.filename}`}
                  disabled={remove.isPending}
                  onClick={() => remove.mutate(document.id)}
                >
                  <X className="h-4 w-4" aria-hidden="true" />
                </Button>
              )}
            </li>
          ))}
        </ul>
      )}

      <FileDropzone
        accept={ACCEPTED}
        maxSizeMb={MAX_MB}
        onFileSelected={onFile}
        disabled={attach.isPending}
      />
      {/* Said where the files are handed over, not only in the footer. */}
      <p className="text-xs text-muted-foreground">
        We keep case documents for seven years and delete copies of government ID within 90 days of
        delivery. See our{' '}
        <Link to={LEGAL.retention.to} className="font-medium text-primary underline-offset-4 hover:underline">
          {LEGAL.retention.label}
        </Link>
        .
      </p>
    </Card>
  )
}

/** An em dash when the size is absent: a zero would be a claim about the file. */
function formatSize(bytes: number | null): string {
  if (bytes === null || bytes <= 0) return '—'
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}
