import { describe, expect, it } from 'vitest'
import type { Role } from '../../lib/session'
import type { CaseDetail } from './caseApi'
import {
  failureReassurance,
  fullProfileGate,
  hasExpert,
  mayPublishToDrive,
  MAY_PUBLISH_TO_DRIVE,
  PREVIEW_SANDBOX,
} from './redactionRules'

/**
 * The panel's three local judgements. None of them redacts anything — the document arrives
 * rendered, and the whitelist that decides what may appear is asserted on the server, where a
 * failure is a leak rather than a cosmetic bug.
 *
 * What is worth pinning here is the publish role list (it must equal the backend's
 * `@PreAuthorize`, and the Case Manager's absence from it is an asymmetry easy to flatten) and
 * the sandbox value, which is load-bearing precisely because it is an empty string and so looks
 * like an oversight.
 */

const ALL_ROLES: readonly Role[] = [
  'GM',
  'BRAND_MANAGER',
  'PROJECT_MANAGER',
  'PROJECT_COORDINATOR',
  'CASE_MANAGER',
  'EXPERT_NETWORK_MANAGER',
]

function detail(overrides: { paid?: boolean; expertId?: string | null } = {}): CaseDetail {
  return {
    summary: {
      id: 'case-1',
      caseCode: 'IE-2026-0001',
      expertId: overrides.expertId === undefined ? 'expert-1' : overrides.expertId,
      paid: overrides.paid ?? true,
    },
  } as unknown as CaseDetail
}

describe('who may publish toward the client', () => {
  /**
   * Mirrors `ExpertProfileController.toDrive`. If the backend gate widens or narrows, this is
   * the test that has to be edited with it — which is the point of asserting the whole set
   * rather than spot-checking one role.
   */
  it('is the two commercial roles and the project manager, exactly', () => {
    expect([...MAY_PUBLISH_TO_DRIVE].sort()).toEqual(['BRAND_MANAGER', 'GM', 'PROJECT_MANAGER'])
  })

  /**
   * The asymmetry worth its own assertion: the Case Manager reads both profiles (the server
   * admits them) and publishes neither, because putting an artefact in front of the client is
   * the PM's call.
   */
  it('excludes the case manager, who reads the profile but does not publish it', () => {
    expect(mayPublishToDrive('CASE_MANAGER')).toBe(false)
  })

  it('excludes the coordinator and the expert network manager', () => {
    expect(mayPublishToDrive('PROJECT_COORDINATOR')).toBe(false)
    expect(mayPublishToDrive('EXPERT_NETWORK_MANAGER')).toBe(false)
  })

  it('admits nobody outside the declared list', () => {
    const admitted = ALL_ROLES.filter(mayPublishToDrive)
    expect(admitted).toHaveLength(MAY_PUBLISH_TO_DRIVE.length)
  })
})

describe('the paid gate on the full profile', () => {
  it('releases the identity on a paid case', () => {
    expect(fullProfileGate(detail({ paid: true }))).toEqual({ released: true, reason: null })
  })

  /**
   * Unpaid must produce a *reason*, not merely a false. The panel renders it, and the whole
   * point of the spec's "the control says the case is unpaid rather than being hidden" is that
   * the reader is told why — a boolean alone would let the control be silently disabled.
   */
  it('withholds it on an unpaid case and says why', () => {
    const gate = fullProfileGate(detail({ paid: false }))

    expect(gate.released).toBe(false)
    expect(gate.reason).toMatch(/not paid/i)
  })
})

describe('whether there is anything to generate', () => {
  it('needs an assigned expert', () => {
    expect(hasExpert(detail({ expertId: 'expert-1' }))).toBe(true)
    expect(hasExpert(detail({ expertId: null }))).toBe(false)
  })
})

describe('what a failure may promise', () => {
  /**
   * True unconditionally for a read: the profile is generated on demand, so a failed generate
   * leaves nothing behind.
   */
  it('tells a read failure that nothing changed', () => {
    expect(failureReassurance('read')).toBe('Nothing was changed.')
  })

  /**
   * The load-bearing half. The Drive write uploads BEFORE it writes its audit row, so an upload
   * that succeeds and an audit write that then fails leaves a real document in the client's
   * folder while EvalOS reports an error. Claiming "nothing was changed" there would deny a side
   * effect the server itself documents as its accepted residual risk.
   */
  it('never claims nothing changed after a failed Drive write', () => {
    expect(failureReassurance('driveWrite')).not.toMatch(/nothing was changed/i)
  })

  it('points a failed Drive write at the folder, which is the actionable thing', () => {
    expect(failureReassurance('driveWrite')).toMatch(/folder/i)
  })
})

describe('the preview sandbox', () => {
  /**
   * An empty `sandbox` withholds every capability, which is the strongest setting and the one
   * that looks most like a mistake. Asserted so nobody "fixes" it by adding
   * `allow-scripts` — the frame renders a template interpolating roster free text, and the
   * escaping on the server is the first layer, not the only one.
   */
  it('withholds every capability', () => {
    expect(PREVIEW_SANDBOX).toBe('')
  })
})
