import type { Role } from '../../lib/session'
/**
 * The client portal's wire shape and the four judgements its one screen makes.
 *
 * Pure data and pure functions, importing nothing — which is what lets `portalRules.test.ts`
 * exercise them without a DOM, and what keeps this file out of the staff bundle's dependency
 * graph. Every rule here is a display decision; the server decides all four for real and answers
 * 409 with its own reason when it refuses.
 */

export type ClientApprovalStatus = 'PENDING' | 'APPROVED' | 'REVISION_REQUESTED'

/** `PortalCaseService.ClientDraftView`, exactly. Nothing else about the case exists out here. */
export type ClientDraftView = {
  clientName: string | null
  serviceType: string | null
  caseReference: string
  /** Null until a draft has been submitted. Never the client's documents folder — see below. */
  draftLink: string | null
  draftVersion: number
  approvalStatus: ClientApprovalStatus | null
  /** The server's own answer on whether the two actions are live. Not derived here. */
  awaitingAnswer: boolean
  /** A whole HTML document for an iframe's `srcdoc`, anonymous by construction (Unit 13). */
  expertProfile: string | null
  expertReference: string | null
}

/**
 * The token, out of the URL fragment.
 *
 * A fragment and not a query parameter, because a fragment is never sent to a server: it stays
 * out of access logs, `Referer` headers and every redirect in between. It is held in memory only
 * — nothing is written to `sessionStorage`, which Unit 07 uses for the staff token on purpose. A
 * link forwarded to a shared machine is a different risk from a person signing in at their own
 * desk.
 */
export function tokenFromFragment(hash: string): string | null {
  const token = hash.replace(/^#/, '').trim()
  return token.length > 0 ? token : null
}

/**
 * What the client may still do.
 *
 * Taken from the server's `awaitingAnswer` rather than re-derived from `approvalStatus`, because
 * the transition's own guard is the authority on it and a second copy of that rule in the browser
 * is the copy that goes stale. A draft with no link is not actionable whatever the state says:
 * approving a document you were never shown is not a decision.
 */
export function mayAct(view: ClientDraftView): boolean {
  return view.awaitingAnswer && view.draftLink !== null
}

/**
 * Whether there is a draft to read at all.
 *
 * **A null link is shown as "not ready" and never as anything else.** `drive_link` — the folder
 * holding this client's own passport scans and transcripts — is not sent to this surface at all,
 * so there is nothing here to fall back to even by accident. That was the defect Unit 14 opened by
 * closing, and this is where the honest answer gets rendered.
 */
export function draftReady(view: ClientDraftView): boolean {
  return view.draftLink !== null
}

/**
 * What the page says about where things stand.
 *
 * The post-action states are the point: after approving, the client is told what happens next and
 * the buttons are gone (the link still reads — it simply has nothing left to do). A page that
 * looked unchanged after an irreversible act invites a second press.
 */
export function statusMessage(view: ClientDraftView): string {
  if (!draftReady(view)) {
    return 'Your draft is not ready to review yet. Whoever sent you this link will be in touch when it is.'
  }
  if (view.approvalStatus === 'APPROVED') {
    return 'Thank you — you have approved this draft. It has gone to the expert for signature, and you will receive the signed letter when that is done.'
  }
  if (view.approvalStatus === 'REVISION_REQUESTED') {
    return 'Your revision request has been sent. The case manager is working on a new version and will send it to you here.'
  }
  if (view.awaitingAnswer) {
    return 'Please read the draft below, then either approve it or tell us what needs to change.'
  }
  return 'There is nothing waiting for your answer on this case right now.'
}

/**
 * What to tell somebody whose link does not work.
 *
 * One message for unknown, expired and revoked, because the server answers all three identically
 * and nothing here should imply otherwise. **Never a login form** — a client has no account, and
 * offering one would send them looking for a password that does not exist. Never a stack trace
 * either: the reader is not a developer and cannot act on one.
 */
export function failureMessage(status: number | undefined): string {
  if (status === 401) {
    return 'This link is no longer valid. It may have expired, or a newer link may have replaced it. Please ask whoever sent it to you for a new one.'
  }
  if (status === 429) {
    return 'Too many attempts. Please wait a minute and reload this page.'
  }
  if (status === 409) {
    return 'This draft is not waiting for your answer any more. Please contact whoever sent you this link.'
  }
  return 'We could not load your draft. Please try again in a moment, or contact whoever sent you this link.'
}

/**
 * The `sandbox` attribute for the profile frame.
 *
 * Empty on purpose, which is the strongest value: the frame runs no script, submits no form and
 * cannot navigate the page around it. The same value and the same reasoning as the staff panel's
 * preview — the server escapes every interpolated roster field, and this is the second layer.
 */
export const PROFILE_SANDBOX = ''

/**
 * Who may mint a client portal link.
 *
 * **Moved here from `case/redactionRules.ts` when Unit 13 was removed (2026-09-02).** It never
 * belonged to the redacted profile — it only shared a file with it, because both answered "who may
 * push something toward the client". That grouping outlived its usefulness the moment one half was
 * deleted, and the rule now sits with the portal it is about.
 *
 * Asserted in the test for the reason the old one was: a client offering a control the server
 * refuses is the failure this project keeps finding.
 */
export const MAY_MINT_PORTAL_LINK: readonly Role[] = [
  'GM',
  'BRAND_MANAGER',
  'PROJECT_MANAGER',
  'CASE_MANAGER',
]

export function mayMintPortalLink(role: Role): boolean {
  return MAY_MINT_PORTAL_LINK.includes(role)
}
