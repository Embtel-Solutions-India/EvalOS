import { describe, expect, it, vi } from 'vitest'
import { ablyRealtime, channelOf, liveChannels, viewChannel } from './realtime'
import type { Envelope, TokenRequest } from './types'

const token: TokenRequest = { keyName: 'k', clientId: 'CLIENT:c1', capability: '{}', ttl: 3_600_000, timestamp: 1, nonce: 'n', mac: 'm' }

/** Just enough of ably-js's Realtime for the connector: auth callback, one channel, connection events. */
class FakeRealtime {
  static last: FakeRealtime | null = null
  options: any
  subscribed: ((m: { data: unknown }) => void) | null = null
  entered = false
  closed = false
  connectionListener: ((c: { current: string }) => void) | null = null
  channelName = ''
  constructor(options: any) {
    this.options = options
    FakeRealtime.last = this
  }
  channels = {
    get: (name: string) => {
      this.channelName = name
      return {
        subscribe: async (cb: (m: { data: unknown }) => void) => {
          this.subscribed = cb
        },
        presence: {
          enter: async () => {
            this.entered = true
          },
        },
      }
    },
  }
  connection = { on: (cb: (c: { current: string }) => void) => (this.connectionListener = cb) }
  close() {
    this.closed = true
  }
}

function handlers() {
  return { onEvent: vi.fn(), onReconnect: vi.fn(), onStatus: vi.fn() }
}

describe('ablyRealtime', () => {
  /** Review Focus 3: no key on the server means REST only, and Ably is never constructed. */
  it('noRealtimeKeyMeansRestOnly', async () => {
    FakeRealtime.last = null
    const h = handlers()
    await ablyRealtime(FakeRealtime as never, () => Promise.reject(new Error('503'))).start(h)
    expect(FakeRealtime.last).toBeNull()
    expect(h.onStatus).toHaveBeenCalledWith('offline')
  })

  it('subscribes to my own channel, enters presence and relays envelopes', async () => {
    const h = handlers()
    const fetchToken = vi.fn().mockResolvedValue(token)
    const stop = await ablyRealtime(FakeRealtime as never, fetchToken).start(h)
    const fake = FakeRealtime.last!
    expect(fake.channelName).toBe('chat:user:CLIENT:c1')
    expect(fake.entered).toBe(true)
    const envelope: Envelope = { type: 'message.created', conversationId: 'v1', data: {} }
    fake.subscribed!({ data: envelope })
    expect(h.onEvent).toHaveBeenCalledWith(envelope)
    stop()
    expect(fake.closed).toBe(true)
  })

  it('reuses the token it fetched first, then asks again when Ably does', async () => {
    const fetchToken = vi.fn().mockResolvedValue(token)
    await ablyRealtime(FakeRealtime as never, fetchToken).start(handlers())
    const auth = FakeRealtime.last!.options.authCallback
    const got: unknown[] = []
    await new Promise<void>((done) => auth({}, (_e: unknown, t: unknown) => (got.push(t), done())))
    expect(fetchToken).toHaveBeenCalledTimes(1)
    await new Promise<void>((done) => auth({}, (_e: unknown, t: unknown) => (got.push(t), done())))
    expect(fetchToken).toHaveBeenCalledTimes(2)
    expect(got).toEqual([token, token])
  })

  it('reports live and offline, and signals a reconnect only after the first connection', async () => {
    const h = handlers()
    await ablyRealtime(FakeRealtime as never, vi.fn().mockResolvedValue(token)).start(h)
    const on = FakeRealtime.last!.connectionListener!
    on({ current: 'connected' })
    expect(h.onReconnect).not.toHaveBeenCalled()
    on({ current: 'disconnected' })
    on({ current: 'connected' })
    expect(h.onReconnect).toHaveBeenCalledTimes(1)
    expect(h.onStatus.mock.calls.map((c) => c[0])).toEqual(['live', 'offline', 'live'])
  })

  it('names the personal channel the way the backend does', () => {
    expect(channelOf('STAFF:abc')).toBe('chat:user:STAFF:abc')
  })
})

describe('liveChannels', () => {
  it('names the brand channels a staff token may hear, never the wildcard', () => {
    const cap = JSON.stringify({ 'chat:user:STAFF:1': ['subscribe'], 'live:brand:b1': ['subscribe'], 'live:brand:*': ['subscribe'] })
    expect(liveChannels(cap)).toEqual(['live:brand:b1'])
    expect(liveChannels('{"chat:user:CLIENT:1":["subscribe"]}')).toEqual([])
    expect(liveChannels('not json')).toEqual([])
  })
})

describe('viewChannel', () => {
  it('names the per-conversation channel the backend publishes every chat event to', () => {
    expect(viewChannel('b1', 'c9')).toBe('chat:view:b1:c9')
  })
})

/** A fake that remembers every channel's listeners, so a test can publish to any one of them. */
class ChannelsFake {
  static last: ChannelsFake | null = null
  listeners = new Map<string, Set<(m: { data: unknown }) => void>>()
  constructor(public options: unknown) {
    ChannelsFake.last = this
  }
  publish(channel: string, data: unknown) {
    this.listeners.get(channel)?.forEach((l) => l({ data }))
  }
  channels = {
    get: (name: string) => ({
      subscribe: async (cb: (m: { data: unknown }) => void) => {
        if (!this.listeners.has(name)) this.listeners.set(name, new Set())
        this.listeners.get(name)!.add(cb)
      },
      unsubscribe: (cb: (m: { data: unknown }) => void) => this.listeners.get(name)?.delete(cb),
      presence: { enter: async () => {} },
    }),
  }
  connection = { on: () => {} }
  close() {}
}

describe('ablyRealtime.watch', () => {
  const envelope: Envelope = { type: 'message.created', conversationId: 'c9', data: {} }

  it('relays a conversation channel the connector was asked to watch after it started', async () => {
    const h = handlers()
    const rt = ablyRealtime(ChannelsFake as never, vi.fn().mockResolvedValue(token))
    await rt.start(h)

    const stop = rt.watch!('chat:view:b1:c9')
    ChannelsFake.last!.publish('chat:view:b1:c9', envelope)
    expect(h.onEvent).toHaveBeenCalledWith(envelope)

    stop()
    ChannelsFake.last!.publish('chat:view:b1:c9', envelope)
    expect(h.onEvent).toHaveBeenCalledTimes(1)
  })

  it('subscribes a channel asked for before the connection existed, once it does', async () => {
    const h = handlers()
    const rt = ablyRealtime(ChannelsFake as never, vi.fn().mockResolvedValue(token))
    rt.watch!('chat:view:b1:early')

    await rt.start(h)
    ChannelsFake.last!.publish('chat:view:b1:early', envelope)

    expect(h.onEvent).toHaveBeenCalledWith(envelope)
  })

  it('forgets a channel stopped before the connection existed', async () => {
    const h = handlers()
    const rt = ablyRealtime(ChannelsFake as never, vi.fn().mockResolvedValue(token))
    rt.watch!('chat:view:b1:gone')()

    await rt.start(h)

    expect(ChannelsFake.last!.listeners.has('chat:view:b1:gone')).toBe(false)
  })
})
