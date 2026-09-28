import { describe, expect, it, vi } from 'vitest'
import { createChatClient } from './client'
import type { ChatApi } from './api'
import type { Realtime, RealtimeHandlers } from './realtime'
import type { Conversation, Message, Page } from './types'

const me = { kind: 'CLIENT' as const, id: 'client-1' }

function msg(id: string, conversationId = 'v1', over: Partial<Message> = {}): Message {
  return { id, conversationId, authorKind: 'STAFF', authorId: 'staff-1', authorName: 'Cam', body: id, parentId: null, replyCount: 0, createdAt: `2026-09-27T10:00:0${id.slice(-1)}Z`, editedAt: null, deleted: false, reactions: {}, mine: false, ...over }
}
function conv(id: string, over: Partial<Conversation> = {}): Conversation {
  return { id, caseId: `case-${id}`, caseCode: 'IE-1', serviceType: null, stage: 'CLIENT_REVIEW', type: 'CLIENT', status: 'ACTIVE', access: 'MEMBER', unread: 0, lastMessage: null, participants: [], lastMessageAt: null, ...over }
}
const page = (items: Message[], nextCursor: string | null = null): Page<Message> => ({ items, nextCursor })

function fakeApi(over: Partial<ChatApi> = {}): ChatApi {
  return {
    me: vi.fn().mockResolvedValue(me),
    inbox: vi.fn().mockResolvedValue({ items: [conv('v1'), conv('v2')], nextCursor: null }),
    messages: vi.fn().mockResolvedValue(page([])),
    replies: vi.fn().mockResolvedValue([]),
    send: vi.fn(),
    react: vi.fn(),
    read: vi.fn().mockResolvedValue(undefined),
    unread: vi.fn().mockResolvedValue(0),
    realtimeToken: vi.fn(),
    ...over,
  } as ChatApi
}

function fakeRealtime() {
  let h: RealtimeHandlers | null = null
  const realtime: Realtime = {
    start: async (handlers) => {
      h = handlers
      handlers.onStatus('live')
      return () => {}
    },
  }
  return { realtime, handlers: () => h! }
}

describe('createChatClient', () => {
  it('starts with who I am, the inbox and the unread total', async () => {
    const api = fakeApi({ unread: vi.fn().mockResolvedValue(3) })
    const client = createChatClient(api, null)
    await client.start({ type: 'CLIENT' })
    const s = client.getState()
    expect(s.me).toEqual(me)
    expect(s.order).toEqual(['v1', 'v2'])
    expect(s.unreadTotal).toBe(3)
    expect(s.realtime).toBe('offline')
    expect(api.inbox).toHaveBeenCalledWith({ type: 'CLIENT' })
  })

  it('reports a failed start instead of throwing', async () => {
    const client = createChatClient(fakeApi({ me: vi.fn().mockRejectedValue(new Error('boom')) }), null)
    await client.start()
    expect(client.getState().error).toBe('Could not load your conversations.')
  })

  it('opens a conversation and marks what it shows as read', async () => {
    const api = fakeApi({
      inbox: vi.fn().mockResolvedValue({ items: [conv('v1', { unread: 2 })], nextCursor: null }),
      messages: vi.fn().mockResolvedValue(page([msg('m2'), msg('m1')], 'older')),
    })
    const client = createChatClient(api, null)
    await client.start()
    await client.openConversation('v1')
    expect(client.getState().messages.v1.map((m) => m.id)).toEqual(['m1', 'm2'])
    expect(api.read).toHaveBeenCalledWith('v1', 'm2')
    expect(client.getState().conversations.v1.unread).toBe(0)
  })

  /** Review Focus 2: every loaded conversation catches up page by page; an empty one loads its first page. */
  it('aReconnectCatchesUpEveryLoadedConversation', async () => {
    const messages = vi.fn()
      .mockResolvedValueOnce(page([msg('m1')]))            // open v1
      .mockResolvedValueOnce(page([]))                     // open v2 (empty)
      .mockResolvedValueOnce(page([msg('m2')], 'next'))    // v1 after m1, page 1
      .mockResolvedValueOnce(page([msg('m3')]))            // v1 after m2, page 2
      .mockResolvedValueOnce(page([msg('m4', 'v2')]))      // v2 first page
    const api = fakeApi({ messages })
    const rt = fakeRealtime()
    const client = createChatClient(api, rt.realtime)
    await client.start()
    await client.openConversation('v1')
    await client.openConversation('v2')

    rt.handlers().onReconnect()
    await vi.waitFor(() => expect(messages).toHaveBeenCalledTimes(5))
    await vi.waitFor(() => expect(client.getState().messages.v2.map((m) => m.id)).toEqual(['m4']))

    expect(messages.mock.calls[2]).toEqual(['v1', { after: '2026-09-27T10:00:01Z|m1' }])
    expect(messages.mock.calls[3]).toEqual(['v1', { after: 'next' }])
    expect(messages.mock.calls[4]).toEqual(['v2'])
    expect(client.getState().messages.v1.map((m) => m.id)).toEqual(['m1', 'm2', 'm3'])
    expect(api.inbox).toHaveBeenCalledTimes(2)
  })

  it('refreshes the unread total and the inbox when the server says so', async () => {
    const api = fakeApi()
    const rt = fakeRealtime()
    const client = createChatClient(api, rt.realtime)
    await client.start()
    rt.handlers().onEvent({ type: 'unread.changed', conversationId: 'v1', data: null })
    rt.handlers().onEvent({ type: 'access.revoked', conversationId: 'v1', data: null })
    await vi.waitFor(() => expect(api.unread).toHaveBeenCalledTimes(2))
    await vi.waitFor(() => expect(api.inbox).toHaveBeenCalledTimes(2))
  })

  it('marks a live message read while its conversation is on screen, and not after closing it', async () => {
    const api = fakeApi({ messages: vi.fn().mockResolvedValue(page([msg('m1')])) })
    const rt = fakeRealtime()
    const client = createChatClient(api, rt.realtime)
    await client.start()
    await client.openConversation('v1')
    rt.handlers().onEvent({ type: 'message.created', conversationId: 'v1', data: msg('m2') })
    await vi.waitFor(() => expect(api.read).toHaveBeenCalledWith('v1', 'm2'))
    client.closeConversation('v1')
    rt.handlers().onEvent({ type: 'message.created', conversationId: 'v1', data: msg('m3') })
    await Promise.resolve()
    expect(api.read).not.toHaveBeenCalledWith('v1', 'm3')
  })

  it('sends trimmed text, refuses blank and over-long text, and toggles my reaction', async () => {
    const sent = msg('m9', 'v1', { authorKind: 'CLIENT', authorId: 'client-1', body: 'hi' })
    const reacted = { ...sent, reactions: { HEART: [{ kind: 'CLIENT' as const, id: 'client-1', name: 'Anita' }] } }
    const api = fakeApi({
      messages: vi.fn().mockResolvedValue(page([])),
      send: vi.fn().mockResolvedValue(sent),
      react: vi.fn().mockResolvedValue(reacted),
    })
    const client = createChatClient(api, null)
    await client.start()
    await client.openConversation('v1')

    await client.send('v1', '  hi  ')
    expect(api.send).toHaveBeenCalledWith('v1', 'hi', undefined)
    await client.send('v1', '   ')
    expect(api.send).toHaveBeenCalledTimes(1)
    await expect(client.send('v1', 'x'.repeat(4001))).rejects.toThrow('4,000')

    await client.toggleReaction(sent, 'HEART')
    expect(api.react).toHaveBeenCalledWith('m9', 'HEART', true)
    await client.toggleReaction(client.getState().messages.v1[0], 'HEART')
    expect(api.react).toHaveBeenLastCalledWith('m9', 'HEART', false)
  })

  /** Fix round 1, Important #1: the cursor for a not-yet-processed conversation is snapshotted
   * before catch-up starts, so a live message that lands on it mid-catch-up cannot make catch-up
   * skip the gap it exists to fill. */
  it('a live message on a not-yet-processed conversation does not skip what catch-up must still fetch for it', async () => {
    let resolveV1After: ((p: Page<Message>) => void) | null = null
    const messages = vi.fn()
      .mockResolvedValueOnce(page([msg('m1')]))                 // open v1
      .mockResolvedValueOnce(page([msg('m2', 'v2')]))           // open v2
      .mockImplementationOnce(() => new Promise((resolve) => { resolveV1After = resolve })) // v1 after m1 (catch-up), held open
      .mockResolvedValue(page([]))                              // everything after that
    const api = fakeApi({ messages })
    const rt = fakeRealtime()
    const client = createChatClient(api, rt.realtime)
    await client.start()
    await client.openConversation('v1')
    await client.openConversation('v2')

    rt.handlers().onReconnect()
    await vi.waitFor(() => expect(messages).toHaveBeenCalledTimes(3))
    // v1's catch-up fetch is still pending; a live message lands on v2, which the loop hasn't reached yet.
    rt.handlers().onEvent({ type: 'message.created', conversationId: 'v2', data: msg('m9', 'v2') })
    resolveV1After!(page([]))
    await vi.waitFor(() => expect(messages).toHaveBeenCalledTimes(4))

    expect(messages.mock.calls[3]).toEqual(['v2', { after: '2026-09-27T10:00:02Z|m2' }])
  })

  /** Fix round 1, Important #2: the list exists as [] before the first fetch, so a live event
   * that arrives while that fetch is in flight is appended instead of dropped. */
  it('keeps a live message that arrives while the first page is still loading', async () => {
    let resolveOpen: ((p: Page<Message>) => void) | null = null
    const messages = vi.fn().mockImplementationOnce(() => new Promise((resolve) => { resolveOpen = resolve }))
    const api = fakeApi({ messages })
    const rt = fakeRealtime()
    const client = createChatClient(api, rt.realtime)
    await client.start()

    const opening = client.openConversation('v1')
    rt.handlers().onEvent({ type: 'message.created', conversationId: 'v1', data: msg('m5') })
    resolveOpen!(page([msg('m1')]))
    await opening

    expect(client.getState().messages.v1.map((m) => m.id)).toEqual(['m1', 'm5'])
  })

  /** Fix round 1, Important #3: one conversation's failed catch-up fetch does not reject and does
   * not stop the others from catching up. */
  it('one conversation failing during catch-up does not stop the others, and nothing throws', async () => {
    const messages = vi.fn()
      .mockResolvedValueOnce(page([msg('m1')]))            // open v1
      .mockResolvedValueOnce(page([msg('m2', 'v2')]))      // open v2
      .mockRejectedValueOnce(new Error('network blip'))    // v1 after m1 (catch-up) fails
      .mockResolvedValueOnce(page([msg('m9', 'v2')]))      // v2 after m2 (catch-up) succeeds
    const api = fakeApi({ messages })
    const rt = fakeRealtime()
    const client = createChatClient(api, rt.realtime)
    await client.start()
    await client.openConversation('v1')
    await client.openConversation('v2')

    rt.handlers().onReconnect()
    await vi.waitFor(() => expect(messages).toHaveBeenCalledTimes(4))

    expect(client.getState().messages.v1.map((m) => m.id)).toEqual(['m1'])
    expect(client.getState().messages.v2.map((m) => m.id)).toEqual(['m2', 'm9'])
  })

  /** Fix round 1, Important #4: stop() called while start() is still awaiting realtime.start()
   * (StrictMode's mount-unmount-remount) closes the connection instead of leaking it. */
  it('stop called before start resolves still closes the realtime connection', async () => {
    const close = vi.fn()
    let resolveRealtimeStart: ((stop: () => void) => void) | null = null
    const realtime: Realtime = {
      start: () => new Promise((resolve) => { resolveRealtimeStart = resolve }),
    }
    const client = createChatClient(fakeApi(), realtime)

    const starting = client.start()
    await vi.waitFor(() => expect(resolveRealtimeStart).not.toBeNull())
    client.stop()
    resolveRealtimeStart!(close)
    await starting

    expect(close).toHaveBeenCalledTimes(1)
  })

  /** Fix round 2, Important A: the first requests after a reconnect (inbox, unread total) are the
   * ones most likely to fail on a flaky network; that must not abandon paging every conversation. */
  it('a failed inbox refresh during catch-up still pages the conversations', async () => {
    const api = fakeApi({
      messages: vi.fn().mockResolvedValue(page([msg('m1')])),
      inbox: vi.fn()
        .mockResolvedValueOnce({ items: [conv('v1')], nextCursor: null }) // start
        .mockRejectedValueOnce(new Error('network blip')),               // catch-up's refreshInbox fails
    })
    const rt = fakeRealtime()
    const client = createChatClient(api, rt.realtime)
    await client.start()
    await client.openConversation('v1')

    rt.handlers().onReconnect()
    await vi.waitFor(() => expect(api.messages).toHaveBeenCalledTimes(2))
  })

  /** Fix round 2, Important B: StrictMode runs start, stop, start on the SAME client. A plain
   * boolean can't tell the first start from the second once stop resets it; a generation can. */
  it('start, stop, start on the same client closes the first connection', async () => {
    const close1 = vi.fn()
    const close2 = vi.fn()
    let resolveFirst: ((stop: () => void) => void) | null = null
    let resolveSecond: ((stop: () => void) => void) | null = null
    let calls = 0
    const realtime: Realtime = {
      start: () =>
        new Promise((resolve) => {
          calls += 1
          if (calls === 1) resolveFirst = resolve
          else resolveSecond = resolve
        }),
    }
    const client = createChatClient(fakeApi(), realtime)

    const starting1 = client.start()
    await vi.waitFor(() => expect(resolveFirst).not.toBeNull())
    client.stop()
    const starting2 = client.start()
    await vi.waitFor(() => expect(resolveSecond).not.toBeNull())

    resolveFirst!(close1)
    resolveSecond!(close2)
    await Promise.all([starting1, starting2])

    expect(close1).toHaveBeenCalledTimes(1)
    expect(close2).not.toHaveBeenCalled()
  })

  /** Fix round 2, Important C: a failed first fetch must not look permanently loaded — reopening
   * (e.g. "Try again") has to fetch again, not settle for the empty placeholder forever. */
  it('retries the first fetch on a later open, after an earlier one failed', async () => {
    const api = fakeApi({
      messages: vi.fn()
        .mockRejectedValueOnce(new Error('network blip')) // first open fails
        .mockResolvedValueOnce(page([msg('m1')])),         // second open succeeds
    })
    const client = createChatClient(api, null)
    await client.start()

    await expect(client.openConversation('v1')).rejects.toThrow()
    expect(client.getState().messages.v1).toEqual([])

    await client.openConversation('v1')
    expect(client.getState().messages.v1.map((m) => m.id)).toEqual(['m1'])
    expect(api.messages).toHaveBeenCalledTimes(2)
  })

  /** Task 6 review, Important #1: `start()`'s default must not overwrite the provider's inbox
   * filter with `{}` — a "Try again" retry (a bare `client.start()`) has to keep the last filter. */
  it('a retry with no params keeps the last inbox filter', async () => {
    const api = fakeApi()
    const client = createChatClient(api, null)
    await client.start({ type: 'CLIENT' })
    await client.start()
    expect(api.inbox).toHaveBeenCalledTimes(2)
    expect(api.inbox).toHaveBeenNthCalledWith(1, { type: 'CLIENT' })
    expect(api.inbox).toHaveBeenNthCalledWith(2, { type: 'CLIENT' })
  })

  /** Task 6 review, Important #2: a failed read-mark after a successful first page must not make
   * `openConversation` reject — that would hide history that already loaded behind a load error. */
  it('does not reject when the first page loads but marking it read fails', async () => {
    const api = fakeApi({
      inbox: vi.fn().mockResolvedValue({ items: [conv('v1', { unread: 2 })], nextCursor: null }),
      messages: vi.fn().mockResolvedValue(page([msg('m1')])),
      read: vi.fn().mockRejectedValue(new Error('network blip')),
    })
    const client = createChatClient(api, null)
    await client.start()

    await expect(client.openConversation('v1')).resolves.toBeUndefined()
    expect(client.getState().messages.v1.map((m) => m.id)).toEqual(['m1'])
  })
})
