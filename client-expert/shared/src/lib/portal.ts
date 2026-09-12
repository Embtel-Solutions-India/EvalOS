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

/**
 * A meeting the client has with the business.
 *
 * **`startsAt` and `endsAt` are NOT ISO-8601.** GHL sends `"2026-09-13 12:30:00"` — a space
 * instead of a `T`, and **no timezone offset at all**. `new Date(...)` on that string is
 * implementation-defined and will silently produce the wrong instant in some browsers, so the
 * portal formats these as text and does not construct a `Date` from them. Nothing downstream
 * may assume otherwise; see `GhlCalendarClient.forContact` for why the server does not parse
 * them either.
 *
 * **`location` is a join link for an online meeting and a street address for one in person.**
 * Named for what it is rather than assumed to be a URL, because the page has to render both.
 *
 * **What is deliberately absent:** the stage, the deal value and any sales note. The portal
 * shows a client their invoices and their meetings and nothing else of the opportunity —
 * decided 2026-09-11. Stage names are written for staff.
 */
export type ClientMeeting = {
  id: string | null
  title: string | null
  startsAt: string | null
  endsAt: string | null
  /** GHL's own word: `confirmed`, `cancelled`, `showed`, `noshow`, and whatever else it uses. */
  status: string | null
  location: string | null
}

// --- 34b: the draft the client reviews -------------------------------------

/**
 * Where a draft stands with the client.
 *
 * **The server's vocabulary, unmapped.** Like `ChecklistItemStatus`, this app holds a label
 * table for the values EvalOS can send and never derives one — a status this screen computed
 * would be a second opinion about whether a client has approved something.
 */
export type ClientApprovalStatus = 'PENDING' | 'APPROVED' | 'REVISION_REQUESTED'

/** How each status reads, and how it looks. A test fails if the server can send a fourth. */
export const APPROVAL_STATUS: Record<
  ClientApprovalStatus,
  { label: string; variant: 'default' | 'secondary' | 'outline' }
> = {
  PENDING: { label: 'Awaiting your review', variant: 'default' },
  APPROVED: { label: 'Approved', variant: 'secondary' },
  REVISION_REQUESTED: { label: 'Revisions requested', variant: 'outline' },
}

/**
 * The draft as the client sees it (Unit 14, `PortalCaseService.ClientDraftView`).
 *
 * **No expert anywhere.** Unit 13's redacted profile was deleted with the unit, and withholding
 * the expert's identity entirely is the stronger position: there is no redaction to get wrong.
 *
 * `draftLink` is a link the Case Manager pasted, not an S3 key — only client *uploads* have
 * object keys today, so this cannot be presigned and must be rendered as an external link.
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
}

/** One row of "my cases" (Unit 35). `step` is a server-rendered phrase, never an enum. */
export type ClientCaseSummary = {
  caseId: string
  caseReference: string
  serviceType: string
  step: string
  actionRequired: boolean
}
