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

export type NavGroup = 'Overview' | 'Pipeline' | 'Records' | 'Admin'

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

  // Case Manager. Their docket is the same board narrowed by their own assignment, which
  // the server does — so this is the board, not a second screen.
  {
    path: '/my-cases',
    label: 'My cases',
    roles: ['CASE_MANAGER'],
    becomes: 'Cases assigned to you',
    group: 'Pipeline',
  },

  // The Coordinator's two stages — the two ends of the pipeline. Each entry's role list is the
  // backend gate for that stage's routes and nothing wider. **The two gates are not the same**,
  // and one comment covering both while claiming they were is exactly what let the second one
  // drift: the Coordinator owns both stages, but oversight only reaches into one of them.
  {
    path: '/checklists',
    label: 'Doc checklists',
    // ChecklistController.COORDINATION. The GM and Brand Manager are here by decision, not by
    // drift: the GM is a superuser on every backend transition, so a screen driving one that
    // they cannot open is an inconsistency rather than a safeguard, and the Brand Manager has
    // the writes on this screen.
    roles: ['GM', 'BRAND_MANAGER', 'PROJECT_COORDINATOR'],
    becomes: 'Document checklist tracking',
    group: 'Pipeline',
  },
  {
    path: '/delivery',
    label: 'Delivery',
    // CaseController.deliver and .close are GM_OR + hasRole('PROJECT_COORDINATOR') — no Brand
    // Manager. They were listed here regardless until a review caught it. Nobody hit a 403
    // because this route still renders a placeholder, but a nav entry that outruns its own gate
    // is the failure this table exists to prevent, so it is narrowed to the gate rather than
    // left to become one the day the screen is built. Revisit the list then — and note that no
    // unit in the build plan builds it (see the open question in progress-tracker.md).
    roles: ['GM', 'PROJECT_COORDINATOR'],
    becomes: 'Final delivery queue (Unit 13)',
    group: 'Pipeline',
  },

  {
    path: '/experts',
    label: 'Experts',
    roles: ['GM', 'BRAND_MANAGER', 'PROJECT_MANAGER'],
    becomes: 'Expert database (Unit 11)',
    group: 'Records',
  },
  // Expert Network Manager.
  {
    path: '/expert-database',
    label: 'Expert database',
    roles: ['EXPERT_NETWORK_MANAGER'],
    becomes: 'Expert roster + sheet upload (Unit 11)',
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
