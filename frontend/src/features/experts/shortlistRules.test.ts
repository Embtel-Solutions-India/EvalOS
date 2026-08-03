import { describe, expect, it } from 'vitest'
import { FIELD_TAGS } from './expertRules'
import { breakdownAddsUp, factorShare, rankLabel, tagLabel, type ShortlistCard } from './shortlistRules'

/**
 * The shortlist panel's pure parts. Small on purpose — the ranking is the server's, and the only
 * arithmetic on this side is the bar width and the check that the rows agree with the total.
 */

const factor = (weight: number, earned: number) => ({ label: 'Field match', weight, earned, why: 'Primary field' })

const card = (score: number, factors: ShortlistCard['factors']): ShortlistCard => ({
  id: 'e1',
  fullName: 'Dr Ada Okoye',
  title: null,
  institution: null,
  tier: 'TIER_1',
  qualityScore: 9,
  score,
  factors,
  flags: [],
  activeLoad: 0,
})

describe('factorShare', () => {
  it('is the fraction of the weight that was earned', () => {
    expect(factorShare(factor(40, 40))).toBe(1)
    expect(factorShare(factor(40, 20))).toBe(0.5)
    expect(factorShare(factor(40, 0))).toBe(0)
  })

  it('never divides by zero, because a NaN width is a bar that silently disappears', () => {
    expect(factorShare(factor(0, 0))).toBe(0)
    expect(factorShare(factor(0, 10))).toBe(0)
  })

  it('clamps rather than overflowing its track, so a discrepancy shows in the number not the bar', () => {
    expect(factorShare(factor(15, 30))).toBe(1)
    expect(factorShare(factor(15, -5))).toBe(0)
  })
})

describe('breakdownAddsUp', () => {
  it('is true when the rows sum to the score shown above them', () => {
    expect(breakdownAddsUp(card(60, [factor(40, 40), factor(25, 20)]))).toBe(true)
  })

  it('is false when they do not, which is the whole reason the panel checks', () => {
    expect(breakdownAddsUp(card(85, [factor(40, 40), factor(25, 20)]))).toBe(false)
  })

  it('treats no factors as a zero score rather than throwing', () => {
    expect(breakdownAddsUp(card(0, []))).toBe(true)
    expect(breakdownAddsUp(card(7, []))).toBe(false)
  })
})

describe('the panel wording', () => {
  it('names the three positions and degrades past them', () => {
    expect([0, 1, 2].map(rankLabel)).toEqual(['Best match', 'Second', 'Third'])
    // The server sends at most three, but a label of `undefined` on screen is worse than a #4.
    expect(rankLabel(3)).toBe('#4')
  })

  it('spells a tag the way the server spells it in the empty state', () => {
    // The two strings are compared by the reader, not by code: the panel shows the tag it asked
    // for and the server's reason names the same tag, so they have to read alike.
    expect(tagLabel('MECHANICAL_ENGINEERING')).toBe('Mechanical Engineering')
    expect(tagLabel('LAW')).toBe('Law')
    expect(tagLabel('SUPPLY_CHAIN')).toBe('Supply Chain')
  })

  it('produces something readable for every tag in the vocabulary', () => {
    for (const tag of FIELD_TAGS) {
      expect(tagLabel(tag), tag).not.toContain('_')
      expect(tagLabel(tag)[0], tag).toMatch(/[A-Z]/)
    }
  })
})
