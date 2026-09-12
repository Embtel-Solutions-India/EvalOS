import { describe, expect, it } from 'vitest'
import {
  draftReady,
  failureMessage,
  mayAct,
  PROFILE_SANDBOX,
  statusMessage,
  tokenFromFragment,
  type ClientApprovalStatus,
  type ClientDraftView,
} from './portalRules'

/**
 * The client portal's local judgements. The reader here is not a colleague who can guess what a
 * screen meant, so what is worth pinning is the copy as much as the branching: a client shown
 * nothing, or shown a login form, has no way forward at all.
 */

function view(overrides: Partial<ClientDraftView> = {}): ClientDraftView {
  return {
    clientName: 'Anita Rao',
    serviceType: 'EXPERT_OPINION_LETTER',
    caseReference: 'IE-2026-0001',
    draftLink: 'https://docs.google.com/document/d/draft/edit',
    draftVersion: 2,
    approvalStatus: 'PENDING' as ClientApprovalStatus,
    awaitingAnswer: true,
    expertProfile: '<html>credentials</html>',
    expertReference: 'Expert AK',
    ...overrides,
  }
}

describe('the token in the fragment', () => {
  it('is read out of the hash', () => {
    expect(tokenFromFragment('#abc123')).toBe('abc123')
    expect(tokenFromFragment('abc123')).toBe('abc123')
  })

  /**
   * A bare `/portal/client` with nothing after it is somebody who lost the fragment — the page has
   * to say so rather than sending an empty header and rendering the server's 401 as if the link
   * were revoked.
   */
  it('is absent when the fragment is empty or whitespace', () => {
    expect(tokenFromFragment('')).toBeNull()
    expect(tokenFromFragment('#')).toBeNull()
    expect(tokenFromFragment('#   ')).toBeNull()
  })
})

describe('whether the client may act', () => {
  it('may when the server says the draft is waiting for them', () => {
    expect(mayAct(view())).toBe(true)
  })

  /**
   * The actions are gone after an answer, and the gone-ness comes from the server's own
   * `awaitingAnswer`, not from a status string this file interprets. Approving is irreversible and
   * commits the letter to an expert's signature — a page that still offered the button afterwards
   * invites a second press.
   */
  it('may not once they have answered', () => {
    expect(mayAct(view({ approvalStatus: 'APPROVED', awaitingAnswer: false }))).toBe(false)
    expect(mayAct(view({ approvalStatus: 'REVISION_REQUESTED', awaitingAnswer: false }))).toBe(false)
  })

  /**
   * The load-bearing one. Approving a document you were never shown is not a decision, so a case
   * whose draft link is missing offers nothing however the approval state reads.
   */
  it('may not when there is no draft to read, whatever the state says', () => {
    expect(mayAct(view({ draftLink: null }))).toBe(false)
    expect(draftReady(view({ draftLink: null }))).toBe(false)
  })
})

describe('what the page says', () => {
  it('asks for an answer while one is wanted', () => {
    expect(statusMessage(view())).toMatch(/approve it or tell us what needs to change/i)
  })

  /**
   * The post-action state has to say what happens NEXT. "Approved" alone leaves the client
   * wondering whether anything is coming.
   */
  it('says what happens next after an approval', () => {
    const message = statusMessage(view({ approvalStatus: 'APPROVED', awaitingAnswer: false }))

    expect(message).toMatch(/expert for signature/i)
    expect(message).toMatch(/signed letter/i)
  })

  it('says a revision request is in hand after one is sent', () => {
    expect(statusMessage(view({ approvalStatus: 'REVISION_REQUESTED', awaitingAnswer: false })))
      .toMatch(/new version/i)
  })

  /**
   * An unready draft is told the truth. It must not name a folder, a link, or anything else —
   * `drive_link` is never sent to this surface, and "not ready" is the whole honest answer.
   */
  it('tells an unready draft that it is not ready and offers nothing else', () => {
    const message = statusMessage(view({ draftLink: null }))

    expect(message).toMatch(/not ready/i)
    expect(message).toMatch(/be in touch/i)
  })
})

describe('what a broken link says', () => {
  /**
   * Unknown, expired and revoked reach the browser as one 401 because the server answers them
   * identically. The message must therefore not claim to know which — and must point at the person
   * who sent the link, because that is the only thing the reader can act on.
   */
  it('gives one message for a refused link, naming no cause with certainty', () => {
    const message = failureMessage(401)

    expect(message).toMatch(/no longer valid/i)
    expect(message).toMatch(/ask whoever sent it/i)
  })

  /**
   * Never a login form and never a stack trace. A client has no account, so offering one sends
   * them hunting for a password that does not exist.
   */
  it('never mentions signing in, on any failure', () => {
    for (const status of [401, 403, 404, 409, 429, 500, undefined]) {
      expect(failureMessage(status)).not.toMatch(/sign in|log in|password|account/i)
    }
  })

  it('distinguishes a rate limit, which is worth waiting out', () => {
    expect(failureMessage(429)).toMatch(/wait a minute/i)
  })

  it('has an answer for a status it has never seen', () => {
    expect(failureMessage(500)).toMatch(/contact whoever sent you this link/i)
    expect(failureMessage(undefined)).toMatch(/contact whoever sent you this link/i)
  })
})

describe('the profile frame', () => {
  /**
   * Empty withholds every capability, which is the strongest value and the one that looks most
   * like an oversight — asserted so nobody "fixes" it by adding `allow-scripts`. This frame renders
   * a template interpolating roster free text, on a page facing the open internet.
   */
  it('withholds every capability', () => {
    expect(PROFILE_SANDBOX).toBe('')
  })
})
