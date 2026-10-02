import { describe, expect, it } from 'vitest'
import { checklistTone, sentence } from './caseUi'

describe('checklistTone', () => {
  it('reads an unsent item as idle, whatever its status (D60)', () => {
    expect(checklistTone('APPROVED', false)).toBe('idle')
  })
  it('gives each sent status its meaning', () => {
    expect(['APPROVED', 'UPLOADED', 'MISSING', 'INCORRECT', 'REQUIRED'].map((s) => checklistTone(s, true))).toEqual([
      'done', 'active', 'blocked', 'blocked', 'pending',
    ])
  })
})

describe('sentence', () => {
  it('turns an enum into a sentence-case label', () => {
    expect(sentence('COURSE_BY_COURSE')).toBe('Course by course')
    expect(sentence('TIER_1')).toBe('Tier 1')
    expect(sentence('RFE_RESPONSE')).toBe('RFE response')
    expect(sentence('PERM')).toBe('PERM')
    expect(sentence(null)).toBe('—')
  })
})
