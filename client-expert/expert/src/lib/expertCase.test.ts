import { describe, expect, it } from 'vitest'
import {
  expertFailureMessage,
  goalOf,
  humanize,
  SIGN_SLA,
  SIGN_STATUS,
  stateOf,
  type ExpertCaseView,
} from './expertCase'

/**
 * The pure rules the expert's screen makes, and the one property worth guarding hardest:
 * **this app decides nothing about the lifecycle.** Every state below is read off a boolean the
 * server sent, in the order the server's own guards apply.
 */

function view(overrides: Partial<ExpertCaseView> = {}): ExpertCaseView {
  return {
    caseReference: 'IE-2026-0044',
    applicantName: 'Priya Menon',
    expertName: 'Dr Ada Lovelace',
    serviceType: 'EXPERT_OPINION_LETTER',
    visaCategory: 'EB1A',
    draftLink: 'https://docs.example.test/draft',
    evidence: ['Passport'],
    signStatus: 'PENDING',
    signSla: 'ON_TRACK',
    awaitingAnswer: true,
    onHold: false,
    signed: false,
    signedAt: null,
    attestation: 'I, Dr Ada Lovelace, confirm this is my signature on this letter.',
    ...overrides,
  }
}

describe('stateOf', () => {
  it('reads the answers as live when the server says they are', () => {
    expect(stateOf(view())).toBe('OPEN')
  })

  /**
   * The precedence matters: a signed case is finished even if some other flag disagrees, because
   * the signature is the fact everything else is about.
   */
  it('puts signed ahead of every other state', () => {
    expect(stateOf(view({ signed: true, onHold: true, awaitingAnswer: true }))).toBe('SIGNED')
  })

  /** Held for evidence: waiting on the client, and the expert cannot sign until it resumes. */
  it('reports a held case as waiting on the client', () => {
    expect(stateOf(view({ onHold: true, awaitingAnswer: false }))).toBe('ON_HOLD')
  })

  it('offers nothing to act on when the server says the case is no longer awaiting an answer', () => {
    expect(stateOf(view({ awaitingAnswer: false }))).toBe('CLOSED')
  })
})

describe('the label tables', () => {
  /**
   * The server-owned vocabularies, spelled once. A fifth `ExpertSignStatus` or a fourth
   * `SlaStatus` in EvalOS fails this test rather than rendering an empty badge on an expert's
   * screen — which is the whole reason a table is acceptable here at all.
   */
  it('covers every value EvalOS can send', () => {
    expect(Object.keys(SIGN_STATUS).sort()).toEqual(['OVERDUE', 'PENDING', 'REASSIGNED', 'SIGNED'])
    expect(Object.keys(SIGN_SLA).sort()).toEqual(['AT_RISK', 'ON_TRACK', 'OVERDUE'])
  })

  it('spells an overdue signature as something an expert can act on', () => {
    expect(SIGN_STATUS.OVERDUE.variant).toBe('destructive')
    expect(SIGN_SLA.AT_RISK.label).toBe('Due soon')
  })
})

describe('humanize and goalOf', () => {
  it('prettifies a constant without holding a table of them', () => {
    expect(humanize('EXPERT_OPINION_LETTER')).toBe('Expert Opinion Letter')
    expect(humanize(null)).toBe('')
  })

  it('leads with the goal the letter has to achieve', () => {
    expect(goalOf(view())).toBe('Eb1a — Expert Opinion Letter')
  })

  /** A case whose type is not set yet still needs a heading rather than an empty one. */
  it('falls back to a plain description when the server sent neither', () => {
    expect(goalOf(view({ serviceType: null, visaCategory: null }))).toBe('Expert opinion letter')
  })
})

describe('expertFailureMessage', () => {
  /**
   * 409 is the one that matters: the case moved under them. Telling an expert to retry would be
   * telling them to do something that cannot work.
   */
  it('explains a case that moved rather than suggesting a retry', () => {
    expect(expertFailureMessage(409)).toContain('moved on')
    expect(expertFailureMessage(409)).not.toContain('try again')
  })

  it('sends a dead link back to the case manager, never to a login form', () => {
    const message = expertFailureMessage(401)
    expect(message).toContain('case manager')
    expect(message.toLowerCase()).not.toContain('password')
  })

  it('says nothing was recorded when the store is down, because nothing was', () => {
    expect(expertFailureMessage(503)).toContain('nothing was recorded')
  })
})
