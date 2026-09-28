import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ExternalLink, FileCheck } from 'lucide-react'
import { useState } from 'react'
import { toast } from 'sonner'
import { Badge } from '@shared/components/ui/badge'
import { Button } from '@shared/components/ui/button'
import { Input } from '@shared/components/ui/input'
import { Textarea } from '@shared/components/ui/textarea'
import { EmptyState } from '@shared/components/common/EmptyState'
import { ErrorState } from '@shared/components/common/ErrorState'
import { ListSkeleton } from '@shared/components/common/LoadingState'
import { DRAFT_STATUS, failureMessage, MAX_COMMENT, type ClientDraftVersion } from '@shared/lib/portal'
import { statusOf } from '@shared/services/apiClient'
import { formatDateShort } from '@shared/utils/formatters'
import { openFile } from '@/lib/openFile'
import { addComment, approveDraft, draftFileUrl, listComments, listDrafts, requestChanges } from '@/services/caseService'

/** 409 here is `DRAFT_NOT_CURRENT`: a newer version replaced the one this tab was showing. */
function draftFailure(error: unknown): string {
  return statusOf(error) === 409
    ? 'This version is no longer the one waiting for you. Please reload the page.'
    : failureMessage(statusOf(error))
}

/**
 * The draft versions the client may see (Unit 58 §1): each downloadable as PDF and Word, the one
 * in review open to comments and to the client's answer. **The server decides which version that
 * is** (`inReview`) — this screen never infers it from a status.
 */
export function CaseDraft({ caseId, legacyLink }: { caseId: string; legacyLink: string | null }) {
  const { data, isLoading, isError, error, refetch } = useQuery({
    queryKey: ['portal', 'case', caseId, 'drafts'],
    queryFn: ({ signal }) => listDrafts(caseId, signal),
    retry: false,
  })
  const [chosen, setChosen] = useState<string | null>(null)

  if (isLoading) return <ListSkeleton />
  if (isError) return <ErrorState description={failureMessage(statusOf(error))} onRetry={() => void refetch()} />

  const versions = [...(data ?? [])].sort((a, b) => b.version - a.version)
  const current = versions.find((v) => v.id === chosen) ?? versions.find((v) => v.inReview) ?? versions[0]

  if (!current) {
    return <EmptyState icon={FileCheck} title="No draft yet" description="Your draft appears here as soon as it is ready." />
  }

  return (
    <div className="space-y-4">
      {versions.length > 1 && (
        <div className="flex flex-wrap gap-2" role="tablist" aria-label="Draft versions">
          {versions.map((v) => (
            <button
              key={v.id}
              type="button"
              role="tab"
              aria-selected={v.id === current.id}
              onClick={() => setChosen(v.id)}
              className={`rounded border px-3 py-1 text-sm ${v.id === current.id ? 'border-primary bg-accent' : 'border-border'}`}
            >
              Version {v.version}
            </button>
          ))}
        </div>
      )}
      <DraftVersion key={current.id} caseId={caseId} draft={current} legacyLink={legacyLink} />
    </div>
  )
}

function DraftVersion({ caseId, draft, legacyLink }: { caseId: string; draft: ClientDraftVersion; legacyLink: string | null }) {
  const status = DRAFT_STATUS[draft.status]
  const hasFiles = draft.hasPdf || draft.hasWord
  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <p className="text-sm text-muted-foreground">
          Version {draft.version} · {formatDateShort(draft.uploadedAt)}
        </p>
        <Badge variant={status.variant}>{status.label}</Badge>
      </div>

      <div className="flex flex-wrap gap-2">
        {/* View first (D51): the PDF opens in the browser's viewer; both files still download. */}
        {draft.hasPdf && (
          <Button size="sm" onClick={() => void openFile(() => draftFileUrl(caseId, draft.id, 'pdf', true))}>
            View PDF
          </Button>
        )}
        {draft.hasPdf && (
          <Button variant="outline" size="sm" onClick={() => void openFile(() => draftFileUrl(caseId, draft.id, 'pdf'))}>
            Download PDF
          </Button>
        )}
        {draft.hasWord && (
          <Button variant="outline" size="sm" onClick={() => void openFile(() => draftFileUrl(caseId, draft.id, 'docx'))}>
            Download Word
          </Button>
        )}
        {/* A draft submitted before uploads was a pasted link (58 §1): view only. */}
        {!hasFiles && legacyLink && (
          <a
            href={legacyLink}
            target="_blank"
            rel="noreferrer noopener"
            className="inline-flex items-center gap-2 text-sm font-medium text-primary underline"
          >
            Draft (link) <ExternalLink className="h-4 w-4" />
          </a>
        )}
      </div>

      <Comments caseId={caseId} draft={draft} />
      {draft.inReview && <Answer caseId={caseId} draftId={draft.id} />}
    </div>
  )
}

function Comments({ caseId, draft }: { caseId: string; draft: ClientDraftVersion }) {
  const queryClient = useQueryClient()
  const key = ['portal', 'case', caseId, 'drafts', draft.id, 'comments']
  const { data, isError, refetch } = useQuery({
    queryKey: key,
    queryFn: ({ signal }) => listComments(caseId, draft.id, signal),
    retry: false,
  })
  const [body, setBody] = useState('')
  const [page, setPage] = useState('')

  const posting = useMutation({
    mutationFn: () => addComment(caseId, draft.id, body.trim(), page ? Number(page) : undefined),
    onSuccess: () => {
      setBody('')
      setPage('')
      void queryClient.invalidateQueries({ queryKey: key })
    },
    onError: (error) => toast.error(draftFailure(error)),
  })

  // Digits only are typed in; "0" is the one value the server's @Positive still refuses.
  const pageValid = page === '' || Number(page) >= 1

  return (
    <div className="space-y-2 border-t pt-4">
      <p className="text-sm font-semibold text-foreground">Comments</p>
      {isError && <ErrorState description="Could not load the comments." onRetry={() => void refetch()} />}
      {data?.length === 0 && <p className="text-sm text-muted-foreground">No comments on this version.</p>}
      {data?.map((c) => (
        <div key={c.id} className="rounded bg-muted/50 p-2 text-sm">
          <p className="text-xs text-muted-foreground">
            {c.authorKind === 'CLIENT' ? 'You' : (c.authorName ?? 'Your case team')}
            {c.page != null && ` · page ${c.page}`} · {formatDateShort(c.createdAt)}
          </p>
          <p className="whitespace-pre-wrap">{c.body}</p>
        </div>
      ))}

      {/* Only the version in review takes comments; earlier threads stay readable. */}
      {draft.inReview && (
        <form
          className="space-y-2"
          onSubmit={(event) => {
            event.preventDefault()
            posting.mutate()
          }}
        >
          <Textarea
            aria-label="Your comment"
            rows={3}
            maxLength={MAX_COMMENT}
            value={body}
            onChange={(event) => setBody(event.target.value)}
            placeholder="What should change, or what is wrong?"
          />
          <div className="flex flex-wrap items-center gap-2">
            <Input
              aria-label="Page (optional)"
              className="w-28"
              inputMode="numeric"
              placeholder="Page"
              value={page}
              onChange={(event) => setPage(event.target.value.replace(/\D/g, ''))}
            />
            <Button type="submit" size="sm" disabled={body.trim() === '' || !pageValid || posting.isPending}>
              {posting.isPending ? 'Posting…' : 'Add comment'}
            </Button>
          </div>
        </form>
      )}
    </div>
  )
}

/** Approve or request changes, each behind an inline confirmation. */
function Answer({ caseId, draftId }: { caseId: string; draftId: string }) {
  const queryClient = useQueryClient()
  const [confirming, setConfirming] = useState<'approve' | 'changes' | null>(null)
  const [notes, setNotes] = useState('')

  const answer = useMutation({
    mutationFn: (kind: 'approve' | 'changes') =>
      kind === 'approve' ? approveDraft(caseId, draftId) : requestChanges(caseId, draftId, notes.trim()),
    onSuccess: (_view, kind) => {
      toast.success(kind === 'approve' ? 'Approved. We have sent it to the expert to sign.' : 'Sent. Your case manager will pick this up.')
      setConfirming(null)
      // Refetched rather than patched: reading it back is the only honest confirmation, and it moved the stage.
      void queryClient.invalidateQueries({ queryKey: ['portal'] })
    },
    onError: (error) => toast.error(draftFailure(error)),
  })

  if (confirming === null) {
    return (
      <div className="flex flex-wrap gap-2 border-t pt-4">
        <Button onClick={() => setConfirming('approve')}>Approve</Button>
        <Button variant="outline" onClick={() => setConfirming('changes')}>
          Request changes
        </Button>
      </div>
    )
  }

  return (
    <div className="space-y-3 rounded border border-primary/40 p-3">
      {confirming === 'approve' ? (
        <p className="text-sm">
          Approving sends this version to the expert to sign. <strong>It cannot be undone from here.</strong>
        </p>
      ) : (
        <>
          <p className="text-sm">Your comments go to your case manager with this request. Anything to add?</p>
          <Textarea
            aria-label="Note (optional)"
            rows={3}
            maxLength={MAX_COMMENT}
            value={notes}
            onChange={(event) => setNotes(event.target.value)}
          />
        </>
      )}
      <div className="flex gap-2">
        <Button onClick={() => answer.mutate(confirming)} disabled={answer.isPending}>
          {answer.isPending ? 'Sending…' : confirming === 'approve' ? 'Yes, approve' : 'Send request'}
        </Button>
        <Button variant="ghost" onClick={() => setConfirming(null)} disabled={answer.isPending}>
          Cancel
        </Button>
      </div>
    </div>
  )
}
