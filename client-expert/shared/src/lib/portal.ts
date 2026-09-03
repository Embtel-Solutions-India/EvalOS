/**
 * The portal seam's wire shapes and the judgements the document screen makes.
 *
 * Pure data and pure functions, importing nothing — which is what lets `portal.test.ts` exercise
 * them without a DOM, and what keeps this file out of every page's dependency graph. Every rule
 * here is a *display* decision. The server decides the real ones and answers with its own reason
 * when it refuses.
 *
 * **Nothing here is a second lifecycle.** The status values below are EvalOS's own
 * `ChecklistItemStatus`, spelled once, and this file maps them to words and colours — it never
 * derives a status, orders them into a workflow, or invents a value the server does not send.
 * See `context/specs/34-portal-frontend-wiring.md` D5.
 */

/** `PortalCaseService.ChecklistItemView.status` — Unit 10's vocabulary, verbatim. */
export type ChecklistItemStatus = 'REQUIRED' | 'UPLOADED' | 'APPROVED' | 'MISSING' | 'INCORRECT'

/** `PortalCaseService.ChecklistItemView`, exactly. */
export type ChecklistItem = {
  id: string
  label: string
  status: ChecklistItemStatus
}

/** `PortalCaseService.UploadedDocumentView`, exactly. No object key: it is an internal address. */
export type UploadedDocument = {
  id: string
  filename: string | null
  version: number
  uploadedAt: string
  /** Which requirement this answered, so two PDFs are tellable apart. */
  checklistLabel: string | null
}

/** `PortalCaseService.ClientDocumentsView`, exactly. */
export type ClientDocumentsView = {
  checklist: ChecklistItem[]
  uploaded: UploadedDocument[]
}

/**
 * The token, out of the URL fragment.
 *
 * A fragment and not a query parameter, because a fragment is never sent to a server: it stays out
 * of access logs, `Referer` headers and every redirect in between. It is held in memory only —
 * nothing is written to `localStorage`, which the rest of this app uses for its mock session. A
 * link forwarded to a shared machine is a different risk from a person signing in at their own
 * desk, and this surface is the forwarded-link one.
 */
export function tokenFromFragment(hash: string): string | null {
  const token = hash.replace(/^#/, '').trim()
  return token.length > 0 ? token : null
}

/**
 * How each checklist status reads, and what colour it wears.
 *
 * `variant` names one of `components/ui/badge`'s variants. **`MISSING` and `INCORRECT` are the two
 * that matter**: they are the Coordinator's "you did not send this" and "what you sent will not
 * do", and showing them here is touchpoint T4 arriving as a state the client can see rather than a
 * message EvalOS has no channel to send.
 */
export const CHECKLIST_STATUS: Record<
  ChecklistItemStatus,
  { label: string; variant: 'muted' | 'info' | 'success' | 'warning' | 'destructive' }
> = {
  REQUIRED: { label: 'Needed', variant: 'muted' },
  UPLOADED: { label: 'Received', variant: 'info' },
  APPROVED: { label: 'Accepted', variant: 'success' },
  MISSING: { label: 'Still needed', variant: 'destructive' },
  INCORRECT: { label: 'Please replace', variant: 'destructive' },
}

/** Whether this item is waiting on the client. Drives ordering, not permission — any item accepts an upload. */
export function needsClientAction(status: ChecklistItemStatus): boolean {
  return status === 'REQUIRED' || status === 'MISSING' || status === 'INCORRECT'
}

/** Outstanding items first: what someone opened this page to do should not be below what they finished. */
export function actionFirst(checklist: ChecklistItem[]): ChecklistItem[] {
  return [...checklist].sort(
    (a, b) => Number(needsClientAction(b.status)) - Number(needsClientAction(a.status)),
  )
}

/** The client's own upload cap, mirroring `spring.servlet.multipart.max-file-size`. */
export const MAX_UPLOAD_MB = 15

/**
 * What to tell somebody whose link does not work.
 *
 * One message for unknown, expired and revoked, because the server answers all three identically
 * and nothing here should imply otherwise. **Never a login form** — the client has no EvalOS
 * account, and offering one sends them hunting for a password that does not exist. Never a stack
 * trace either: the reader is not a developer and cannot act on one.
 */
export function failureMessage(status: number | undefined): string {
  if (status === 401) {
    return 'This link is no longer valid. It may have expired, or a newer link may have replaced it. Please ask whoever sent it to you for a new one.'
  }
  if (status === 403) {
    return 'This link does not open that document. Please go back and try again from your own list.'
  }
  if (status === 429) {
    return 'Too many attempts. Please wait a minute and reload this page.'
  }
  if (status === 502) {
    return 'Our document store is temporarily unavailable. Nothing was lost — please try again in a few minutes.'
  }
  return 'We could not load your documents. Please try again in a moment, or contact whoever sent you this link.'
}

/** Said when the address bar has no fragment at all — a different problem from a refused link. */
export const NO_TOKEN =
  'This page needs the full link that was sent to you, including everything after the # sign. ' +
  'Please open it again from the original message, or ask whoever sent it for a new one.'
