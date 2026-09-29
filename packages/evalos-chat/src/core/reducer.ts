import { keyOf, type Conversation, type Envelope, type Me, type Message, type Reaction, type ReaderMark } from './types'

export type RealtimeStatus = 'connecting' | 'live' | 'offline'

export type ChatState = {
  me: Me | null
  inboxLoaded: boolean
  error: string | null
  conversations: Record<string, Conversation>
  /** Inbox order, most recent activity first. */
  order: string[]
  /** Top-level messages per conversation, oldest first. Absent = never opened. */
  messages: Record<string, Message[]>
  /** Where older history starts; null = there is none. */
  olderCursor: Record<string, string | null>
  /** Replies per parent message, oldest first. */
  replies: Record<string, Message[]>
  unreadTotal: number
  realtime: RealtimeStatus
  /** Read watermarks per conversation, for "seen by". Absent = not fetched. */
  readers: Record<string, ReaderMark[]>
  /** Who is typing per conversation, as `"KIND:uuid"` keys. The client expires them. */
  typing: Record<string, string[]>
}

export const initialState: ChatState = {
  me: null,
  inboxLoaded: false,
  error: null,
  conversations: {},
  order: [],
  messages: {},
  olderCursor: {},
  replies: {},
  unreadTotal: 0,
  realtime: 'connecting',
  readers: {},
  typing: {},
}

export type Action =
  | { type: 'me'; me: Me }
  | { type: 'inbox'; conversations: Conversation[] }
  /** A page from the server, newest first — the first page or older history. */
  | { type: 'page'; conversationId: string; items: Message[]; nextCursor: string | null }
  /** Catch-up after a reconnect, oldest first. */
  | { type: 'newer'; conversationId: string; items: Message[] }
  | { type: 'replies'; parentId: string; items: Message[] }
  /** A REST reply (send, react): the caller's own view of one message. */
  | { type: 'upsert'; message: Message }
  | { type: 'read'; conversationId: string }
  | { type: 'unread'; total: number }
  | { type: 'realtime'; status: RealtimeStatus }
  | { type: 'error'; message: string | null }
  | { type: 'event'; envelope: Envelope }
  | { type: 'readState'; conversationId: string; readers: ReaderMark[] }
  | { type: 'typing'; conversationId: string; key: string; on: boolean }

export function reactedByMe(message: Message, reaction: Reaction, me: Me | null): boolean {
  return !!me && (message.reactions[reaction] ?? []).some((r) => r.kind === me.kind && r.id === me.id)
}

export function reduce(state: ChatState, action: Action): ChatState {
  switch (action.type) {
    case 'me':
      return { ...state, me: action.me }
    case 'inbox': {
      const conversations = Object.fromEntries(action.conversations.map((c) => [c.id, c]))
      return { ...state, inboxLoaded: true, error: null, conversations, order: action.conversations.map((c) => c.id) }
    }
    case 'page': {
      const existing = state.messages[action.conversationId] ?? []
      const known = new Set(existing.map((m) => m.id))
      const older = [...action.items].reverse().filter((m) => !known.has(m.id)).map((m) => own(state, m))
      return {
        ...state,
        messages: { ...state.messages, [action.conversationId]: [...older, ...existing] },
        olderCursor: { ...state.olderCursor, [action.conversationId]: action.nextCursor },
      }
    }
    case 'newer':
      return action.items.reduce((s, m) => upsert(s, m, 'append').state, state)
    case 'replies':
      return { ...state, replies: { ...state.replies, [action.parentId]: action.items.map((m) => own(state, m)) } }
    case 'upsert':
      return upsert(state, action.message, 'append').state
    case 'read':
      return patchConversation(state, action.conversationId, { unread: 0 })
    case 'unread':
      return { ...state, unreadTotal: action.total }
    case 'realtime':
      return { ...state, realtime: action.status }
    case 'error':
      return { ...state, error: action.message }
    case 'event':
      return onEvent(state, action.envelope)
    case 'readState':
      return { ...state, readers: { ...state.readers, [action.conversationId]: action.readers } }
    case 'typing':
      return setTyping(state, action.conversationId, action.key, action.on)
  }
}

function onEvent(state: ChatState, envelope: Envelope): ChatState {
  switch (envelope.type) {
    case 'message.created': {
      // Whoever just sent has stopped typing.
      const sent = setTyping(state, envelope.conversationId, keyOf({ kind: (envelope.data as Message).authorKind, id: (envelope.data as Message).authorId }), false)
      const { state: next, added } = upsert(sent, envelope.data as Message, 'append')
      const message = own(state, envelope.data as Message)
      if (!added || message.mine) return next
      const c = next.conversations[envelope.conversationId]
      return c ? patchConversation(next, c.id, { unread: c.unread + 1 }) : next
    }
    case 'message.edited':
    case 'message.deleted':
    case 'reactions.changed':
      return upsert(state, envelope.data as Message, 'replace-only').state
    case 'read.moved': {
      const reader = envelope.data as ReaderMark
      const known = state.readers[envelope.conversationId]
      if (known) {
        // Only once fetched: a partial list would claim the others have read nothing.
        const others = known.filter((r) => keyOf(r) !== keyOf(reader))
        state = { ...state, readers: { ...state.readers, [envelope.conversationId]: [...others, reader] } }
      }
      const mine = state.me && reader.kind === state.me.kind && reader.id === state.me.id
      if (!mine) return state
      // `ReaderMark` carries only the watermark's message id, not its time, so "at or after the
      // conversation's newest message" is simplified to "is it the newest message": a watermark
      // behind `lastMessage` (a stale echo, or one that missed a newer reply) must not zero the
      // row, or it hides that message being unread (I1's fix relies on this).
      const c = state.conversations[envelope.conversationId]
      const caughtUp = !c?.lastMessage || reader.lastReadMessageId === c.lastMessage.id
      return caughtUp ? patchConversation(state, envelope.conversationId, { unread: 0 }) : state
    }
    case 'conversation.read_only':
      return patchConversation(state, envelope.conversationId, { status: 'READ_ONLY' })
    default:
      // members / access / unread / typing: the client refetches (see client.ts) or ignores.
      return state
  }
}

/** "Mine" from my identity: a live payload carries its author's value (Review Focus 5). */
function own(state: ChatState, m: Message): Message {
  if (!state.me) return m
  const mine = m.authorKind === state.me.kind && m.authorId === state.me.id
  return mine === m.mine ? m : { ...m, mine }
}

function upsert(state: ChatState, raw: Message, mode: 'append' | 'replace-only'): { state: ChatState; added: boolean } {
  const m = own(state, raw)
  if (m.parentId) {
    const thread = state.replies[m.parentId]
    const at = thread?.findIndex((r) => r.id === m.id) ?? -1
    if (at >= 0) {
      const next = [...thread!]
      next[at] = m
      return { state: { ...state, replies: { ...state.replies, [m.parentId]: next } }, added: false }
    }
    if (mode === 'replace-only') return { state, added: false }
    let s: ChatState = thread ? { ...state, replies: { ...state.replies, [m.parentId]: [...thread, m] } } : { ...state, replies: { ...state.replies, [m.parentId]: [m] } }
    s = bumpReplyCount(s, m)
    return { state: touch(s, m), added: true }
  }
  const list = state.messages[m.conversationId]
  const at = list?.findIndex((x) => x.id === m.id) ?? -1
  if (at >= 0) {
    const next = [...list!]
    next[at] = m
    return { state: { ...state, messages: { ...state.messages, [m.conversationId]: next } }, added: false }
  }
  if (mode === 'replace-only') return { state, added: false }
  // Never start a list with only the newest message: an unopened conversation loads its page on open.
  const s = list ? { ...state, messages: { ...state.messages, [m.conversationId]: [...list, m] } } : state
  return { state: touch(s, m), added: true }
}

/** A reply is counted once: the thread is initialized on first sight, so the second arrival (REST or event) replaces instead of re-counting. */
function bumpReplyCount(state: ChatState, reply: Message): ChatState {
  const list = state.messages[reply.conversationId]
  const at = list?.findIndex((x) => x.id === reply.parentId) ?? -1
  if (at < 0) return state
  const next = [...list!]
  next[at] = { ...next[at], replyCount: next[at].replyCount + 1 }
  return { ...state, messages: { ...state.messages, [reply.conversationId]: next } }
}

/** New activity: the inbox row shows it and moves to the top. */
function touch(state: ChatState, m: Message): ChatState {
  const c = state.conversations[m.conversationId]
  if (!c) return state
  const s = patchConversation(state, c.id, { lastMessage: m, lastMessageAt: m.createdAt })
  return { ...s, order: [c.id, ...s.order.filter((id) => id !== c.id)] }
}

function patchConversation(state: ChatState, id: string, patch: Partial<Conversation>): ChatState {
  const c = state.conversations[id]
  return c ? { ...state, conversations: { ...state.conversations, [id]: { ...c, ...patch } } } : state
}

export type InboxFilter = { type?: Conversation['type']; status?: Conversation['status'] }

/**
 * The inbox narrowed by type and status, in inbox order. Done over the loaded inbox (100 rows)
 * rather than a refetch, so switching tabs is instant.
 * ponytail: past 100 conversations per person this hides the rest; pass the filter to `inbox()` then.
 */
export function filterInbox(state: Pick<ChatState, 'order' | 'conversations'>, filter: InboxFilter): string[] {
  return state.order.filter((id) => {
    const c = state.conversations[id]
    return !!c && (!filter.type || c.type === filter.type) && (!filter.status || c.status === filter.status)
  })
}

function setTyping(state: ChatState, conversationId: string, key: string, on: boolean): ChatState {
  const now = state.typing[conversationId] ?? []
  if (on === now.includes(key)) return state
  const next = on ? [...now, key] : now.filter((k) => k !== key)
  return { ...state, typing: { ...state.typing, [conversationId]: next } }
}

/**
 * Who, besides me, has read up to my latest top-level message — "Seen by" under it. A watermark on
 * a message this list does not hold (an unloaded reply, older history) is compared by the times
 * the list does know; one it cannot place is left out rather than guessed.
 */
export function seenBy(messages: Message[], readers: ReaderMark[] | undefined, me: Me | null, replies: Message[] = []): string[] {
  if (!me || !readers) return []
  const latestMine = [...messages].reverse().find((m) => m.mine && !m.deleted)
  if (!latestMine) return []
  const at = new Map([...messages, ...replies].map((m) => [m.id, m.createdAt]))
  return readers
    .filter((r) => keyOf(r) !== keyOf(me))
    .filter((r) => {
      const t = at.get(r.lastReadMessageId)
      return t !== undefined && t >= latestMine.createdAt
    })
    .map((r) => r.name ?? 'Someone')
}
