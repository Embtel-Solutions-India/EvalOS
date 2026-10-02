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
 * of access logs, `Referer` headers and every redirect in between. Once lifted it is kept the way a
 * signed-in token is (Unit 75, D72): `sessionStorage` for that tab, never `localStorage`, so it is
 * gone when the tab closes — which bounds the risk of a link opened on a shared machine.
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
 * What to tell somebody whose **link** does not work.
 *
 * One message for unknown, expired and revoked, because the server answers all three identically
 * and nothing here should imply otherwise. Never a stack trace: the reader is not a developer and
 * cannot act on one.
 *
 * **Never a password, and `portal.test.ts` enforces it — but the reason has changed.** It used to
 * be that the client had no account to log into. Unit 42 gave them one, so the rule now rests on
 * *who is reading*: everyone who reaches this function got here by opening a link, and telling
 * them to try their password would send them looking for one they may never have set. That is why
 * the rule survived the thing that justified it.
 *
 * **The auth screens must NOT use this** — they have `authFailureMessage` in the client app's own
 * `authService`. Reusing this one is exactly the bug it caused: a wrong password answered "We
 * could not load your documents… contact whoever sent you this link."
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

/**
 * One invoice, as the client is shown it (Unit 41).
 *
 * **Deliberately narrow.** GHL's invoice payload carries line items, internal ids and the
 * business's own contact block; a client needs to know what they were billed, when, and whether
 * it is settled. The server projects this whitelist and a test asserts on the serialized body,
 * because a nested DTO passes a field-name check and still leaks.
 *
 * **A settled invoice is not revenue.** EvalOS recognises revenue as *paid AND delivered*
 * (invariant 5), read only through the server's own `RefundService`. Nothing on this screen may
 * be summed into a revenue figure.
 */
export type ClientInvoice = {
  invoiceNumber: string | null
  /** GHL's own word: `paid`, `unpaid`, `partially_paid`, `void`, and whatever else it uses. */
  status: string | null
  total: number | null
  amountPaid: number | null
  amountDue: number | null
  currency: string | null
  issueDate: string | null
  dueDate: string | null
}

// --- Unit 58: the case, its drafts and what is delivered ---------------------

/** The server's `ClientApprovalStatus`, carried on the case view. */
export type ClientApprovalStatus = 'PENDING' | 'APPROVED' | 'REVISION_REQUESTED'

/** One client-language milestone (`CaseMilestones.Milestone`). The label is the server's. */
export type Milestone = { label: string; at: string }

/**
 * One case as the client sees it (`PortalCaseService.ClientDraftView`).
 *
 * **No expert anywhere.** Withholding the expert's identity entirely is the stronger position:
 * there is no redaction to get wrong. `draftLink` is a legacy pasted link, or null.
 */
export type ClientDraftView = {
  clientName: string | null
  serviceType: string
  caseReference: string
  draftLink: string | null
  draftVersion: number
  approvalStatus: ClientApprovalStatus
  /** Whether EvalOS is waiting on the client right now — the server decides, not this app. */
  awaitingAnswer: boolean
  step: string
  /** Stepper position, 0–3 over `CLIENT_STEPS`. */
  stepIndex: number
  milestones: Milestone[]
}

/** The client stepper (58 §4). The server says which one a case is on (`stepIndex`). */
export const CLIENT_STEPS = ['Upload', 'Review', 'Signing', 'Delivered'] as const

/** A `ServiceType` name for reading: `COURSE_BY_COURSE` → "Course by course". */
export function serviceLabel(serviceType: string): string {
  const words = serviceType.replaceAll('_', ' ').toLowerCase()
  return words.charAt(0).toUpperCase() + words.slice(1)
}

/** `stepIndex` of a delivered (or closed) case — Home lists those separately. */
export const DELIVERED_STEP = CLIENT_STEPS.length - 1

/** One row of "my cases" (Unit 35). `step` is a server-rendered phrase, never an enum. */
export type ClientCaseSummary = {
  caseId: string
  caseReference: string
  serviceType: string
  step: string
  actionRequired: boolean
  stepIndex: number
}

/** The draft version statuses a client can be shown (`CaseDrafts`' client-visible set). */
export type ClientDraftStatus = 'PM_APPROVED' | 'CLIENT_APPROVED' | 'CHANGES_REQUESTED'

/** How each reads. A test fails if the set changes without this table. */
export const DRAFT_STATUS: Record<ClientDraftStatus, { label: string; variant: 'default' | 'secondary' | 'outline' }> = {
  PM_APPROVED: { label: 'Awaiting your review', variant: 'default' },
  CLIENT_APPROVED: { label: 'Approved', variant: 'secondary' },
  CHANGES_REQUESTED: { label: 'Changes requested', variant: 'outline' },
}

/** `CaseDrafts.ClientDraftVersion`. Only the version with `inReview` takes comments and answers. */
export type ClientDraftVersion = {
  id: string
  version: number
  status: ClientDraftStatus
  uploadedAt: string
  inReview: boolean
  hasWord: boolean
  hasPdf: boolean
}

/** `CaseDrafts.CommentView`. `authorName` is null for the client reader: staff read as "Your case team". */
export type DraftComment = {
  id: string
  authorKind: 'STAFF' | 'CLIENT'
  authorName: string | null
  body: string
  page: number | null
  createdAt: string
}

/** The comment limit, mirroring the server's `@Size(max = 2000)`. */
export const MAX_COMMENT = 2000

/** `PortalCaseService.DeliveredFile` — served only once the case is delivered. */
export type DeliveredFile = {
  id: string
  kind: 'SIGNED_LETTER' | 'APPROVED_DRAFT'
  filename: string | null
  at: string
}

/**
 * Whether the browser has a viewer for this file (Unit 74): a PDF, PNG or JPEG. The server applies
 * the same rule and forces the type when it serves a view; a Word file only downloads.
 */
export function isViewable(filename: string | null | undefined): boolean {
  return /\.(pdf|png|jpe?g)$/i.test(filename ?? '')
}
