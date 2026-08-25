import { api, unwrap } from '../../lib/api'

/**
 * The counts the rail shows beside a screen's name.
 *
 * **A badge means "this many things here need you", not "this many exist."** Every count below is
 * a queue whose healthy value is low or zero, so a rising number is information. A count that only
 * grows — total cases, total experts — is decoration and does not belong here.
 */
export type NavBadges = {
  unassigned: number
  draftsAwaitingReview: number
  readyToDeliver: number
  docsAging: number
  myCasesCritical: number
}

/**
 * Which count sits beside which screen.
 *
 * **The mapping lives on the client on purpose.** Routes are `navigation.ts`'s, which is also the
 * router's allow-list; having the server key its response by path would put half that table on the
 * backend where it could drift from the half that stayed. The server sends named numbers and this
 * decides where they go.
 *
 * A path with no entry simply has no badge — that is the default, not an omission.
 */
export const BADGE_FOR_PATH: Readonly<Record<string, keyof NavBadges>> = {
  '/inbox': 'unassigned',
  '/drafts': 'draftsAwaitingReview',
  '/delivery': 'readyToDeliver',
  '/checklists': 'docsAging',
  '/my-cases': 'myCasesCritical',
}

/**
 * Whether a count is bad enough to draw in red rather than as a neutral chip.
 *
 * Only where zero is genuinely the target: an unassigned case and a case past its deadline are
 * both "should not exist". Drafts waiting for review and cases ready to deliver are ordinary
 * work in progress, and colouring them red would teach people to ignore the colour.
 */
export function isUrgentBadge(key: keyof NavBadges): boolean {
  return key === 'unassigned' || key === 'myCasesCritical'
}

export async function fetchNavBadges(signal?: AbortSignal): Promise<NavBadges> {
  return unwrap<NavBadges>(api.get('/metrics/nav', { signal }))
}
