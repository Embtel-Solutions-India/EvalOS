import { describe, expect, it } from 'vitest'
import type { Role } from '../../lib/session'
import { CASE_DETAIL_PATH, NAV_ITEMS, itemFor, mayReach, navFor } from './navigation'

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
})
