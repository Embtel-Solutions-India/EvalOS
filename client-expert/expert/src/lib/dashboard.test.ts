import { describe, expect, it } from 'vitest'
import {
  bucketOf,
  caseCounts,
  deltaPct,
  lastMonths,
  payoutSums,
  perMonth,
  previousRange,
  primaryCurrency,
  rangeOf,
  recentActivity,
} from './dashboard'
import type { ExpertCaseSummary, ExpertPayoutRow } from './expertCase'

const NOW = new Date(2026, 8, 15) // 15 Sep 2026, local

function kase(over: Partial<ExpertCaseSummary>): ExpertCaseSummary {
  return { caseId: 'c1', caseReference: 'IE-1', serviceType: null, signStatus: 'PENDING', step: null, actionRequired: false, offered: false, signedAt: null, offeredFee: null, currency: null, ...over }
}

function payout(over: Partial<ExpertPayoutRow>): ExpertPayoutRow {
  return { caseReference: 'IE-1', amount: 100, currency: 'USD', status: 'PENDING', settledOn: null, paymentId: null, dueDate: null, ...over }
}

describe('cases', () => {
  it('signedIsCompletedAnOpenOfferIsNotStartedAndReassignedCountsNowhere', () => {
    expect(bucketOf(kase({ signStatus: 'SIGNED' }))).toBe('COMPLETED')
    expect(bucketOf(kase({ signedAt: '2026-09-02T10:00:00Z' }))).toBe('COMPLETED')
    expect(bucketOf(kase({ offered: true }))).toBe('NOT_STARTED')
    expect(bucketOf(kase({}))).toBe('IN_PROGRESS')
    expect(bucketOf(kase({ signStatus: 'REASSIGNED' }))).toBeNull()
    expect(caseCounts([kase({ signStatus: 'SIGNED' }), kase({}), kase({ signStatus: 'REASSIGNED' })])).toEqual({
      COMPLETED: 1, IN_PROGRESS: 1, NOT_STARTED: 0, total: 2,
    })
  })
})

describe('payouts', () => {
  it('sumsOneCurrencyAndLeavesVoidedOut', () => {
    const rows = [
      payout({ status: 'CONFIRMED', amount: 350 }),
      payout({ status: 'PAID', amount: 50 }),
      payout({ status: 'PENDING', amount: 300 }),
      payout({ status: 'VOIDED', amount: 999 }),
      payout({ status: 'CONFIRMED', amount: 500, currency: 'EUR' }),
    ]
    expect(primaryCurrency(rows)).toBe('USD')
    const sums = payoutSums(rows, 'USD')
    expect(sums.total).toEqual({ amount: 700, count: 3 })
    expect(sums.PAID.amount).toBe(350)
    expect(sums.PROCESSING.amount).toBe(50)
    expect(sums.PENDING.amount).toBe(300)
  })

  it('aDeltaFromNothingIsNotAPercentage', () => {
    expect(deltaPct(12, 10)).toBe(20)
    expect(deltaPct(5, 0)).toBeNull()
  })
})

describe('time', () => {
  it('thePreviousRangeIsTheSameLengthJustBefore', () => {
    const month = rangeOf('this-month', NOW)
    expect(month.from).toEqual(new Date(2026, 8, 1))
    expect(previousRange(month)).toEqual({ from: new Date(2026, 7, 1), to: new Date(2026, 8, 1) })
    expect(previousRange(rangeOf('all', NOW))).toBeNull()
  })

  it('bucketsByMonthAndSkipsUndated', () => {
    const months = lastMonths(2, NOW)
    const rows = [
      payout({ amount: 10, settledOn: new Date(2026, 7, 20).toISOString() }),
      payout({ amount: 5, dueDate: new Date(2026, 8, 3).toISOString() }),
      payout({ amount: 7 }),
    ]
    expect(perMonth(rows, months, (r) => r.settledOn ?? r.dueDate, (r) => r.amount)).toEqual([10, 5])
  })

  it('recentActivityIsNewestFirstAndOnlyDated', () => {
    const activity = recentActivity(
      [kase({ caseId: 'a', signedAt: '2026-09-10T00:00:00Z' })],
      [payout({ status: 'CONFIRMED', settledOn: '2026-09-12T00:00:00Z' }), payout({ status: 'PENDING' })],
    )
    expect(activity.map((a) => a.label)).toEqual(['Payment received', 'Signed letter sent'])
  })
})
