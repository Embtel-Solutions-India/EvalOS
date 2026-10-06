import { afterEach, describe, expect, it, vi } from 'vitest'

// TanStack switches its timers off when it believes it is on a server (no `window` when it loads), so
// the stub has to exist before any import runs — hence `hoisted`.
vi.hoisted(() => {
  Object.assign(globalThis, { window: { addEventListener: () => undefined, removeEventListener: () => undefined } })
})

import { QueryClient, QueryObserver } from '@tanstack/react-query'
import { refreshOptions } from './useMetrics'

afterEach(() => vi.useRealTimers())

/** Runs a query for `ms` of fake time and says how many times it was read. */
async function reads(options: { refreshEvery?: number } | undefined, ms: number): Promise<number> {
  vi.useFakeTimers()
  const load = vi.fn(async () => 'board')
  const observer = new QueryObserver(new QueryClient(), { queryKey: ['t'], queryFn: load, ...refreshOptions(options) })
  const stop = observer.subscribe(() => undefined)
  await vi.advanceTimersByTimeAsync(ms)
  stop()
  return load.mock.calls.length
}

describe('refreshEvery', () => {
  it('re-reads on the timer: once at the start, then once a minute', async () => {
    expect(await reads({ refreshEvery: 60_000 }, 130_000)).toBe(3)
  })

  it('never polls when no interval is asked for, as for the GM overview that reads GHL live', async () => {
    expect(await reads(undefined, 130_000)).toBe(1)
  })

  it('does not poll a hidden tab', () => {
    expect(refreshOptions({ refreshEvery: 60_000 }).refetchIntervalInBackground).toBe(false)
  })
})
