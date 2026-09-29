import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowLeft, CheckCircle2, Clock, ExternalLink, FileSignature, FileText, Hash } from 'lucide-react'
import type { ComponentType, ReactNode } from 'react'
import { useState } from 'react'
import { Link, Navigate, useSearchParams } from 'react-router-dom'
import { toast } from 'sonner'
import { Badge } from '@shared/components/ui/badge'
import { Button } from '@shared/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@shared/components/ui/card'
import { Checkbox } from '@shared/components/ui/checkbox'
import { Progress } from '@shared/components/ui/progress'
import { EmptyState } from '@shared/components/common/EmptyState'
import { ErrorState } from '@shared/components/common/ErrorState'
import { FileDropzone, validateFile } from '@shared/components/common/FileDropzone'
import { ListSkeleton } from '@shared/components/common/LoadingState'
import { PageHeader } from '@shared/components/common/PageHeader'
import { statusOf } from '@shared/services/apiClient'
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
import { getCase, letterLink, setOpenCase, uploadSignedLetter } from '@/services/expertPortalService'
import { Answers } from '@/components/Answers'
import { ExpertChat } from '@/components/ExpertChat'

/**
 * The expert's assigned case, against EvalOS (Unit 15, wired in 34e).
 *
 * **One column for the case, because the expert has one decision to make and reads top to bottom**:
 * the goal, then the letter, then the evidence it rests on, then the answers. The case team's
 * conversation sits beside it (Unit 57). The account shell around the
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

  // The shell (`ExpertLayout`) guards the token; signing in is the only way to hold one (Unit 59).
  const caseId = useSearchParams()[0].get('caseId')
  setOpenCase(caseId)

  const { data, isLoading, isError, error, refetch } = useQuery({
    queryKey: ['expert-portal', 'case', caseId],
    queryFn: getCase,
    retry: false,
  })

  const refresh = () => void queryClient.invalidateQueries({ queryKey: ['expert-portal', 'case'] })

  // A case is always named; the list is where one is chosen.
  if (!caseId) return <Navigate to="/cases" replace />

  return (
    <div className="mx-auto grid max-w-7xl gap-6 xl:grid-cols-[minmax(0,1fr)_24rem]">
      <div className="min-w-0">
        <Link to="/cases" className="mb-4 inline-flex items-center gap-1.5 text-sm text-primary hover:underline">
          <ArrowLeft className="h-4 w-4" />
          All your cases
        </Link>
        {isLoading && <ListSkeleton />}
        {isError && (
          <ErrorState description={expertFailureMessage(statusOf(error))} onRetry={() => void refetch()} />
        )}

        {!isLoading && !isError && data && <CaseBody caseId={caseId} view={data} onChanged={refresh} />}
      </div>
      {/* Beside the case, below it on a phone (Unit 57 §7). */}
      {data && <ExpertChat caseReference={data.caseReference} />}
    </div>
  )
}

function CaseBody({ caseId, view, onChanged }: { caseId: string; view: ExpertCaseView; onChanged: () => void }) {
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

      <Card className="mb-6 grid divide-y sm:grid-cols-3 sm:divide-x sm:divide-y-0">
        <Fact icon={FileText} label="Letter">
          {view.draftLink ? 'Ready to open' : 'Not ready yet'}
        </Fact>
        <Fact icon={FileSignature} label="Your signature">
          {view.signed ? `Signed${view.signedAt ? ` ${formatDate(view.signedAt)}` : ''}` : (signStatus?.label ?? 'Not yet asked')}
        </Fact>
        <Fact icon={state === 'ON_HOLD' ? Clock : Hash} label={state === 'ON_HOLD' ? 'Clock' : 'Case'}>
          {state === 'ON_HOLD' ? 'Paused while on hold' : (view.caseReference ?? '—')}
        </Fact>
      </Card>

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
          <Answers caseId={caseId} onChanged={onChanged} />
        </>
      )}
    </>
  )
}

function Fact({ icon: Icon, label, children }: { icon: ComponentType<{ className?: string }>; label: string; children: ReactNode }) {
  return (
    <div className="flex items-start gap-3 p-4">
      <Icon className="mt-0.5 h-4 w-4 shrink-0 text-muted-foreground" />
      <div>
        <p className="text-xs text-muted-foreground">{label}</p>
        <p className="text-sm font-medium text-foreground">{children}</p>
      </div>
    </div>
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
