import type { Role } from '../../lib/session'

/**
 * The nav and the route allow-list are one table, not two.
 *
 * Keeping them separate is how a screen ends up reachable by deep link but absent from
 * the nav, or listed in the nav and then 403. Each entry declares its path, its label,
 * and exactly which roles may reach it; the nav filters this list and the router
 * guards against the same field.
 *
 * **This is convenience, not security.** The server enforces brand, role and ownership
 * on every call (architecture principle 7); hiding a nav item only saves the user a
 * pointless click.
 */
export type NavItem = {
  path: string
  label: string
  roles: readonly Role[]
  /** Shown under the label on the placeholder page, so it is clear what lands here. */
  becomes: string
  /**
   * The nav heading this item sits under. Grouping is by consecutive runs of this value,
   * so the order of {@link NAV_ITEMS} is the order on screen.
   */
  group: NavGroup
}

export type NavGroup = 'Overview' | 'Marketing' | 'Pipeline' | 'Records' | 'Admin'

const ALL_ROLES: readonly Role[] = [
  'GM',
  'BRAND_MANAGER',
  'PROJECT_MANAGER',
  'PROJECT_COORDINATOR',
  'CASE_MANAGER',
  'EXPERT_NETWORK_MANAGER',
]

export const NAV_ITEMS: readonly NavItem[] = [
  { path: '/dashboard', label: 'Dashboard', roles: ALL_ROLES, becomes: 'Role dashboard (Unit 17)', group: 'Overview' },

  // The one screen in EvalOS that reads GHL's front of house: the Google Ads sales funnel,
  // stage by stage, with the sources behind it.
  //
  // **GM only, and the reason is scoping rather than seniority.** `evalos.ghl.location-id` is a
  // single global setting with no link to a brand, so the figures behind this entry cannot be
  // attributed to one — the endpoint accepts no `brandId` because none would narrow anything.
  //
  // The first version of this note said the brands *share* one GHL sub-account, making the figure
  // "cross-brand by construction". That was wrong: each brand has its own sub-account, so this is
  // one brand's funnel and EvalOS cannot tell whose. **A Brand Manager is absent for the corrected
  // reason** — not because the number spans brands, but because it is unattributable, and showing
  // a single-brand role a figure that might be another brand's is the leak the scoping rule
  // exists to prevent. Unit 25 maps locations to brands; Unit 25a then re-scopes this entry.
  //
  // Sits above Pipeline rather than in it: this is the funnel *before* EvalOS takes custody,
  // and the Pipeline group is everything after. Its own group, because grouping is by
  // consecutive runs and marketing is not production work.
  {
    path: '/marketing/google-ads',
    label: 'Google Ads pipeline',
    roles: ['GM'],
    becomes: 'GHL Google Ads funnel by stage',
    group: 'Marketing',
  },

  // The second GHL funnel: the email marketing pipeline, in the same location and behind the
  // same door. GM-only for the identical reason — one global `location-id`, so the figures
  // cannot be attributed to a brand, let alone narrowed to one.
  //
  // Its own entry rather than a tab inside the Google Ads screen: they are separate funnels run
  // by separate people, and a nav entry is what makes the second one findable.
  {
    path: '/marketing/email',
    label: 'Email marketing',
    roles: ['GM'],
    becomes: 'GHL email marketing funnel by stage',
    group: 'Marketing',
  },

  // The production board. Four roles, one screen: the spec's per-role wording ("all
  // brands" / "own brand" / "team" / a Coordinator's read view) describes *scope*, which
  // the server applies — not four different boards.
  //
  // **There is no separate "Cases" screen and no unit builds one.** A `/cases` placeholder
  // used to sit above this labelled "Case table (Unit 08)", which is what Unit 08 shipped
  // *as this board* — so the one screen with live data was listed second, below a page that
  // could only ever say "not built yet". Two entries for one screen is how that happens.
  {
    path: '/board',
    label: 'Production board',
    roles: ['GM', 'BRAND_MANAGER', 'PROJECT_MANAGER', 'PROJECT_COORDINATOR'],
    becomes: 'Kanban production board',
    group: 'Pipeline',
  },

  // The PM's two working queues (Unit 22, slice 1). Both read `/api/cases/board` rather than
  // adding endpoints, so the scope they show is the board's scope and cannot drift from it.
  //
  // Roles come from what the screens actually *do*, not from who might like to look. The inbox
  // takes and staffs incoming cases and the draft queue approves and returns drafts — both
  // PM-gated on the server (`CaseController`). A Brand Manager is deliberately absent: they hold
  // neither gate, so the screens would render buttons that answer 403.
  //
  // **The GM is absent from the inbox as of Unit 23, and that is a nav change only.** The inbox
  // is the front door for incoming work and the Project Manager is the person who opens it: a
  // paid case lands in the pool, the PM takes it, and the PM staffs the coordinator and the case
  // manager. `GM_OR` still prefixes every gate those buttons drive, so the GM can unblock any
  // one of them from the board or the case page — what they no longer have is a queue of
  // somebody else's work in their sidebar.
  {
    path: '/inbox',
    label: 'Cases inbox',
    roles: ['PROJECT_MANAGER'],
    becomes: 'Incoming and at-risk cases',
    group: 'Pipeline',
  },
  // PM-only, and unlike `/inbox` above this one matches its backend gate exactly: Unit 23a
  // removed `GM_OR` from `draft/pm-approve` and `draft/pm-return` outright. Reviewing a Case
  // Manager's draft is the judgement of the PM who assigned it; a superuser override around the
  // reviewer is a second reviewer, not oversight.
  {
    path: '/drafts',
    label: 'Draft review',
    roles: ['PROJECT_MANAGER'],
    becomes: 'Drafts awaiting your review',
    group: 'Pipeline',
  },

  // Case Manager. Their docket is the same board narrowed by their own assignment, which
  // the server does — so this is the board, not a second screen.
  {
    path: '/my-cases',
    label: 'My cases',
    roles: ['CASE_MANAGER'],
    becomes: 'Cases assigned to you',
    group: 'Pipeline',
  },

  // The delivery queue (Unit 22, slice 2) — the /delivery entry the tracker's G3 asked for, now
  // with a screen behind it. Roles are CaseController's `deliver` and `close` gates exactly:
  // GM_OR PROJECT_COORDINATOR. The Brand Manager is absent, which is the correction the previous
  // /delivery entry needed — it listed a role its own backend gate refused for a whole unit.
  {
    path: '/delivery',
    label: 'Delivery queue',
    roles: ['GM', 'PROJECT_COORDINATOR'],
    becomes: 'Cases ready to send',
    group: 'Pipeline',
  },

  // The Coordinator's document-collection stage. The role list is the backend gate for that
  // stage's routes and nothing wider.
  //
  // This used to carry a long note explaining why there was deliberately no `/delivery` entry
  // beside it: the old one was a label over a placeholder no unit built, and it listed a role its
  // own gate refused. That note is gone because the condition it set has been met — `/delivery`
  // is back above, with a screen behind it and roles taken from the transitions it drives. The
  // rule it was really stating still holds: **add the entry with the screen, never ahead of it.**
  {
    path: '/checklists',
    label: 'Doc checklists',
    // ChecklistController.COORDINATION, minus the GM as of Unit 23 — the same nav-only narrowing
    // the inbox above takes, for the same reason. Chasing a client for a transcript is the
    // Coordinator's day and the Brand Manager's oversight; it was never the GM's, and the entry
    // put a stage-level worklist in the sidebar of the one role that reads the business.
    //
    // The backend gate keeps `GM_OR`, so this is a listing decision and not a capability one: a
    // GM who needs to tick an item off can still reach it from the case.
    roles: ['BRAND_MANAGER', 'PROJECT_COORDINATOR'],
    becomes: 'Document checklist tracking',
    group: 'Pipeline',
  },

  // The expert database, live as of Unit 11: roster, availability board, sheet upload.
  //
  // **One entry, not two.** There used to be a `/experts` for the GM/BM/PM beside an
  // `/expert-database` for the Expert Network Manager — two paths, two labels, one screen,
  // which is exactly what `/cases` next to `/board` was before the visual pass deleted it.
  // Whichever of the two a role happened to be given decided which URL their bookmarks and
  // their deep links used, for the same page.
  //
  // Roles are `ExpertController.ROSTER_READ`: the ENM maintains the roster, the GM and Brand
  // Manager oversee it, and the Project Manager reads it because they pick the experts on it
  // — the assignment picker only ever showed them a name. A Case Manager and a Coordinator
  // are absent, matching that gate: they work the case the expert was chosen for.
  {
    path: '/experts',
    label: 'Expert database',
    roles: ['GM', 'BRAND_MANAGER', 'PROJECT_MANAGER', 'EXPERT_NETWORK_MANAGER'],
    becomes: 'Expert roster + sheet upload',
    group: 'Records',
  },
  {
    path: '/payouts',
    label: 'Payouts',
    roles: ['GM', 'BRAND_MANAGER', 'EXPERT_NETWORK_MANAGER'],
    becomes: 'Payout ledger (Unit 16)',
    group: 'Records',
  },

  { path: '/brands', label: 'Brands', roles: ['GM'], becomes: 'Brand administration', group: 'Admin' },
]

/**
 * Routes that are reachable but not listed, because they need a parameter.
 *
 * `/cases/:id` has no nav entry — you arrive from a board card — but it still needs a role
 * allow-list, and it needs to live in the same table for the reason the header comment gives:
 * a screen whose gate is declared somewhere else is how one ends up deep-linkable but
 * unguarded. Every staff role can open a case; **which** cases is the server's scope, which is
 * why the list here is every role rather than a subset.
 */
export const CASE_DETAIL_PATH = '/cases/:id'

const PARAMETERIZED: readonly NavItem[] = [
  {
    path: CASE_DETAIL_PATH,
    label: 'Case',
    roles: ALL_ROLES,
    becomes: 'Case detail',
    group: 'Pipeline',
  },
]

export function navFor(role: Role): readonly NavItem[] {
  return NAV_ITEMS.filter((item) => item.roles.includes(role))
}

/**
 * This role's nav as headed sections, in table order.
 *
 * Built from consecutive runs rather than by filtering per group, so the table's order is
 * the screen's order and a heading can never appear twice.
 */
export function navSectionsFor(role: Role): readonly { group: NavGroup; items: readonly NavItem[] }[] {
  const sections: { group: NavGroup; items: NavItem[] }[] = []
  for (const item of navFor(role)) {
    const last = sections.at(-1)
    if (last?.group === item.group) last.items.push(item)
    else sections.push({ group: item.group, items: [item] })
  }
  return sections
}

/**
 * The live screen to send this role to when the one they asked for is not built.
 *
 * The board under its two names, then the dashboard — checked through {@link mayReach} so a
 * role that can reach neither (the Expert Network Manager today) is never offered a link
 * that answers 403.
 */
export function boardPathFor(role: Role): { path: string; label: string } {
  for (const path of ['/board', '/my-cases']) {
    const item = itemFor(path)
    if (item && mayReach(role, path)) return { path, label: `Go to ${item.label.toLowerCase()}` }
  }
  return { path: '/dashboard', label: 'Back to your dashboard' }
}

export function itemFor(path: string): NavItem | undefined {
  return NAV_ITEMS.find((item) => item.path === path)
}

/**
 * The router's half of the table, over listed and parameterized routes alike.
 *
 * Unknown paths return false, so a route added without an entry is refused rather than open —
 * the safe direction, and the reason this stays a lookup instead of a default-allow.
 */
export function mayReach(role: Role, path: string): boolean {
  const item = itemFor(path) ?? PARAMETERIZED.find((candidate) => candidate.path === path)
  return item?.roles.includes(role) ?? false
}
