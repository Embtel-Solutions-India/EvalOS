import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { CheckCircle2, ExternalLink, FileText } from 'lucide-react'
import { useState } from 'react'
import { toast } from 'sonner'
import { Badge } from '@shared/components/ui/badge'
import { Button } from '@shared/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@shared/components/ui/card'
import { Checkbox } from '@shared/components/ui/checkbox'
import { Progress } from '@shared/components/ui/progress'
import { Textarea } from '@shared/components/ui/textarea'
import { EmptyState } from '@shared/components/common/EmptyState'
import { ErrorState } from '@shared/components/common/ErrorState'
import { FileDropzone, validateFile } from '@shared/components/common/FileDropzone'
import { ListSkeleton } from '@shared/components/common/LoadingState'
import { PageHeader } from '@shared/components/common/PageHeader'
import { NO_TOKEN, tokenFromFragment } from '@shared/lib/portal'
import { hasPortalToken, setPortalToken, statusOf } from '@shared/services/apiClient'
import { formatDate } from '@shared/utils/formatters'
import {
  expertFailureMessage,
  goalOf,
  MAX_UPLOAD_MB,
  SIGN_SLA,
  SIGN_STATUS,
  SIGNED_LETTER_TYPES,
  stateOf,
  type ExpertCaseView,
} from '@/lib/expertCase'
import {
  accept,
  decline,
  getCase,
  letterLink,
  requestEvidence,
  uploadSignedLetter,
} from '@/services/expertPortalService'

/**
 * The expert's assigned case, against EvalOS (Unit 15, wired in 34e).
 *
 * **One column, because the expert has one decision to make and reads top to bottom**: the goal,
 * then the letter, then the evidence it rests on, then the answers. The account shell around the
 * rest of this app is deliberately not here — the credential is a scoped portal link naming one
 * case, not a session, and mounting this behind `ExpertAuthenticatedRoute` would answer Unit 34's
 * decision D1 by accident. Same reasoning, same shape as the client's `/documents` (34c).
 *
 * **There is no e-signature step and the copy says so.** Download the letter, sign it in whatever
 * tool you already use — a scanned wet signature is expected and accepted — and upload it back.
 * Nobody should be hunting for a signing button that does not exist.
 *
 * **No lifecycle word on this page is computed here.** Every label comes from a value EvalOS sent.
 */
export default function ExpertCasePortal() {
  const queryClient = useQueryClient()

  // Captured during the first render rather than in an effect: the token has to be on the client
  // before the query fires, and an effect runs after.
  const [tokenPresent] = useState(() => {
    const token = tokenFromFragment(window.location.hash)
    if (token) setPortalToken(token)
    return hasPortalToken()
  })

  const { data, isLoading, isError, error, refetch } = useQuery({
    queryKey: ['expert-portal', 'case'],
    queryFn: getCase,
    enabled: tokenPresent,
    retry: false,
  })

  const refresh = () => void queryClient.invalidateQueries({ queryKey: ['expert-portal', 'case'] })

  if (!tokenPresent) {
    return (
      <div className="mx-auto max-w-2xl p-6">
        <PageHeader title="Your assigned case" description={NO_TOKEN} />
      </div>
    )
  }

  return (
    <div className="mx-auto max-w-3xl p-6">
      {isLoading && <ListSkeleton />}
      {isError && (
        <ErrorState description={expertFailureMessage(statusOf(error))} onRetry={() => void refetch()} />
      )}

      {!isLoading && !isError && data && <CaseBody view={data} onChanged={refresh} />}
    </div>
  )
}

function CaseBody({ view, onChanged }: { view: ExpertCaseView; onChanged: () => void }) {
  const state = stateOf(view)
  const signStatus = view.signStatus ? SIGN_STATUS[view.signStatus] : null
  const sla = view.signSla ? SIGN_SLA[view.signSla] : null

  return (
    <>
      <PageHeader
        title={goalOf(view)}
        description={
          view.applicantName
            ? `A letter about ${view.applicantName}. Case ${view.caseReference ?? ''}`.trim()
            : `Case ${view.caseReference ?? ''}`.trim()
        }
        actions={
          <div className="flex items-center gap-2">
            {signStatus && <Badge variant={signStatus.variant}>{signStatus.label}</Badge>}
            {/* No clock runs while a case is held, so the server sends no SLA and none is shown. */}
            {sla && <Badge variant={sla.variant}>{sla.label}</Badge>}
          </div>
        }
      />

      {state === 'ON_HOLD' && (
        <Card className="mb-6 border-warning/40 bg-warning/5">
          <CardContent className="p-4 text-sm text-foreground">
            <p className="font-medium">We are waiting on the client, not on you.</p>
            <p className="mt-1 text-muted-foreground">
              You asked for more evidence, so this case is on hold until the coordinator has it. You will
              be able to sign once it is back with you — nothing is running against your time in the
              meantime.
            </p>
          </CardContent>
        </Card>
      )}

      {state === 'SIGNED' && (
        <Card className="mb-6 border-success/40 bg-success/5">
          <CardContent className="flex items-start gap-3 p-4 text-sm">
            <CheckCircle2 className="mt-0.5 h-4 w-4 shrink-0 text-success" />
            <div>
              <p className="font-medium text-foreground">Signed letter received.</p>
              <p className="mt-1 text-muted-foreground">
                {view.signedAt ? `We have it as of ${formatDate(view.signedAt)}. ` : ''}
                It is with the project manager for a final check. There is nothing further for you to do
                on this case.
              </p>
            </div>
          </CardContent>
        </Card>
      )}

      <Card className="mb-6">
        <CardHeader>
          <CardTitle className="text-sm">The letter</CardTitle>
        </CardHeader>
        <CardContent>
          {view.draftLink ? (
            <LetterButton />
          ) : (
            <p className="text-sm text-muted-foreground">
              The letter is not ready yet. The case manager will let you know when it is.
            </p>
          )}
        </CardContent>
      </Card>

      <Card className="mb-6">
        <CardHeader>
          <CardTitle className="text-sm">What the opinion rests on</CardTitle>
        </CardHeader>
        <CardContent>
          {view.evidence.length === 0 ? (
            <EmptyState
              icon={FileText}
              title="No evidence recorded yet"
              description="Nothing the client sent has been accepted onto this case so far."
            />
          ) : (
            <ul className="space-y-2">
              {view.evidence.map((label) => (
                <li key={label} className="flex items-center gap-2 text-sm text-foreground">
                  <FileText className="h-4 w-4 shrink-0 text-muted-foreground" />
                  {label}
                </li>
              ))}
            </ul>
          )}
        </CardContent>
      </Card>

      {state === 'OPEN' && (
        <>
          <SignPanel view={view} onSigned={onChanged} />
          <Answers onChanged={onChanged} />
        </>
      )}
    </>
  )
}

/**
 * Opens the letter.
 *
 * The link is fetched on the click and used immediately, the way the client's presigned downloads
 * are — and the fetch is what records that the expert opened it. `noopener` because the opened
 * document has no business reaching back into this tab.
 */
function LetterButton() {
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
      const url = await letterLink()
      if (tab) tab.location.href = url
      // A blocked popup is not a failure of the fetch, so it is reported as itself.
      else toast.error('Allow pop-ups for this site to open the letter.')
    }
    catch (openError: unknown) {
      tab?.close()
      toast.error(expertFailureMessage(statusOf(openError)))
    }
    finally {
      setBusy(false)
    }
  }

  return (
    <Button variant="outline" onClick={() => void open()} disabled={busy}>
      <ExternalLink className="h-4 w-4" />
      Open the letter
    </Button>
  )
}

/**
 * Download → sign in your own tool → upload back.
 *
 * **The attestation is part of the upload, not a step of its own.** It is required, the file input
 * stays disabled until it is ticked, and the API refuses an upload without it regardless of what
 * this component does — it is the evidence, and a UI-only checkbox would be no evidence at all.
 * The wording is the server's, sent on the view and returned unedited.
 */
function SignPanel({ view, onSigned }: { view: ExpertCaseView; onSigned: () => void }) {
  const [confirmed, setConfirmed] = useState(false)
  // The attestation names a person, and the server takes that name off the case rather than from
  // this app. If EvalOS could not tell us whose case this is, the sentence on the tick is a
  // placeholder and the upload would be refused — saying so beats a refusal nobody can act on.
  const nameKnown = Boolean(view.expertName)
  const [progress, setProgress] = useState<number | null>(null)
  const [rejection, setRejection] = useState<string | undefined>()

  const upload = useMutation({
    mutationFn: (file: File) => uploadSignedLetter(file, view.attestation, setProgress),
    onSuccess: () => {
      setProgress(null)
      toast.success('Signed letter received. Thank you.')
      onSigned()
    },
    onError: (uploadError: unknown) => {
      setProgress(null)
      toast.error(expertFailureMessage(statusOf(uploadError)))
    },
  })

  function onFileSelected(file: File) {
    const problem = validateFile(file, SIGNED_LETTER_TYPES, MAX_UPLOAD_MB)
    setRejection(problem ?? undefined)
    if (problem) return
    upload.mutate(file)
  }

  return (
    <Card className="mb-6">
      <CardHeader>
        <CardTitle className="text-sm">Sign and send it back</CardTitle>
      </CardHeader>
      <CardContent className="space-y-4">
        <p className="text-sm text-muted-foreground">
          Open the letter above, sign it in whatever tool you already use, and upload the signed copy
          here. <span className="font-medium text-foreground">A scanned wet signature is expected and
          accepted</span> — there is no e-signature step to look for.
        </p>

        {!nameKnown && (
          <p className="text-sm text-destructive">
            We cannot confirm whose signature this would be, so the upload is closed. Please contact
            the case manager who sent you this link.
          </p>
        )}

        <label className="flex items-start gap-3 text-sm text-foreground">
          <Checkbox
            disabled={!nameKnown}
            checked={confirmed}
            onCheckedChange={(next) => setConfirmed(next === true)}
            aria-label="Confirm this is your signature"
            className="mt-0.5"
          />
          <span>{view.attestation}</span>
        </label>

        {progress !== null ? (
          <Progress value={progress} />
        ) : (
          <FileDropzone
            accept={SIGNED_LETTER_TYPES}
            maxSizeMb={MAX_UPLOAD_MB}
            onFileSelected={onFileSelected}
            disabled={!confirmed || !nameKnown || upload.isPending}
            error={rejection}
          />
        )}

        {!confirmed && (
          <p className="text-xs text-muted-foreground">Tick the confirmation to enable the upload.</p>
        )}
      </CardContent>
    </Card>
  )
}

/** Accept · Ask for more evidence · Decline. Each one says plainly what it does to the case. */
function Answers({ onChanged }: { onChanged: () => void }) {
  const [missing, setMissing] = useState('')
  const [reason, setReason] = useState('')

  const act = (call: () => Promise<unknown>, done: string) => async () => {
    try {
      await call()
      toast.success(done)
      onChanged()
    }
    catch (actionError: unknown) {
      toast.error(expertFailureMessage(statusOf(actionError)))
    }
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-sm">Your answer</CardTitle>
      </CardHeader>
      <CardContent className="space-y-6">
        <div>
          <Button onClick={() => void act(accept, 'Thank you — the case manager has been told.')()}>
            I will sign this
          </Button>
          <p className="mt-2 text-xs text-muted-foreground">
            Tells the case manager you have taken it. You can still upload the signed letter later.
          </p>
        </div>

        <div>
          <p className="mb-2 text-sm font-medium text-foreground">Ask for more evidence</p>
          <Textarea
            value={missing}
            onChange={(event) => setMissing(event.target.value)}
            placeholder="What do you need before you can sign? Be specific — the client is asked for exactly this."
            rows={3}
          />
          <Button
            variant="outline"
            className="mt-2"
            disabled={missing.trim().length === 0}
            onClick={() => void act(() => requestEvidence(missing.trim()), 'We will ask the client for it.')()}
          >
            Ask for it
          </Button>
          <p className="mt-2 text-xs text-muted-foreground">
            Puts the case on hold with the client. You will not be able to sign until it comes back.
          </p>
        </div>

        <div>
          <p className="mb-2 text-sm font-medium text-foreground">Decline this case</p>
          <Textarea
            value={reason}
            onChange={(event) => setReason(event.target.value)}
            placeholder="Why can you not take it? This goes to the case manager, who will find another expert."
            rows={3}
          />
          <Button
            variant="outline"
            className="mt-2"
            disabled={reason.trim().length === 0}
            onClick={() => void act(() => decline(reason.trim()), 'Understood — the case goes back for rematching.')()}
          >
            Decline
          </Button>
          <p className="mt-2 text-xs text-muted-foreground">
            This sends the case back to be matched with another expert. It cannot be undone from here.
          </p>
        </div>
      </CardContent>
    </Card>
  )
}
