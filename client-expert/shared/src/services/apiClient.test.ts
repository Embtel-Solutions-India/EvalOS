import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

/** Unit 75 (D73): the portal sign-in lives in sessionStorage, and a server-side end returns to sign-in. */

type Store = Map<string, string>

function fakeSessionStorage(store: Store, broken = false) {
  const refuse = () => {
    throw new Error('SecurityError')
  }
  return {
    getItem: broken ? refuse : (key: string) => store.get(key) ?? null,
    setItem: broken ? refuse : (key: string, value: string) => void store.set(key, value),
    removeItem: broken ? refuse : (key: string) => void store.delete(key),
  }
}

let store: Store
let location: { assign: ReturnType<typeof vi.fn>; reload: ReturnType<typeof vi.fn> }

beforeEach(() => {
  vi.resetModules()
  store = new Map()
  location = { assign: vi.fn(), reload: vi.fn() }
  vi.stubGlobal('sessionStorage', fakeSessionStorage(store))
  vi.stubGlobal('window', { location })
})

afterEach(() => vi.unstubAllGlobals())

/** Answers every request with `status`, recording what was asked. */
function answer(api: typeof import('./apiClient'), status: number, calls: string[] = []) {
  api.apiClient.defaults.adapter = async (config) => {
    calls.push(`${config.method?.toUpperCase()} ${config.url}`)
    if (status >= 400) {
      const error = Object.assign(new Error(String(status)), {
        isAxiosError: true,
        config,
        response: { status, data: {}, headers: {}, config, statusText: '' },
      })
      throw error
    }
    return { status, data: { success: true }, headers: {}, config, statusText: 'OK' }
  }
  return calls
}

describe('the portal session', () => {
  it('survives a reload: a token saved before the page loads is read back', async () => {
    store.set('evalos.portal.token', 'saved')
    const api = await import('./apiClient')
    expect(api.hasPortalToken()).toBe(true)
  })

  it('writes a new sign-in to sessionStorage', async () => {
    const api = await import('./apiClient')
    expect(api.hasPortalToken()).toBe(false)
    api.setPortalToken('fresh')
    expect(store.get('evalos.portal.token')).toBe('fresh')
  })

  it('still works in memory when the browser refuses storage', async () => {
    vi.stubGlobal('sessionStorage', fakeSessionStorage(store, true))
    const api = await import('./apiClient')
    api.setPortalToken('memory-only')
    expect(api.hasPortalToken()).toBe(true)
  })

  it('signs out by revoking on the server, forgetting the token and going to sign-in', async () => {
    store.set('evalos.portal.token', 'saved')
    const api = await import('./apiClient')
    const calls = answer(api, 200)
    await api.signOut('/signin')
    expect(calls).toEqual(['POST /sign-out'])
    expect(store.has('evalos.portal.token')).toBe(false)
    expect(location.assign).toHaveBeenCalledWith('/signin')
  })

  it('signs this browser out even when the revoke fails', async () => {
    store.set('evalos.portal.token', 'saved')
    const api = await import('./apiClient')
    answer(api, 500)
    await api.signOut('/')
    expect(api.hasPortalToken()).toBe(false)
    expect(location.assign).toHaveBeenCalledWith('/')
  })

  it('a 401 while signed in ends the session and the sign-in screen says so, once', async () => {
    store.set('evalos.portal.token', 'expired')
    const api = await import('./apiClient')
    answer(api, 401)
    await expect(api.apiClient.get('/client/cases')).rejects.toBeTruthy()
    expect(api.hasPortalToken()).toBe(false)
    expect(location.reload).toHaveBeenCalledOnce()

    vi.resetModules()
    const reloaded = await import('./apiClient')
    expect(reloaded.takeSessionEnded()).toBe(true)
    expect(reloaded.takeSessionEnded()).toBe(true)
    vi.resetModules()
    expect((await import('./apiClient')).takeSessionEnded()).toBe(false)
  })

  it('a wrong password is not a session ending', async () => {
    store.set('evalos.portal.token', 'old')
    const api = await import('./apiClient')
    answer(api, 401)
    await expect(api.apiClient.post('/auth/sign-in', {})).rejects.toBeTruthy()
    expect(api.hasPortalToken()).toBe(true)
    expect(location.reload).not.toHaveBeenCalled()
  })
})
