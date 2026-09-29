import { describe, expect, it } from 'vitest'
import { filterInbox, initialState, reactedByMe, reduce, seenBy, type ChatState } from './reducer'
import type { Conversation, Message } from './types'

const me = { kind: 'CLIENT' as const, id: 'client-1' }

function msg(id: string, over: Partial<Message> = {}): Message {
  return {
    id,
    conversationId: 'v1',
    authorKind: 'STAFF',
    authorId: 'staff-1',
    authorName: 'Cam',
    body: `body ${id}`,
    parentId: null,
    replyCount: 0,
    createdAt: `2026-09-27T10:00:0${id.slice(-1)}Z`,
    editedAt: null,
    deleted: false,
    reactions: {},
    mine: false,
    ...over,
  }
}

function conv(over: Partial<Conversation> = {}): Conversation {
  return {
    id: 'v1',
    caseId: 'c1',
    caseCode: 'IE-1042',
    clientName: null,
    serviceType: 'EXPERT_OPINION_LETTER',
    stage: 'CLIENT_REVIEW',
    type: 'CLIENT',
    status: 'ACTIVE',
    access: 'MEMBER',
    unread: 0,
    lastMessage: null,
    participants: [],
    lastMessageAt: null,
    ...over,
  }
}

/** Me known, one conversation in the inbox, its first page (server order: newest first) loaded. */
function opened(): ChatState {
  let s = reduce(initialState, { type: 'me', me })
  s = reduce(s, { type: 'inbox', conversations: [conv()] })
  return reduce(s, { type: 'page', conversationId: 'v1', items: [msg('m2'), msg('m1')], nextCursor: 'cur-1' })
}

const created = (m: Message) => ({ type: 'event' as const, envelope: { type: 'message.created', conversationId: 'v1', data: m } })

describe('read watermarks and seen-by', () => {
  const mark = (id: string, last: string, name = id) => ({ kind: 'STAFF' as const, id, name, lastReadMessageId: last })
  const mine = (id: string) => msg(id, { authorKind: 'CLIENT', authorId: 'client-1', mine: true })

  it('names who read up to my latest message, never me, never someone behind it', () => {
    const list = [msg('m1'), mine('m2'), msg('m3')]
    const readers = [mark('cam', 'm3'), mark('pat', 'm1'), { ...mark('x', 'm3'), kind: 'CLIENT' as const, id: 'client-1' }]
    expect(seenBy(list, readers, me)).toEqual(['cam'])
  })

  it('leaves out a watermark it cannot place', () => {
    expect(seenBy([mine('m2')], [mark('cam', 'unknown')], me)).toEqual([])
  })

  it('keeps read.moved in the fetched readers, and ignores it before they are fetched', () => {
    const moved = { type: 'event' as const, envelope: { type: 'read.moved', conversationId: 'v1', data: mark('cam', 'm2') } }
    let s = reduce(opened(), moved)
    expect(s.readers.v1).toBeUndefined()
    s = reduce(s, { type: 'readState', conversationId: 'v1', readers: [mark('cam', 'm1')] })
    s = reduce(s, moved)
    expect(s.readers.v1).toEqual([mark('cam', 'm2')])
  })
})

describe('filterInbox', () => {
  it('narrows by type and status, keeping inbox order', () => {
    const conversations = {
      a: conv({ id: 'a', type: 'CLIENT' }),
      b: conv({ id: 'b', type: 'INTERNAL' }),
      c: conv({ id: 'c', type: 'INTERNAL', status: 'READ_ONLY' }),
    }
    const s = { order: ['c', 'b', 'a'], conversations }
    expect(filterInbox(s, {})).toEqual(['c', 'b', 'a'])
    expect(filterInbox(s, { type: 'INTERNAL' })).toEqual(['c', 'b'])
    expect(filterInbox(s, { type: 'INTERNAL', status: 'ACTIVE' })).toEqual(['b'])
  })
})

describe('typing', () => {
  it('turns on and off, and a sent message ends it', () => {
    let s = reduce(opened(), { type: 'typing', conversationId: 'v1', key: 'STAFF:staff-1', on: true })
    expect(s.typing.v1).toEqual(['STAFF:staff-1'])
    s = reduce(s, created(msg('m3')))
    expect(s.typing.v1).toEqual([])
  })
})

describe('reduce', () => {
  it('shows a page oldest first and remembers where older history starts', () => {
    const s = opened()
    expect(s.messages.v1.map((m) => m.id)).toEqual(['m1', 'm2'])
    expect(s.olderCursor.v1).toBe('cur-1')
    expect(s.inboxLoaded).toBe(true)
  })

  it('puts an older page in front without repeating anything', () => {
    const s = reduce(opened(), { type: 'page', conversationId: 'v1', items: [msg('m1'), msg('m0')], nextCursor: null })
    expect(s.messages.v1.map((m) => m.id)).toEqual(['m0', 'm1', 'm2'])
    expect(s.olderCursor.v1).toBeNull()
  })

  /** Review Focus 1: the REST reply and the live event for one send, in either order. */
  it('aMessageArrivingTwiceIsShownOnce', () => {
    const mine = msg('m3', { authorKind: 'CLIENT', authorId: 'client-1' })
    const eventFirst = reduce(reduce(opened(), created(mine)), { type: 'upsert', message: mine })
    const restFirst = reduce(reduce(opened(), { type: 'upsert', message: mine }), created(mine))
    for (const s of [eventFirst, restFirst]) {
      expect(s.messages.v1.map((m) => m.id)).toEqual(['m1', 'm2', 'm3'])
      expect(s.conversations.v1.unread).toBe(0)
    }
  })

  it('counts a message from someone else as unread and floats its conversation to the top', () => {
    let s = reduce(opened(), { type: 'inbox', conversations: [conv({ id: 'v0', caseId: 'c0' }), conv()] })
    s = reduce(s, created(msg('m3')))
    expect(s.conversations.v1.unread).toBe(1)
    expect(s.conversations.v1.lastMessage?.id).toBe('m3')
    expect(s.order[0]).toBe('v1')
  })

  /** Review Focus 5: live payloads are built for their author. */
  it('mineIsComputedFromMyIdentityNotThePayload', () => {
    const fromStaffClaimingMine = msg('m3', { mine: true })
    const s = reduce(opened(), created(fromStaffClaimingMine))
    expect(s.messages.v1.at(-1)?.mine).toBe(false)
    expect(s.conversations.v1.unread).toBe(1)
  })

  it('does not invent history for a conversation that was never opened', () => {
    let s = reduce(initialState, { type: 'me', me })
    s = reduce(s, { type: 'inbox', conversations: [conv()] })
    s = reduce(s, created(msg('m3')))
    expect(s.messages.v1).toBeUndefined()
    expect(s.conversations.v1.unread).toBe(1)
  })

  it('files a reply under its parent and counts it once', () => {
    const reply = msg('m4', { parentId: 'm1' })
    let s = reduce(opened(), { type: 'replies', parentId: 'm1', items: [] })
    s = reduce(reduce(s, created(reply)), created(reply))
    expect(s.replies.m1.map((m) => m.id)).toEqual(['m4'])
    expect(s.messages.v1[0].replyCount).toBe(1)
    expect(s.messages.v1.map((m) => m.id)).toEqual(['m1', 'm2'])
  })

  it('replaces an edited, deleted or re-reacted message in place and ignores one it never loaded', () => {
    const edited = msg('m1', { body: 'fixed', editedAt: '2026-09-27T11:00:00Z' })
    let s = reduce(opened(), { type: 'event', envelope: { type: 'message.edited', conversationId: 'v1', data: edited } })
    expect(s.messages.v1[0].body).toBe('fixed')
    s = reduce(s, { type: 'event', envelope: { type: 'reactions.changed', conversationId: 'v1', data: msg('m9') } })
    expect(s.messages.v1.map((m) => m.id)).toEqual(['m1', 'm2'])
  })

  it('clears unread when I read, and not when someone else does', () => {
    let s = reduce(opened(), created(msg('m3')))
    s = reduce(s, { type: 'event', envelope: { type: 'read.moved', conversationId: 'v1', data: { kind: 'STAFF', id: 'staff-1', name: 'Cam', lastReadMessageId: 'm3' } } })
    expect(s.conversations.v1.unread).toBe(1)
    s = reduce(s, { type: 'event', envelope: { type: 'read.moved', conversationId: 'v1', data: { kind: 'CLIENT', id: 'client-1', name: 'Anita', lastReadMessageId: 'm3' } } })
    expect(s.conversations.v1.unread).toBe(0)
  })

  /** Final review, deferred #6 (folded into Important #1): `ReaderMark` carries only a message
   * id, not a time, so a watermark behind the conversation's newest message must not zero the
   * row — that is what let a permanently-unread reply (I1) look read on the client. */
  it("does not clear unread when my watermark is behind the conversation's newest message", () => {
    let s = reduce(opened(), created(msg('m3')))
    expect(s.conversations.v1.lastMessage?.id).toBe('m3')
    s = reduce(s, { type: 'event', envelope: { type: 'read.moved', conversationId: 'v1', data: { kind: 'CLIENT', id: 'client-1', name: 'Anita', lastReadMessageId: 'm2' } } })
    expect(s.conversations.v1.unread).toBe(1)
  })

  it('freezes a conversation that became read-only', () => {
    const s = reduce(opened(), { type: 'event', envelope: { type: 'conversation.read_only', conversationId: 'v1', data: null } })
    expect(s.conversations.v1.status).toBe('READ_ONLY')
  })

  /** Review Focus 1 (replies): avoid double-counting when thread is not loaded. */
  it('counts a reply once when its thread is not loaded (upsert + event in both orders)', () => {
    const reply = msg('m4', { parentId: 'm1' })
    // Case 1: upsert first, then event
    let s = reduce(opened(), { type: 'upsert', message: reply })
    s = reduce(s, created(reply))
    expect(s.replies.m1.map((m) => m.id)).toEqual(['m4'])
    expect(s.messages.v1[0].replyCount).toBe(1)
    // Case 2: event first, then upsert
    s = reduce(opened(), created(reply))
    s = reduce(s, { type: 'upsert', message: reply })
    expect(s.replies.m1.map((m) => m.id)).toEqual(['m4'])
    expect(s.messages.v1[0].replyCount).toBe(1)
  })

  it('knows whether I reacted', () => {
    const m = msg('m1', { reactions: { HEART: [{ kind: 'CLIENT', id: 'client-1', name: 'Anita' }], THANKS: [{ kind: 'STAFF', id: 'staff-1', name: 'Cam' }] } })
    expect(reactedByMe(m, 'HEART', me)).toBe(true)
    expect(reactedByMe(m, 'THANKS', me)).toBe(false)
    expect(reactedByMe(m, 'LAUGH', null)).toBe(false)
  })
})
