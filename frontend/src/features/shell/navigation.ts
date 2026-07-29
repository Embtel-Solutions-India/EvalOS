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
}

const ALL_ROLES: readonly Role[] = [
  'GM',
  'BRAND_MANAGER',
  'PROJECT_MANAGER',
  'PROJECT_COORDINATOR',
  'CASE_MANAGER',
  'EXPERT_NETWORK_MANAGER',
]

export const NAV_ITEMS: readonly NavItem[] = [
  { path: '/dashboard', label: 'Dashboard', roles: ALL_ROLES, becomes: 'Role dashboard (Unit 17)' },

  // GM + Brand Manager: the commercial view.
  {
    path: '/cases',
    label: 'Cases',
    roles: ['GM', 'BRAND_MANAGER', 'PROJECT_MANAGER', 'PROJECT_COORDINATOR'],
    becomes: 'Case table (Unit 08)',
  },
  {
    path: '/experts',
    label: 'Experts',
    roles: ['GM', 'BRAND_MANAGER', 'PROJECT_MANAGER'],
    becomes: 'Expert database (Unit 11)',
  },
  {
    path: '/payouts',
    label: 'Payouts',
    roles: ['GM', 'BRAND_MANAGER', 'EXPERT_NETWORK_MANAGER'],
    becomes: 'Payout ledger (Unit 16)',
  },
  { path: '/brands', label: 'Brands', roles: ['GM'], becomes: 'Brand administration' },

  // The production board. Four roles, one screen: the spec's per-role wording ("all
  // brands" / "own brand" / "team" / a Coordinator's read view) describes *scope*, which
  // the server applies — not four different boards.
  {
    path: '/board',
    label: 'Board',
    roles: ['GM', 'BRAND_MANAGER', 'PROJECT_MANAGER', 'PROJECT_COORDINATOR'],
    becomes: 'Kanban production board',
  },

  // Project Coordinator.
  {
    path: '/checklists',
    label: 'Doc Checklists',
    roles: ['PROJECT_COORDINATOR'],
    becomes: 'Document checklist tracking (Unit 10)',
  },
  { path: '/delivery', label: 'Delivery', roles: ['PROJECT_COORDINATOR'], becomes: 'Final delivery queue (Unit 13)' },

  // Case Manager. Their docket is the same board narrowed by their own assignment, which
  // the server does — so this is the board, not a second screen.
  { path: '/my-cases', label: 'My Cases', roles: ['CASE_MANAGER'], becomes: 'Cases assigned to you' },

  // Expert Network Manager.
  {
    path: '/expert-database',
    label: 'Expert Database',
    roles: ['EXPERT_NETWORK_MANAGER'],
    becomes: 'Expert roster + sheet upload (Unit 11)',
  },
]

export function navFor(role: Role): readonly NavItem[] {
  return NAV_ITEMS.filter((item) => item.roles.includes(role))
}

export function itemFor(path: string): NavItem | undefined {
  return NAV_ITEMS.find((item) => item.path === path)
}

export function mayReach(role: Role, path: string): boolean {
  return itemFor(path)?.roles.includes(role) ?? false
}
