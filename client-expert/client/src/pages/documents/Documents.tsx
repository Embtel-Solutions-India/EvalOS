import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { FileText } from 'lucide-react'
import { useState } from 'react'
import { toast } from 'sonner'
import { Badge } from '@shared/components/ui/badge'
import { Button } from '@shared/components/ui/button'
import { Card } from '@shared/components/ui/card'
import { Progress } from '@shared/components/ui/progress'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@shared/components/ui/table'
import { EmptyState } from '@shared/components/common/EmptyState'
import { ErrorState } from '@shared/components/common/ErrorState'
import { FileDropzone, validateFile } from '@shared/components/common/FileDropzone'
import { PageHeader } from '@shared/components/common/PageHeader'
import { TableSkeleton } from '@shared/components/common/LoadingState'
import {
  actionFirst,
  CHECKLIST_STATUS,
  failureMessage,
  MAX_UPLOAD_MB,
  NO_TOKEN,
  needsClientAction,
  tokenFromFragment,
  type ChecklistItem,
} from '@shared/lib/portal'
import { hasPortalToken, setPortalToken, statusOf } from '@shared/services/apiClient'
import { documentUrl, listDocuments, uploadDocument } from '@/services/documentService'
import { formatDateShort } from '@shared/utils/formatters'

/**
 * The client's documents, against EvalOS (Unit 34c). **The first real screen in this app.**
 *
 * <p>It sits outside the account shell on purpose. The credential here is a scoped portal token
 * that names one case — not the mock account session the rest of the app carries — and the
 * question of whether a portal credential should name a *party* instead is Unit 34's decision D1,
 * which is not taken. Wiring this page into `AuthenticatedRoute` would answer it by accident.
 *
 * <p>What is deliberately absent: any status this file computes. Every word on a checklist row is
 * a label for a value EvalOS sent.
 */

/** The types the checklist accepts. The real cap is the server's 15 MB multipart limit. */
const ACCEPTED = ['PDF', 'JPG', 'JPEG', 'PNG']

export default function Documents() {
  const queryClient = useQueryClient()

  // Captured during the first render rather than in an effect: the token has to be on the client
  // before the query fires, and an effect runs after. Re-running it (StrictMode does) sets the
  // same value twice, which is a no-op.
  const [tokenPresent] = useState(() => {
    const token = tokenFromFragment(window.location.hash)
    if (token) setPortalToken(token)
    return hasPortalToken()
  })

  const { data, isLoading, isError, error, refetch } = useQuery({
    queryKey: ['portal', 'documents'],
    queryFn: listDocuments,
    enabled: tokenPresent,
    retry: false,
  })

  if (!tokenPresent) {
    return (
      <div className="mx-auto max-w-2xl p-6">
        <PageHeader title="Your documents" description={NO_TOKEN} />
      </div>
    )
  }

  return (
    <div className="mx-auto max-w-4xl p-6">
      <PageHeader
        title="Your documents"
        description="Send us what is still needed, and download anything you have already sent."
      />

      {isLoading && <TableSkeleton />}
      {isError && (
        <ErrorState description={failureMessage(statusOf(error))} onRetry={() => void refetch()} />
      )}

      {!isLoading && !isError && data && (
        <div className="space-y-8">
          <section>
            <h2 className="mb-3 text-sm font-semibold text-foreground">What we need</h2>
            {data.checklist.length === 0 ? (
              <EmptyState
                icon={FileText}
                title="Nothing outstanding"
                description="There is no document waiting on you right now."
              />
            ) : (
              <div className="space-y-3">
                {actionFirst(data.checklist).map((item) => (
                  <ChecklistRow
                    key={item.id}
                    item={item}
                    onUploaded={() => void queryClient.invalidateQueries({ queryKey: ['portal', 'documents'] })}
                  />
                ))}
              </div>
            )}
          </section>

          <section>
            <h2 className="mb-3 text-sm font-semibold text-foreground">What you have sent</h2>
            {data.uploaded.length === 0 ? (
              <EmptyState
                icon={FileText}
                title="No documents yet"
                description="Anything you upload above will appear here."
              />
            ) : (
              <Card className="overflow-hidden p-0">
                <Table>
                  <TableHeader>
                    <TableRow>
                      <TableHead>Document</TableHead>
                      <TableHead>For</TableHead>
                      <TableHead>Sent</TableHead>
                      <TableHead className="text-right">Actions</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {data.uploaded.map((document) => (
                      <TableRow key={document.id}>
                        <TableCell className="font-medium text-foreground">
                          {document.filename ?? 'Document'}
                          <span className="ml-2 text-xs text-muted-foreground">v{document.version}</span>
                        </TableCell>
                        <TableCell>{document.checklistLabel ?? '—'}</TableCell>
                        <TableCell>{formatDateShort(document.uploadedAt)}</TableCell>
                        <TableCell className="text-right">
                          <DownloadButton documentId={document.id} />
                        </TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </Card>
            )}
          </section>
        </div>
      )}
    </div>
  )
}

function ChecklistRow({ item, onUploaded }: { item: ChecklistItem; onUploaded: () => void }) {
  const [progress, setProgress] = useState<number | null>(null)
  const [rejection, setRejection] = useState<string | undefined>()
  const status = CHECKLIST_STATUS[item.status]

  const upload = useMutation({
    mutationFn: (file: File) => uploadDocument(item.id, file, setProgress),
    onSuccess: () => {
      setProgress(null)
      toast.success(`${item.label} received.`)
      onUploaded()
    },
    onError: (uploadError: unknown) => {
      setProgress(null)
      // The server's own words are not written for a client, so the status decides the message.
      toast.error(failureMessage(statusOf(uploadError)))
    },
  })

  function onFileSelected(file: File) {
    const problem = validateFile(file, ACCEPTED, MAX_UPLOAD_MB)
    setRejection(problem ?? undefined)
    if (problem) return
    upload.mutate(file)
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

/**
 * Opens one document.
 *
 * The URL is fetched on the click and used immediately — it expires in five minutes and is never
 * held anywhere. `noopener` because the opened tab is an S3 origin and has no business reaching
 * back into this one.
 */
function DownloadButton({ documentId }: { documentId: string }) {
  const [busy, setBusy] = useState(false)

  async function open() {
    setBusy(true)
    // **The tab is opened synchronously and WITHOUT `noopener`, and both halves matter.**
    // Synchronously, because a popup blocker rejects a window opened from an async continuation —
    // the click has to be what opens it, and a blocked open here looks exactly like a dead button
    // while the audit row says the document was fetched. Without `noopener`, because per the HTML
    // spec `window.open` returns **null** whenever `noopener` is in the features string, so the
    // handle needed to navigate the tab afterwards would never exist. The opener reference is
    // severed on the next line instead, which is the same protection by a different route.
    const tab = window.open('', '_blank')
    if (tab) tab.opener = null
    try {
      const url = await documentUrl(documentId)
      if (tab) tab.location.href = url
      else toast.error('Allow pop-ups for this site to open documents.')
    }
    catch (openError: unknown) {
      tab?.close()
      toast.error(failureMessage(statusOf(openError)))
    }
    finally {
      setBusy(false)
    }
  }

  return (
    <Button variant="ghost" size="sm" disabled={busy} onClick={() => void open()}>
      {busy ? 'Opening…' : 'Download'}
    </Button>
  )
}
