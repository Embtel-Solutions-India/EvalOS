import { describe, expect, it, vi } from 'vitest'
import { QueryClient } from '@tanstack/react-query'
import type { ChatClient, LiveSignal } from '@evalos/chat'
import { attachLive, refreshes } from './live'

describe('refreshes', () => {
  const changed = (caseId: string): LiveSignal => ({ type: 'case.changed', caseId })

  it('re-reads that case and every list, never another case or the bell', () => {
    expect(refreshes([changed('c1')], ['case', 'c1', 'timeline'])).toBe(true)
    expect(refreshes([changed('c1')], ['board', null, null])).toBe(true)
    expect(refreshes([changed('c1')], ['nav-badges'])).toBe(true)
    expect(refreshes([changed('c1')], ['case', 'c2'])).toBe(false)
    expect(refreshes([changed('c1')], ['notifications', 'count'])).toBe(false)
  })

  it('re-reads the bell for its own signal, and everything after a reconnect', () => {
    expect(refreshes([{ type: 'notifications.changed' }], ['notifications', 'count'])).toBe(true)
    expect(refreshes([{ type: 'notifications.changed' }], ['board'])).toBe(false)
    expect(refreshes([{ type: 'reconnected' }], ['case', 'c9'])).toBe(true)
    expect(refreshes([{ type: 'reconnected' }], ['metrics', 'gm'])).toBe(false) // dashboards are focus-only (spec 70 §1.8)
    expect(refreshes([{ type: 'case.changed', caseId: 'c1' }], ['payouts', 'batch', null])).toBe(true)
  })
})

describe('attachLive', () => {
  it('gathers a burst of signals into one invalidation', () => {
    vi.useFakeTimers()
    let emit: (signal: LiveSignal) => void = () => {}
    const chat = { onLive: (listener: (signal: LiveSignal) => void) => ((emit = listener), () => {}) } as unknown as ChatClient
    const queries = new QueryClient()
    const invalidate = vi.spyOn(queries, 'invalidateQueries')

    attachLive(chat, queries)
    for (let i = 0; i < 10; i++) emit({ type: 'case.changed', caseId: 'c1' })
    expect(invalidate).not.toHaveBeenCalled()
    vi.advanceTimersByTime(500)

    expect(invalidate).toHaveBeenCalledTimes(1)
    vi.useRealTimers()
  })
})
