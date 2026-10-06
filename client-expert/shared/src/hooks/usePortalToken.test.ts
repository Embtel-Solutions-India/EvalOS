import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

/** Unit 75 (D73): a token in the link is taken once and removed from the address bar. */

let store: Map<string, string>
let replaceState: ReturnType<typeof vi.fn>

function at(hash: string) {
  vi.stubGlobal('window', {
    location: { hash, pathname: '/dashboard', search: '?tab=1' },
    history: { state: { key: 'router' }, replaceState },
  })
}

beforeEach(() => {
  vi.resetModules()
  store = new Map()
  replaceState = vi.fn()
  vi.stubGlobal('localStorage', {
    getItem: (key: string) => store.get(key) ?? null,
    setItem: (key: string, value: string) => void store.set(key, value),
    removeItem: (key: string) => void store.delete(key),
  })
})

afterEach(() => vi.unstubAllGlobals())

describe('liftFragmentToken', () => {
  it('keeps the token for the browser and drops it from the URL, so a reload cannot replay it', async () => {
    at('#link-token')
    const { liftFragmentToken } = await import('./usePortalToken')

    expect(liftFragmentToken()).toBe(true)
    expect(store.get('evalos.portal.token')).toBe('link-token')
    expect(replaceState).toHaveBeenCalledWith({ key: 'router' }, '', '/dashboard?tab=1')
  })

  it('leaves the URL alone when there is no token in it', async () => {
    at('')
    const { liftFragmentToken } = await import('./usePortalToken')

    expect(liftFragmentToken()).toBe(false)
    expect(replaceState).not.toHaveBeenCalled()
  })
})
