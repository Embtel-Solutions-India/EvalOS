import { describe, expect, it } from 'vitest'
import { moveDeal } from './boardMove'
import type { BoardColumn, Deal } from './opportunityApi'

const deal = (id: string, amount: number | null): Deal => ({
  opportunityId: id, name: id, contactId: 'c', status: 'open', amount, updatedAt: null, source: null, service: null,
})
const column = (stageId: string, deals: Deal[]): BoardColumn => ({
  stageId, stageName: stageId, position: 0, deals, total: deals.reduce((t, d) => t + (d.amount ?? 0), 0),
})

describe('moveDeal', () => {
  const board = [column('a', [deal('1', 100), deal('2', null)]), column('b', [deal('3', 50)]), column('c', [])]

  it('moves one deal and keeps every untouched column and card by identity', () => {
    const next = moveDeal(board, '1', 'b')
    expect(next[0].deals.map((d) => d.opportunityId)).toEqual(['2'])
    expect(next[0].total).toBe(0)
    expect(next[1].deals.map((d) => d.opportunityId)).toEqual(['1', '3'])
    expect(next[1].total).toBe(150)
    expect(next[2]).toBe(board[2])
    expect(next[1].deals[0]).toBe(board[0].deals[0])
  })

  it('rolls back to where it came from', () => {
    const back = moveDeal(moveDeal(board, '1', 'b'), '1', 'a')
    expect(back[0].deals.map((d) => d.opportunityId).sort()).toEqual(['1', '2'])
    expect(back[1].total).toBe(50)
  })

  it('returns the same board when there is nothing to do', () => {
    expect(moveDeal(board, 'missing', 'b')).toBe(board)
    expect(moveDeal(board, '1', 'no-such-stage')).toBe(board)
    expect(moveDeal(board, '1', 'a', { name: '1', status: 'open', amount: 100 })).toBe(board)
  })

  it('applies the server answer in place when the stage already agrees', () => {
    const next = moveDeal(board, '1', 'a', { amount: 120 })
    expect(next[0].deals[0].amount).toBe(120)
    expect(next[0].total).toBe(120)
    expect(next[1]).toBe(board[1])
  })
})
