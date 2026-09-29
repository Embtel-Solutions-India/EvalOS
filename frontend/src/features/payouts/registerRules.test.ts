import { describe, expect, it } from 'vitest'
import { REGISTER_STATUS_LABEL, describeChange, mayEditFee, registerParams } from './registerRules'

describe('REGISTER_STATUS_LABEL', () => {
  it('uses the business words (the server already mapped stored PAID / CONFIRMED)', () => {
    expect(REGISTER_STATUS_LABEL.PROCESSING).toBe('Processing')
    expect(REGISTER_STATUS_LABEL.PAID).toBe('Paid')
    expect(REGISTER_STATUS_LABEL.TIMED_OUT).toBe('Timed out')
  })
})

describe('describeChange', () => {
  it('shows old → new for a changed key', () => {
    expect(describeChange('{"fee":350.00}', '{"fee":400.00}')).toBe('fee: 350 → 400')
  })
  it('shows only the new value when there was no before', () => {
    expect(describeChange(null, '{"fee":350}')).toBe('fee: 350')
  })
  it('lowercases and spaces an outcome', () => {
    expect(describeChange('{"outcome":"OFFERED"}', '{"outcome":"TIMED_OUT","fee":350}'))
      .toBe('outcome: offered → timed out · fee: 350')
  })
  it('leaves out ids nobody reads', () => {
    expect(describeChange(null, '{"caseId":"a1","expertId":"b2","fee":350}')).toBe('fee: 350')
  })
  it('never throws on a snapshot that is not JSON', () => {
    expect(describeChange('oops', null)).toBe('')
  })
})

describe('registerParams', () => {
  it('drops empty filters so the URL stays clean', () => {
    expect(registerParams({ status: 'PENDING', q: '  ' })).toEqual({ status: 'PENDING' })
  })
})

describe('mayEditFee', () => {
  it('lets a fee setter edit only while the offer is open', () => {
    expect(mayEditFee('PROJECT_COORDINATOR', 'OFFERED')).toBe(true)
    expect(mayEditFee('EXPERT_NETWORK_MANAGER', 'ACCEPTED')).toBe(false)
  })
  it('never lets a case manager or brand manager edit', () => {
    expect(mayEditFee('CASE_MANAGER', 'OFFERED')).toBe(false)
    expect(mayEditFee('BRAND_MANAGER', 'OFFERED')).toBe(false)
  })
})
