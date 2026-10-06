import { describe, expect, it } from 'vitest'
import { createChatApi, type Request } from './api'
import { keyBytes, pushState, sameKey } from './push'

describe('keyBytes', () => {
  it('decodes a base64url VAPID key, padding included', () => {
    // "AQID_w" is base64url for 01 02 03 ff — both the '_' and the missing '=' padding matter.
    expect([...keyBytes('AQID_w')]).toEqual([1, 2, 3, 255])
  })
})

describe('push routes', () => {
  it('speaks the Unit 57 push routes', async () => {
    const calls: string[] = []
    const request: Request = async <T,>(method: string, path: string, options?: { body?: unknown }) => {
      calls.push(`${method} ${path} ${JSON.stringify(options?.body ?? null)}`)
      return { publicKey: 'k' } as T
    }
    const api = createChatApi(request)
    expect(await api.pushPublicKey()).toBe('k')
    await api.unsubscribePush('https://fcm.googleapis.com/x')
    expect(calls).toEqual(['GET /push/public-key null', 'DELETE /push/subscriptions {"endpoint":"https://fcm.googleapis.com/x"}'])
  })
})

describe('pushState', () => {
  it('is unsupported without a Push API, and never asks the server', async () => {
    // vitest runs in node: no window, no serviceWorker.
    const api = createChatApi(async () => {
      throw new Error('should not be called')
    })
    expect(await pushState(api, '/sw.js')).toBe('unsupported')
  })
})

describe('sameKey', () => {
  const sub = (key: number[] | null) => ({ options: { applicationServerKey: key ? new Uint8Array(key).buffer : null } }) as unknown as PushSubscription

  it('matches the key the subscription was made with, and no other', () => {
    expect(sameKey(sub([1, 2, 3]), new Uint8Array([1, 2, 3]))).toBe(true)
    expect(sameKey(sub([1, 2, 3]), new Uint8Array([1, 2, 4]))).toBe(false)
    expect(sameKey(sub([1, 2, 3]), new Uint8Array([1, 2]))).toBe(false)
  })

  it('cannot tell when the browser hides the key, and does not resubscribe for that', () => {
    expect(sameKey(sub(null), new Uint8Array([1]))).toBe(true)
  })
})
