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
})
