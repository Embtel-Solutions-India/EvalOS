import { describe, expect, it } from 'vitest'
import { createChatApi, type Request } from './api'
import { keyBytes, pushState } from './push'

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
