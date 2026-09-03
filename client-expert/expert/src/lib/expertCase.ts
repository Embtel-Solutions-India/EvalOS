/**
 * The expert seam's wire shapes and the judgements its one screen makes (Unit 34e).
 *
 * Pure data and pure functions, importing nothing — which is what lets `expertCase.test.ts`
 * exercise them without a DOM. Every rule here is a *display* decision; the server decides the
 * real ones and answers with its own reason when it refuses.
 *
 * **Nothing here is a second lifecycle.** `ExpertCaseView` is `ExpertPortalService.ExpertCaseView`
 * spelled once, and this file maps its values to words — it never derives a status, orders values
 * into a workflow, or decides what the expert may do. `awaitingAnswer`, `onHold` and `signed` are
 * booleans **the server states**, precisely so the browser does not infer them from a stage.
 * See `context/specs/34-portal-frontend-wiring.md` D5.
 */

/** EvalOS's `ExpertSignStatus`, verbatim. */
export type ExpertSignStatus = 'PENDING' | 'SIGNED' | 'OVERDUE' | 'REASSIGNED'

/** EvalOS's `SlaStatus`, verbatim. Null when no clock is running — a held case, or a closed one. */
export type SlaStatus = 'ON_TRACK' | 'AT_RISK' | 'OVERDUE'

/** `ExpertPortalService.ExpertCaseView`, exactly. No field is added, renamed or computed here. */
export type ExpertCaseView = {
  caseReference: string | null
  applicantName: string | null
  expertName: string | null
  serviceType: string | null
  visaCategory: string | null
  draftLink: string | null
  /** The labels of what the client actually supplied. What they still owe is not the expert's. */
  evidence: string[]
  signStatus: ExpertSignStatus | null
  signSla: SlaStatus | null
  awaitingAnswer: boolean
  onHold: boolean
  signed: boolean
  signedAt: string | null
  /** The exact wording the API requires back. Composed by the server because it is the evidence. */
  attestation: string
}

/** `ExpertPortalService.SignedLetterView`, exactly. */
export type SignedLetterView = {
  documentId: string
  filename: string | null
  version: number
  signedAt: string
  contentSha256: string
}

/**
 * What the screen is for right now.
 *
 * Derived **only** from booleans the server sent, in the order the server's own guards apply:
 * a signed case is finished; a held case is waiting on the client and cannot be signed until a
 * Coordinator resumes it; otherwise the three answers are live. Adding a fourth state here — or
 * deriving one from `signStatus` — would be this app holding an opinion about the lifecycle.
 */
export function stateOf(view: ExpertCaseView): 'SIGNED' | 'ON_HOLD' | 'OPEN' | 'CLOSED' {
  if (view.signed) return 'SIGNED'
  if (view.onHold) return 'ON_HOLD'
  return view.awaitingAnswer ? 'OPEN' : 'CLOSED'
}

/** How each sign status reads, and what colour it wears. `variant` names a `ui/badge` variant. */
export const SIGN_STATUS: Record<
  ExpertSignStatus,
  { label: string; variant: 'muted' | 'info' | 'success' | 'warning' | 'destructive' }
> = {
  PENDING: { label: 'Awaiting your signature', variant: 'warning' },
  SIGNED: { label: 'Signed', variant: 'success' },
  OVERDUE: { label: 'Overdue', variant: 'destructive' },
  REASSIGNED: { label: 'Reassigned', variant: 'muted' },
}

/** How much of the signing budget is left, in the server's own three words. */
export const SIGN_SLA: Record<SlaStatus, { label: string; variant: 'muted' | 'warning' | 'destructive' }> = {
  ON_TRACK: { label: 'On time', variant: 'muted' },
  AT_RISK: { label: 'Due soon', variant: 'warning' },
  OVERDUE: { label: 'Past due', variant: 'destructive' },
}

/**
 * An enum constant as a person reads it.
 *
 * **Generic on purpose: there is no per-value table for `serviceType` or `visaCategory`.** Those
 * are long, open vocabularies that grow in EvalOS, and a table here would be a second list to keep
 * in step — one that shows a raw constant, or nothing, the day a value is added. Prettifying the
 * constant is honest about where the word came from and cannot fall behind.
 */
export function humanize(value: string | null | undefined): string {
  if (!value) return ''
  return value
    .split('_')
    .map((word) => word.charAt(0) + word.slice(1).toLowerCase())
    .join(' ')
}

/** The one line at the top: what this letter has to achieve. */
export function goalOf(view: ExpertCaseView): string {
  const parts = [humanize(view.visaCategory), humanize(view.serviceType)].filter(Boolean)
  return parts.length > 0 ? parts.join(' — ') : 'Expert opinion letter'
}

/**
 * The signed letter must be a PDF, and the server refuses anything else **by content**.
 *
 * Narrower than the client's allowlist deliberately: a JPEG is a fine supporting document and is
 * not a fine deliverable. Stated on the control so nobody discovers it at the 400.
 */
export const SIGNED_LETTER_TYPES = ['PDF']

/** Mirrors `spring.servlet.multipart.max-file-size`. The server is the real cap. */
export const MAX_UPLOAD_MB = 15

/**
 * What to tell an expert whose action was refused.
 *
 * Separate from the client's `failureMessage` because the readers are different people with
 * different next moves: a client is told to ask whoever sent the link, an expert is told to
 * contact the case manager. **409 is the one that matters** — it is a case that moved under them
 * (somebody rematched it, or they are held awaiting the client), and telling them to retry would
 * be telling them to do something that cannot work.
 */
export function expertFailureMessage(status: number | undefined): string {
  if (status === 401) {
    return 'This link is no longer valid. It may have expired, or a newer one may have replaced it. Please ask the case manager who sent it for a new link.'
  }
  if (status === 403) {
    return 'This link does not open that case.'
  }
  if (status === 409) {
    return 'This case has moved on since you opened it — it may be waiting on the client, or it may have been assigned to another expert. Reload the page to see where it stands.'
  }
  if (status === 400) {
    return 'That file was not accepted. The signed letter must be a PDF, and the confirmation must be ticked.'
  }
  if (status === 429) {
    return 'Too many attempts. Please wait a minute and reload this page.'
  }
  if (status === 502 || status === 503) {
    return 'Our document store is temporarily unavailable. Nothing was lost, and nothing was recorded — please try again in a few minutes.'
  }
  return 'We could not complete that. Please try again in a moment, or contact the case manager who sent you this link.'
}
