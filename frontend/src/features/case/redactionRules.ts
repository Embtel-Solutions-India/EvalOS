import type { Role } from '../../lib/session'
import type { CaseDetail } from './caseApi'

/**
 * The redacted-profile panel's wire shapes and the two judgements it makes locally.
 *
 * There is deliberately **no redaction here**. The document arrives fully rendered from
 * `service/RedactedProfileService`, whose whitelist is the only place that decides what may
 * appear — a second opinion in the browser would be a second answer to "is this anonymous",
 * and the one that loses is the one that leaked. What this file holds is who may press the
 * publish button and whether the paid gate has opened, both of which are display decisions
 * the server still enforces.
 */

/** `ExpertProfileController.ProfileView`. `html` is a whole document, for an iframe's `srcdoc`. */
export type ProfileView = { html: string; reference: string }

/** `ExpertProfileController.DriveWriteView`. */
export type DriveWriteView = { fileId: string; link: string; reference: string }

/**
 * Who may file the profile into the case's Drive folder.
 *
 * **Must equal `ExpertProfileController.toDrive`'s `@PreAuthorize`.** The Case Manager reads
 * both profiles and is absent here on purpose: they draft the letter and need to know who is
 * signing it, but putting an artefact in front of the client is the Project Manager's call.
 * That asymmetry is exactly the kind a client copy flattens by accident, which is why the
 * test asserts this list rather than trusting it.
 */
export const MAY_PUBLISH_TO_DRIVE: readonly Role[] = ['GM', 'BRAND_MANAGER', 'PROJECT_MANAGER']

export function mayPublishToDrive(role: Role): boolean {
  return MAY_PUBLISH_TO_DRIVE.includes(role)
}

/**
 * Who may mint the client's portal link (Unit 14).
 *
 * **Must equal `PortalLinkController.MAY_MINT`.** Wider than the Drive publish above by one role:
 * the Case Manager wrote the draft and is the person fielding "my link doesn't work", so they can
 * issue a new one. The Coordinator is deliberately absent even though they run
 * `draft/send-to-client` — Unit 18 dispatches the link on that event once GHL can carry it, so the
 * manual mint is a stopgap and not their workflow.
 *
 * Lives beside `mayPublishToDrive` because it is the same kind of rule: who may push something
 * toward the client. Asserted in the test for the reason that one is — a client offering a control
 * the server refuses is the failure this project keeps finding.
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

/**
 * Whether there is anything to generate.
 *
 * A case with no expert has no profile, and the server answers 409 saying so. The panel asks
 * first only to avoid offering a button whose sole outcome is an error — this is the one
 * precondition that is a plain fact on the case rather than a rule that could drift.
 */
export function hasExpert(detail: CaseDetail): boolean {
  return detail.summary.expertId !== null
}

/**
 * The paid gate on the full profile.
 *
 * **Unpaid says so rather than hiding the control**, the same reasoning as the checklist's
 * unpaid chip: a missing button is indistinguishable from a permission the reader does not
 * have, and a PM wondering why cannot act on absence. The server refuses with a 409 either
 * way — this only decides what the panel says before anyone presses it.
 */
export function fullProfileGate(detail: CaseDetail): { released: boolean; reason: string | null } {
  return detail.summary.paid
    ? { released: true, reason: null }
    : { released: false, reason: 'This case is not paid yet, so the expert’s identity is not released.' }
}

/**
 * Which of the panel's two operations failed. A read generates a document and changes nothing;
 * the Drive write puts a file in a folder the client can see.
 */
export type FailedOperation = 'read' | 'driveWrite'

/**
 * What to tell the reader after a failure, beyond the server's own message.
 *
 * **"Nothing was changed" is only true of the reads.** That phrase was introduced for read
 * failures (Unit 08's board panel) precisely because it is unconditionally true there — a failed
 * generate leaves nothing behind. Reusing it on the Drive write would state something the write
 * path cannot promise: the upload happens *before* the audit row, so an upload that succeeds and
 * an audit write that then fails leaves a real document in the client's folder while EvalOS
 * reports an error. `RedactedProfileService.writeRedactedToDrive` names that window as its
 * accepted residual risk, so the UI must not deny it.
 *
 * Held here rather than inline for the reason `boardRules.allInsideSla` exists: a display branch
 * that can be wrong is a display branch worth testing.
 */
export function failureReassurance(operation: FailedOperation): string {
  return operation === 'read'
    ? 'Nothing was changed.'
    : 'Check the folder before retrying — a failed write can still have left a document there.'
}

/**
 * The `sandbox` attribute for the preview frame.
 *
 * Empty on purpose, and that is the strongest value: it withholds every capability, so the
 * frame runs no script, submits no form, loads no plugin and cannot navigate the page it sits
 * in. `dangerouslySetInnerHTML` would have none of that. It is our own template, but a
 * template that interpolates roster fields is a template that interpolates whatever the
 * Expert Network Manager typed into them — the server escapes, and this is the second layer.
 */
export const PREVIEW_SANDBOX = ''
