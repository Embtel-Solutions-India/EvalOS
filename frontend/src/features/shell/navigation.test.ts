import { describe, expect, it } from 'vitest'
import type { Role } from '../../lib/session'
import { CASE_DETAIL_PATH, NAV_ITEMS, boardPathFor, itemFor, mayReach, navFor, navSectionsFor } from './navigation'

/**
 * The nav table is also the route allow-list, so a mistake here is a screen that is either
 * unreachable or reachable by the wrong role. The server enforces the real thing, but a UI
 * that offers a 403 is still a bug.
 */

const ALL_ROLES: readonly Role[] = [
  'GM',
  'BRAND_MANAGER',
  'PROJECT_MANAGER',
  'PROJECT_COORDINATOR',
  'CASE_MANAGER',
  'EXPERT_NETWORK_MANAGER',
]

describe('the nav and route table', () => {
  it('gives every role a dashboard and at least one screen beyond it', () => {
    for (const role of ALL_ROLES) {
      const paths = navFor(role).map((item) => item.path)
      expect(paths, `${role} has no dashboard`).toContain('/dashboard')
      expect(paths.length, `${role} has only a dashboard`).toBeGreaterThan(1)
    }
  })

  it('keeps the nav and the allow-list consistent, in both directions', () => {
    // Listed but unreachable, or reachable but unlisted, are the two failures the single
    // table exists to prevent — so assert the equivalence rather than trusting it.
    for (const role of ALL_ROLES) {
      const listed = navFor(role).map((item) => item.path)
      for (const item of NAV_ITEMS) {
        expect(mayReach(role, item.path), `${role} / ${item.path}`).toBe(listed.includes(item.path))
      }
    }
  })

  it('refuses a path that has no entry at all', () => {
    // Fail closed: a route added without a table entry must not default to open.
    for (const role of ALL_ROLES) {
      expect(mayReach(role, '/not-a-screen')).toBe(false)
      expect(mayReach(role, '')).toBe(false)
    }
  })

  it('guards the parameterized case route even though it has no nav entry', () => {
    // Reached from a board card, so it is absent from every nav — but still gated. Every role
    // may open a case; which cases is the server's scope, not this table's.
    expect(itemFor(CASE_DETAIL_PATH)).toBeUndefined()
    for (const role of ALL_ROLES) {
      expect(mayReach(role, CASE_DETAIL_PATH), `${role} cannot open a case`).toBe(true)
    }
    // And it is genuinely not in the nav, or it would render as a broken link.
    expect(NAV_ITEMS.map((item) => item.path)).not.toContain(CASE_DETAIL_PATH)
  })

  it('declares a label and a destination for every listed screen', () => {
    for (const item of NAV_ITEMS) {
      expect(item.path.startsWith('/'), `${item.path} is not absolute`).toBe(true)
      expect(item.label.length, `${item.path} has no label`).toBeGreaterThan(0)
      expect(item.becomes.length, `${item.path} has no destination note`).toBeGreaterThan(0)
      expect(item.roles.length, `${item.path} is reachable by nobody`).toBeGreaterThan(0)
    }
  })

  it('lists no path twice, so one entry is always the answer', () => {
    const paths = NAV_ITEMS.map((item) => item.path)
    expect(new Set(paths).size).toBe(paths.length)
  })

  it('groups every listed item exactly once, in table order', () => {
    // Grouping is by consecutive runs, so an item filed out of order would split its heading
    // in two — which is the failure this asserts against, not just the item count.
    for (const role of ALL_ROLES) {
      const sections = navSectionsFor(role)
      const headings = sections.map((section) => section.group)
      expect(new Set(headings).size, `${role} has a repeated heading`).toBe(headings.length)
      expect(sections.flatMap((section) => section.items.map((item) => item.path))).toEqual(
        navFor(role).map((item) => item.path),
      )
    }
  })

  it('pins the checklist screen to its backend gate, and keeps delivery out of the nav', () => {
    // The case detail page's "Manage the checklist" link is gated on exactly this, after the
    // browser pass found it answering 403 for a Project Manager — and, then, for the GM.
    // **This list must equal ChecklistController.COORDINATION**, the gate on
    // /api/checklists/board and the three writes under it.
    expect(ALL_ROLES.filter((role) => mayReach(role, '/checklists'))).toEqual([
      'GM',
      'BRAND_MANAGER',
      'PROJECT_COORDINATOR',
    ])
    // The Project Manager stays out, even though they may call docs-complete.
    expect(mayReach('PROJECT_MANAGER', '/checklists')).toBe(false)

    // `/delivery` is gone, and its absence is asserted rather than assumed. It promised a queue
    // no unit in the build plan builds, while `deliver` and `close` are Unit 04 transitions the
    // Coordinator already drives from the board — and for a whole unit its role list claimed a
    // backend gate it did not have. Re-adding it without a screen behind it reinstates both
    // problems, so fail here if anybody does.
    expect(itemFor('/delivery')).toBeUndefined()
    for (const role of ALL_ROLES) {
      expect(mayReach(role, '/delivery'), `${role} can still reach /delivery`).toBe(false)
    }
  })

  it('never offers a role a way out it would be refused', () => {
    // The placeholder's escape link. A link that answers 403 is worse than no link.
    for (const role of ALL_ROLES) {
      const way = boardPathFor(role)
      expect(mayReach(role, way.path) || way.path === '/dashboard', `${role} → ${way.path}`).toBe(true)
    }
    expect(boardPathFor('CASE_MANAGER').path).toBe('/my-cases')
    // No board of their own today, so they get their dashboard rather than somebody else's board.
    expect(boardPathFor('EXPERT_NETWORK_MANAGER').path).toBe('/dashboard')
  })
})
