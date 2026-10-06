import { describe, expect, it } from 'vitest'
import type { OpportunityBoard } from '../opportunities/opportunityApi'
import { countUndated, openDeals, staleDeals } from './dealAge'

const old = new Date(Date.now() - 30 * 24 * 60 * 60 * 1000).toISOString()

function board(statuses: Array<[string, string | null]>): OpportunityBoard {
  return {
    columns: [
      {
        stageId: 's',
        stageName: 'Stage',
        position: 0,
        total: 0,
        deals: statuses.map(([status, updatedAt], i) => ({
          opportunityId: String(i),
          name: 'd',
          contactId: 'c',
          status,
          amount: 1,
          updatedAt,
          source: null,
          service: null,
        })),
      },
    ],
    totalDeals: 0,
    totalValue: 0,
    lastSyncedAt: null,
    stale: false,
    syncConfigured: true,
  } as unknown as OpportunityBoard
}

describe('open deals', () => {
  it('leaves won and lost deals out, so a closed deal is not "no movement"', () => {
    const b = board([['open', old], ['won', old], ['lost', old], ['abandoned', old]])
    expect(openDeals(b)).toHaveLength(1)
    expect(staleDeals(b)).toHaveLength(1)
  })

  it('counts a deal with no status as open, and undated ones only among the open', () => {
    const b = board([['', null], ['won', null], ['open', null]])
    expect(openDeals(b)).toHaveLength(2)
    expect(countUndated(b)).toBe(2)
  })
})
