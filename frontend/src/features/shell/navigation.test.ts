import { describe, expect, it } from 'vitest'
import type { Role } from '../../lib/session'
import { CASE_DETAIL_PATH, NAV_ITEMS, boardPathFor, homePathFor, itemFor, mayReach, navFor, navSectionsFor } from './navigation'

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
  'SALES',
  'MARKETING',
]

/**
 * Every role that works EvalOS's own cases — which is no longer all of them.
 *
 * **The split this comment was kept for has just earned itself.** Unit 29 separated these lists
 * because a role existed that was assigned no case; that role went, the lists became equal, and
 * the previous note said keeping them apart was what would make the next such role a one-line
 * change rather than a rewrite of six assertions. Unit 36 added two — `SALES` and `MARKETING`
 * act on GHL opportunities and are assigned no case — and this was indeed the one line.
 *
 * **Do not fold these back together.** Every assertion phrased "every role" needs to know which
 * kind of "every" it means, and the two answers have diverged for good: a case is born at
 * payment (invariant 8), by which point the deal has left the sales desk.
 */
const CASE_ROLES: readonly Role[] = [
  'GM',
  'BRAND_MANAGER',
  'PROJECT_MANAGER',
  'PROJECT_COORDINATOR',
  'CASE_MANAGER',
  'EXPERT_NETWORK_MANAGER',
]

describe('the nav and route table', () => {
  it('gives every case-working role a dashboard and at least one screen beyond it', () => {
    for (const role of CASE_ROLES) {
      const paths = navFor(role).map((item) => item.path)
      expect(paths, `${role} has no dashboard`).toContain('/dashboard')
      expect(paths.length, `${role} has only a dashboard`).toBeGreaterThan(1)
    }
  })

  it('sends every role somewhere it may actually go after signing in', () => {
    // The generalisation of the line above: no role's landing screen may be one it cannot reach.
    // Dashboard-first for everyone who has one, so this also pins that Unit 29 changed nobody
    // else's landing screen.
    for (const role of CASE_ROLES) {
      expect(homePathFor(role), `${role} lands somewhere else now`).toBe('/dashboard')
    }
    for (const role of ALL_ROLES) {
      const home = homePathFor(role)
      expect(mayReach(role, home) || home === '/dashboard', `${role} → ${home}`).toBe(true)
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
    for (const role of CASE_ROLES) {
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

  it('pins the checklist screen to who works it, and keeps delivery out of the nav', () => {
    // The case detail page's "Manage the checklist" link is gated on exactly this, after the
    // browser pass found it answering 403 for a Project Manager.
    //
    // **This is ChecklistController.COORDINATION minus the GM as of Unit 23**, and the gap is
    // deliberate rather than drift: the backend gate still carries `GM_OR`, so a GM who needs to
    // tick an item reaches it from the case. What they no longer get is a stage-level worklist in
    // their sidebar. If the GM is re-added here, say why — it is not a bug fix.
    expect(ALL_ROLES.filter((role) => mayReach(role, '/checklists'))).toEqual([
      'BRAND_MANAGER',
      'PROJECT_COORDINATOR',
    ])
    // The Project Manager stays out, even though they may call docs-complete.
    expect(mayReach('PROJECT_MANAGER', '/checklists')).toBe(false)
    expect(mayReach('GM', '/checklists'), 'nav-only narrowing, the backend gate is unchanged').toBe(
      false,
    )

    // `/delivery` is back as of Unit 22 slice 2, and this assertion flipped with the screen —
    // which is exactly the condition the previous version of it set. It used to demand the entry
    // be absent, because it was a label over a queue no unit built and it listed a role
    // (Brand Manager) its own backend gate refused.
    //
    // **Both problems are what this now guards against.** The roles must equal CaseController's
    // `deliver`/`close` gate and nothing wider, so re-adding the Brand Manager fails here.
    expect(ALL_ROLES.filter((role) => mayReach(role, '/delivery'))).toEqual([
      'GM',
      'PROJECT_COORDINATOR',
    ])
    expect(mayReach('BRAND_MANAGER', '/delivery'), 'the gate that was wrong before').toBe(false)
  })

  it("pins the PM's two queues to the transitions they drive, and nothing wider", () => {
    // Both screens act rather than only display: the inbox takes and staffs incoming cases
    // (`POST /api/cases/{id}/assign-pm`, `PATCH /api/cases/{id}/case-manager`) and the draft
    // queue approves and returns drafts (`POST .../draft/pm-approve` and `/pm-return`).
    //
    // **The inbox is the Project Manager's alone as of Unit 23.** It is the front door for
    // incoming work, and the PM is the person who opens it: a paid case lands in the pool, the PM
    // takes it, the PM staffs it. The GM keeps every underlying gate through `GM_OR` and keeps
    // `/drafts` — what left is a queue of somebody else's work in their sidebar.
    expect(ALL_ROLES.filter((role) => mayReach(role, '/inbox'))).toEqual(['PROJECT_MANAGER'])
    // `/drafts` is PM-only for a *stronger* reason than `/inbox`: Unit 23a removed `GM_OR` from
    // `draft/pm-approve` and `draft/pm-return` on the server, so this entry does match its gate
    // exactly. `boardRules.test.ts` pins the same exclusion on the buttons.
    expect(ALL_ROLES.filter((role) => mayReach(role, '/drafts'))).toEqual(['PROJECT_MANAGER'])

    // The Brand Manager is the tempting addition and the wrong one: they oversee the brand but
    // hold neither gate, so the screens would render buttons that answer 403 — the failure the
    // checklist entry above was fixed for.
    expect(mayReach('BRAND_MANAGER', '/inbox')).toBe(false)
    expect(mayReach('BRAND_MANAGER', '/drafts')).toBe(false)

    // Both CM screens are CM-only. They draw the same cases and are deliberately two entries:
    // "what did the PM ask for" is read once before drafting, "where did my work get to" is read
    // repeatedly after. The file's own `/cases`-beside-`/board` warning is about two entries for
    // one *screen*; these are two screens.
    expect(ALL_ROLES.filter((role) => mayReach(role, '/pm-notes'))).toEqual(['CASE_MANAGER'])

    // The CM's own drafting queue (Unit 32a). CM-only, and deliberately not the same screen as
    // `/drafts`: that one is the PM's work queue of other people's drafts, this is a status board
    // of your own. Both roles care about drafts; they are asking opposite questions.
    expect(ALL_ROLES.filter((role) => mayReach(role, '/my-drafts'))).toEqual(['CASE_MANAGER'])
    expect(mayReach('PROJECT_MANAGER', '/my-drafts')).toBe(false)

    // A Case Manager's drafts are on their own board; the review side is not theirs.
    expect(mayReach('CASE_MANAGER', '/drafts')).toBe(false)

    // The expert assignment board is the third PM queue and PM-only for the `/inbox` reason
    // rather than the `/drafts` one: the GM *does* hold both gates behind it
    // (`expert/timed-out` and `reassign-expert` are `GM_OR`), and is left out because staffing an
    // expert is the PM's day, not because the server would refuse them.
    expect(ALL_ROLES.filter((role) => mayReach(role, '/expert-assignment'))).toEqual(['PROJECT_MANAGER'])

    // The ENM is the tempting addition here: they own the roster this screen shows. They do not
    // staff cases — `ExpertShortlistController` and `CaseController.expertTimedOut` both exclude
    // them — so the two buttons on it would answer 403.
    expect(mayReach('EXPERT_NETWORK_MANAGER', '/expert-assignment')).toBe(false)
  })

  it('pins the expert database to its backend gate, as one entry rather than two', () => {
    // **This list must equal ExpertController.ROSTER_READ**, the gate on /api/experts/roster
    // and the routes beneath it. The Project Manager is on it because they pick experts; the
    // Case Manager and Coordinator are not, because they work the case the expert was chosen
    // for.
    expect(ALL_ROLES.filter((role) => mayReach(role, '/experts'))).toEqual([
      'GM',
      'BRAND_MANAGER',
      'PROJECT_MANAGER',
      'EXPERT_NETWORK_MANAGER',
    ])

    // `/expert-database` is gone: it was a second path to this same screen, given only to the
    // ENM, so which URL a role used for one page depended on their role. Asserted rather than
    // assumed, for the reason `/delivery` is above — re-adding it reinstates the split.
    expect(itemFor('/expert-database')).toBeUndefined()
    for (const role of ALL_ROLES) {
      expect(mayReach(role, '/expert-database'), `${role} can still reach /expert-database`).toBe(false)
    }
  })

  it('keeps every unattributable GHL screen GM-only, and states the one exception', () => {
    // **This one is not a taste call about who should see marketing.**
    // These screens read the one GHL sub-account named by `evalos.ghl.location-id` — a *global*
    // setting with no link to a brand — so their figures cannot be attributed to a brand, and
    // the endpoints accept no `brandId` because none would narrow anything.
    //
    // The Brand Manager is the tempting addition and is the leak. Each brand has its own GHL
    // sub-account, so the configured one is *some* brand's funnel and the server cannot prove
    // whose: a role locked to one brand could be shown another brand's numbers.
    //
    // **The list is derived, not typed out, and that is the change Unit 38 made.** It used to be
    // three hardcoded paths, with a comment warning that "a screen added without the same door is
    // the way this leaks next" — and then Unit 38 added a fourth screen over the same location
    // and this test passed, because the new path simply was not in the list. A guard that only
    // checks what somebody remembered to list is a guard against forgetting nothing. Items now
    // declare `readsGhlLocation`, and this walks them.
    // **The floor is 1, and the walk down from 4 is the history of this location's screens.**
    // 4 until 2026-09-14, when `/marketing/google-ads` went (its configured pipeline name matched
    // nothing in the sub-account IE moved to on 2026-09-11, so it could only ever 502). 3 until
    // 2026-09-16, when `/marketing/email` and `/sales/pipeline` went on the business's
    // instruction — they were GM-only for the scoping reason this test enforces, which meant the
    // audience for a marketing funnel could not open one, so they were removed rather than
    // widened. `/opportunities/board` is what is left, and it is the exception below.
    //
    // **Never lowered to 0.** The assertion's job is to catch the marker falling off the items,
    // and an empty list would pass while proving nothing — which is the exact failure it exists
    // to prevent. If the last GHL screen ever goes, delete this test rather than floor it at 0.
    const ghlScreens = NAV_ITEMS.filter((item) => item.readsGhlLocation)
    expect(ghlScreens.length, 'no GHL screens found — is the marker still on the items?')
      .toBeGreaterThanOrEqual(1)

    for (const item of ghlScreens) {
      const reachers = ALL_ROLES.filter((role) => mayReach(role, item.path))

      if (item.path === '/opportunities/board') {
        // **The one legitimate exception, stated rather than unnoticed.** Unit 36 added
        // `evalos.ghl.sales-brand`, which names the brand that owns the location, and bound
        // SALES/MARKETING to it with a 400 at assignment. So this screen's brand IS provable —
        // the exception narrowed rather than widened. Everything else on this location is still
        // unattributable and still GM-only.
        expect(reachers.sort(), item.path).toEqual(['GM', 'MARKETING', 'SALES'])
        continue
      }

      expect(reachers, item.path).toEqual(['GM'])
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
