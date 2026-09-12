import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useParams } from 'react-router-dom'
import { ExternalLink, FileCheck } from 'lucide-react'
import { useState } from 'react'
import { toast } from 'sonner'
import { Badge } from '@shared/components/ui/badge'
import { Button } from '@shared/components/ui/button'
import { Card } from '@shared/components/ui/card'
import { Textarea } from '@shared/components/ui/textarea'
import { EmptyState } from '@shared/components/common/EmptyState'
import { ErrorState } from '@shared/components/common/ErrorState'
import { PageHeader } from '@shared/components/common/PageHeader'
import { TableSkeleton } from '@shared/components/common/LoadingState'
import {
  APPROVAL_STATUS,
  failureMessage,
  NO_TOKEN,
  type ClientCaseSummary,
} from '@shared/lib/portal'
import { usePortalToken } from '@shared/hooks/usePortalToken'
import { statusOf } from '@shared/services/apiClient'
import { approve, listCases, readDraftFor, requestRevisions } from '@/services/draftService'

/**
 * The client reads their draft and answers it (34b).
 *
 * **The three endpoints behind this have existed since Unit 14 and nothing called them.** That
 * is what made this the highest-value screen left in the portal: EvalOS could already accept an
 * approval, and the app had no way to send one.
 *
 * **Approving sends the letter to an expert to sign** (Handoff B) and there is no undo that
 * reaches the client. So the button says what it does, and a party link covering several cases
 * asks which one rather than guessing — the server refuses to guess, and this screen is how the
 * client answers.
 *
 * **Outside the account shell**, like `/documents` and `/invoices`: the credential is a scoped
 * portal link, not the mock account session.
 *
 * **It holds no lifecycle vocabulary.** `APPROVAL_STATUS` maps the three values EvalOS can send
 * to a label; `awaitingAnswer` is the server's own flag. Nothing here decides whether a client
 * still owes an answer — that would be a second opinion about the case.
 */
export default function DraftReview() {
  const queryClient = useQueryClient()

  const tokenPresent = usePortalToken()

  // A case named in the path wins over the picker: the case list links here directly, and
  // making somebody re-choose what they just clicked is a step that exists by accident.
  const { caseId: fromPath } = useParams<{ caseId: string }>()
  const [chosen, setChosen] = useState<string | null>(null)

  const cases = useQuery({
    queryKey: ['portal', 'cases'],
    queryFn: ({ signal }) => listCases(signal),
    enabled: tokenPresent,
    retry: false,
  })

  // A party link with exactly one case needs no picker: choosing from a list of one is a step
  // that exists only because the code could not be bothered to notice.
  const onlyCase = cases.data?.length === 1 ? cases.data[0].caseId : null
  const caseId = fromPath ?? chosen ?? onlyCase

  const draft = useQuery({
    queryKey: ['portal', 'draft', caseId],
    queryFn: ({ signal }) => readDraftFor(caseId as string, signal),
    enabled: Boolean(caseId),
    retry: false,
  })

  const answered = () => {
    // Refetched rather than patched: the only honest confirmation an approval landed is reading
    // it back, and this one also moves the case's stage.
    void queryClient.invalidateQueries({ queryKey: ['portal'] })
  }

  const approving = useMutation({
    mutationFn: () => approve(caseId as string),
    onSuccess: () => {
      toast.success('Approved. We have sent it to the expert to sign.')
      answered()
    },
    onError: (error) => toast.error(failureMessage(statusOf(error))),
  })

  const [notes, setNotes] = useState('')
  const revising = useMutation({
    mutationFn: () => requestRevisions(caseId as string, notes.trim()),
    onSuccess: () => {
      toast.success('Sent. Your case manager will pick this up.')
      setNotes('')
      answered()
    },
    onError: (error) => toast.error(failureMessage(statusOf(error))),
  })

  if (!tokenPresent) {
    return (
      <div className="mx-auto max-w-2xl p-6">
        <PageHeader title="Your draft" description={NO_TOKEN} />
      </div>
    )
  }

  return (
    <div className="mx-auto max-w-3xl space-y-6 p-6">
      <PageHeader
        title="Your draft"
        description="Read it, then either approve it or tell us what to change."
      />

      {cases.isLoading && <TableSkeleton />}
      {cases.isError && (
        <ErrorState
          description={
            // A case-scoped link cannot list cases — it names one. That is a different link,
            // not a fault, and the generic message would send them to support for nothing.
            statusOf(cases.error) === 403
              ? 'This link opens a single case. Use the link we sent for that case to review its draft.'
              : failureMessage(statusOf(cases.error))
          }
          onRetry={() => void cases.refetch()}
        />
      )}

      {cases.data && cases.data.length === 0 && (
        <EmptyState icon={FileCheck} title="Nothing to review" description="You have no cases yet." />
      )}

      {/* The picker is for a party link with several cases and no case named in the path. */}
      {!fromPath && cases.data && cases.data.length > 1 && (
        <CasePicker cases={cases.data} chosen={caseId} onChoose={setChosen} />
      )}

      {caseId && draft.isLoading && <TableSkeleton />}
      {caseId && draft.isError && (
        <ErrorState
          description={failureMessage(statusOf(draft.error))}
          onRetry={() => void draft.refetch()}
        />
      )}

      {draft.data && (
        <Card className="space-y-4 p-5">
          <div className="flex flex-wrap items-baseline justify-between gap-2">
            <div>
              <p className="text-sm font-semibold text-foreground">{draft.data.caseReference}</p>
              <p className="text-xs text-muted-foreground">
                Version {draft.data.draftVersion}
              </p>
            </div>
            <Badge variant={APPROVAL_STATUS[draft.data.approvalStatus].variant}>
              {APPROVAL_STATUS[draft.data.approvalStatus].label}
            </Badge>
          </div>

          {draft.data.draftLink ? (
            <a
              href={draft.data.draftLink}
              target="_blank"
              rel="noreferrer noopener"
              className="inline-flex items-center gap-2 text-sm font-medium text-primary underline"
            >
              Open the draft <ExternalLink className="h-4 w-4" />
            </a>
          ) : (
            <p className="text-sm text-muted-foreground">
              {/*
                `draftLink` is a link the Case Manager pastes, not an S3 key — only client uploads
                have object keys today, so there is nothing to presign and nothing to show until
                they paste one. Saying so beats an empty space.
              */}
              The draft is not ready to read yet. We will let you know.
            </p>
          )}

          {draft.data.awaitingAnswer ? (
            <div className="space-y-4 border-t pt-4">
              <div>
                <Button
                  onClick={() => approving.mutate()}
                  disabled={approving.isPending || revising.isPending}
                >
                  {approving.isPending ? 'Approving…' : 'Approve this draft'}
                </Button>
                <p className="mt-1 text-xs text-muted-foreground">
                  {/*
                    Approving is Handoff B: it sends the letter to an expert to sign, and there
                    is no undo that reaches the client. A button that did that silently would be
                    the wrong kind of quiet.
                  */}
                  This sends it to the expert to sign. It cannot be undone from here.
                </p>
              </div>

              <div className="space-y-2">
                <label htmlFor="revisions" className="text-sm font-medium text-foreground">
                  Or tell us what to change
                </label>
                <Textarea
                  id="revisions"
                  rows={4}
                  value={notes}
                  onChange={(event) => setNotes(event.target.value)}
                  placeholder="What needs to be different?"
                />
                <Button
                  variant="outline"
                  disabled={notes.trim() === '' || revising.isPending || approving.isPending}
                  onClick={() => revising.mutate()}
                >
                  {revising.isPending ? 'Sending…' : 'Request changes'}
                </Button>
                <p className="text-xs text-muted-foreground">
                  {/* The server requires a reason: revisions with none are useless to the CM. */}
                  Please say what needs changing — your case manager works from your words.
                </p>
              </div>
            </div>
          ) : (
            <p className="border-t pt-4 text-sm text-muted-foreground">
              {/*
                `awaitingAnswer` is the server's flag, not a status this screen inferred. When it
                is false there is nothing for the client to do, whatever the approval status says.
              */}
              Nothing is needed from you on this case right now.
            </p>
          )}
        </Card>
      )}
    </div>
  )
}

/**
 * Which case, when a party link covers several.
 *
 * The server refuses to guess for the two write actions — approving is irreversible — so this
 * is where the client answers. Shown only for more than one case.
 */
function CasePicker({
  cases,
  chosen,
  onChoose,
}: {
  cases: readonly ClientCaseSummary[]
  chosen: string | null
  onChoose: (caseId: string) => void
}) {
  return (
    <div className="space-y-2">
      <p className="text-sm font-medium text-foreground">Which case?</p>
      <div className="grid gap-2">
        {cases.map((item) => (
          <button
            key={item.caseId}
            type="button"
            onClick={() => onChoose(item.caseId)}
            className={`rounded border p-3 text-left text-sm ${
              chosen === item.caseId ? 'border-primary bg-accent' : 'border-border'
            }`}
          >
            <span className="font-medium">{item.caseReference}</span>
            <span className="ml-2 text-muted-foreground">{item.step}</span>
            {/* The server decides what needs the client; this only draws it. */}
            {item.actionRequired && (
              <Badge variant="default" className="ml-2">
                Needs you
              </Badge>
            )}
          </button>
        ))}
      </div>
    </div>
  )
}
