import { describe, expect, it, vi } from 'vitest'

const get = vi.fn()
vi.mock('../../lib/api', () => ({
  api: { get: (...args: unknown[]) => get(...args) },
  unwrap: async (call: Promise<{ data: { data: unknown } }>) => (await call).data.data,
}))

import { fetchMyTarget } from './pmMetricsApi'

describe('fetchMyTarget', () => {
  it('asks for this month and leaves the month to the server', async () => {
    get.mockResolvedValue({ data: { data: { kind: 'WON_VALUE', target: 1000, progress: 900 } } })

    expect(await fetchMyTarget()).toEqual({ kind: 'WON_VALUE', target: 1000, progress: 900 })
    // No month on the wire: the server's business calendar says what "this month" is, not the browser's.
    expect(get.mock.calls[0][1].params).toBeUndefined()
  })

  it('is null when the caller has no target, so the desk page shows nothing rather than an error', async () => {
    get.mockImplementation(() => Promise.reject({ response: { status: 404 } }))

    expect(await fetchMyTarget()).toBeNull()
  })

  it('still fails on a real error, so a server fault is not hidden as "no target"', async () => {
    get.mockImplementation(() => Promise.reject({ response: { status: 500 } }))

    await expect(fetchMyTarget()).rejects.toBeDefined()
  })
})
