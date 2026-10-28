import { describe, expect, it } from 'vitest'
import { mondayOf, settleBlocker, sumSelected, weekLabel } from './payoutRules'
import type { LedgerRow } from './payoutRules'

const draft = (id: string, amount: number | null, over: Partial<LedgerRow> = {}): LedgerRow => ({
  id,
  caseId: `case-${id}`,
  caseCode: `IE-00${id}`,
  expertId: 'expert-1',
  expertName: 'Dr. Smith',
  amount,
  currency: 'USD',
  status: 'PENDING',
  dueDate: '2026-09-02T00:00:00Z',
  overdue: false,
  paymentId: null,
  ...over,
})

describe('sumSelected', () => {
  it('adds only what is ticked', () => {
    const drafts = [draft('1', 350), draft('2', 350), draft('3', 400)]
    expect(sumSelected(drafts, new Set(['1', '3']))).toBe(750)
  })

  it('is zero when nothing is ticked', () => {
    expect(sumSelected([draft('1', 350)], new Set())).toBe(0)
  })

  it('adds in cents so the total is not a floating-point near-miss', () => {
    // 0.1 + 0.2 is 0.30000000000000004 as floats. The server compares the posted amount
    // against its own BigDecimal sum exactly, so a near-miss here is a settlement the user
    // cannot complete and cannot fix from the screen.
    expect(sumSelected([draft('1', 0.1), draft('2', 0.2)], new Set(['1', '2']))).toBe(0.3)
  })

  it('treats an undecided amount as zero rather than NaN', () => {
    expect(sumSelected([draft('1', null), draft('2', 350)], new Set(['1', '2']))).toBe(350)
  })
})

describe('settleBlocker', () => {
  it('passes a clean selection', () => {
    expect(settleBlocker([draft('1', 350)], new Set(['1']))).toBeNull()
  })

  it('blocks an empty selection', () => {
    expect(settleBlocker([draft('1', 350)], new Set())).toMatch(/at least one/i)
  })

  it('blocks a draft with no amount, naming it', () => {
    expect(settleBlocker([draft('1', null)], new Set(['1']))).toMatch(/IE-001/)
  })

  it('blocks a draft that is already settled', () => {
    const settled = draft('1', 350, { status: 'PAID' })
    expect(settleBlocker([settled], new Set(['1']))).toMatch(/already paid/i)
  })

  it('blocks a selection spanning two experts', () => {
    const mine = draft('1', 350)
    const theirs = draft('2', 350, { expertId: 'expert-2' })
    expect(settleBlocker([mine, theirs], new Set(['1', '2']))).toMatch(/one expert/i)
  })

  it('blocks a selection spanning two currencies', () => {
    const usd = draft('1', 350)
    const gbp = draft('2', 350, { currency: 'GBP' })
    expect(settleBlocker([usd, gbp], new Set(['1', '2']))).toMatch(/different currencies/i)
  })

  it('ignores drafts that are not ticked', () => {
    // The unticked row is unsettleable in three ways at once; none of them may block a
    // selection that does not include it.
    const bad = draft('2', null, { status: 'VOIDED', expertId: 'expert-9' })
    expect(settleBlocker([draft('1', 350), bad], new Set(['1']))).toBeNull()
  })
})

describe('weekLabel', () => {
  it('reads as a range a person would say out loud', () => {
    expect(weekLabel('2026-08-24', '2026-08-30')).toBe('Aug 24 – Aug 30, 2026')
  })

  it('spans a month boundary', () => {
    expect(weekLabel('2026-08-31', '2026-09-06')).toBe('Aug 31 – Sep 6, 2026')
  })
})

describe('mondayOf', () => {
  it('leaves a Monday alone', () => {
    expect(mondayOf('2026-08-24')).toBe('2026-08-24')
  })

  it('counts a Sunday back to the Monday that started its week, not forward', () => {
    // The off-by-one that matters: a naive `day - getUTCDay()` sends Sunday to the Monday
    // six days ahead, putting a Sunday delivery in next week's batch.
    expect(mondayOf('2026-08-30')).toBe('2026-08-24')
  })

  it('walks back across a month boundary', () => {
    expect(mondayOf('2026-09-02')).toBe('2026-08-31')
  })
})
