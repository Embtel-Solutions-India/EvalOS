import { cursorOf, type ChatApi, type InboxParams } from './api'
import { initialState, reactedByMe, reduce, type Action, type ChatState } from './reducer'
import type { Realtime } from './realtime'
import { MAX_BODY, type Envelope, type Message, type Reaction } from './types'

/**
 * The chat store (Unit 57 §7): REST for every read and write, Ably for live events, REST again to
 * catch up after a reconnect. Framework-free — the React layer subscribes to it.
 */
export function createChatClient(api: ChatApi, realtime: Realtime | null) {
  let state: ChatState = initialState
  const listeners = new Set<() => void>()
  let inboxParams: InboxParams = {}
  let onScreen: string | null = null
  let stopRealtime: (() => void) | null = null

  function dispatch(action: Action) {
    state = reduce(state, action)
    listeners.forEach((listener) => listener())
  }

  async function refreshInbox() {
    const page = await api.inbox(inboxParams)
    dispatch({ type: 'inbox', conversations: page.items })
  }

  async function refreshUnread() {
    dispatch({ type: 'unread', total: await api.unread() })
  }

  async function start(params: InboxParams = {}) {
    inboxParams = params
    try {
      dispatch({ type: 'me', me: await api.me() })
      await Promise.all([refreshInbox(), refreshUnread()])
    } catch {
      dispatch({ type: 'error', message: 'Could not load your conversations.' })
      return
    }
    if (!realtime) {
      dispatch({ type: 'realtime', status: 'offline' })
      return
    }
    stopRealtime = await realtime.start({
      onEvent,
      onReconnect: () => void catchUp(),
      onStatus: (status) => dispatch({ type: 'realtime', status }),
    })
  }

  function onEvent(envelope: Envelope) {
    dispatch({ type: 'event', envelope })
    switch (envelope.type) {
      case 'unread.changed':
        void refreshUnread()
        break
      case 'members.changed':
      case 'access.granted':
      case 'access.revoked':
        void refreshInbox()
        break
      case 'message.created':
        if (envelope.conversationId === onScreen) void markRead(envelope.conversationId)
        break
    }
  }

  async function openConversation(id: string) {
    onScreen = id
    if (!state.messages[id]) {
      const page = await api.messages(id)
      dispatch({ type: 'page', conversationId: id, items: page.items, nextCursor: page.nextCursor })
    }
    await markRead(id)
  }

  function closeConversation(id: string) {
    if (onScreen === id) onScreen = null
  }

  async function loadOlder(id: string) {
    const before = state.olderCursor[id]
    if (!before) return
    const page = await api.messages(id, { before })
    dispatch({ type: 'page', conversationId: id, items: page.items, nextCursor: page.nextCursor })
  }

  async function loadReplies(parentId: string) {
    dispatch({ type: 'replies', parentId, items: await api.replies(parentId) })
  }

  /** After a reconnect: the inbox, the total, and every conversation already on the page (§5). */
  async function catchUp() {
    await Promise.all([refreshInbox(), refreshUnread()])
    for (const id of Object.keys(state.messages)) {
      const list = state.messages[id]
      const last = list[list.length - 1]
      if (!last) {
        const page = await api.messages(id)
        dispatch({ type: 'page', conversationId: id, items: page.items, nextCursor: page.nextCursor })
        continue
      }
      let after: string | null = cursorOf(last)
      while (after) {
        const page = await api.messages(id, { after })
        dispatch({ type: 'newer', conversationId: id, items: page.items })
        after = page.nextCursor
      }
    }
    if (onScreen) await markRead(onScreen)
  }

  /** Moves my watermark to the newest message on screen — only when there is something unread. */
  async function markRead(id: string) {
    const list = state.messages[id]
    const last = list?.[list.length - 1]
    if (!last || (state.conversations[id]?.unread ?? 0) === 0) return
    await api.read(id, last.id)
    dispatch({ type: 'read', conversationId: id })
    await refreshUnread()
  }

  async function send(id: string, body: string, parentId?: string) {
    const text = body.trim()
    if (!text) return
    if (text.length > MAX_BODY) throw new Error('A message is at most 4,000 characters.')
    dispatch({ type: 'upsert', message: await api.send(id, text, parentId) })
  }

  async function toggleReaction(message: Message, reaction: Reaction) {
    const on = !reactedByMe(message, reaction, state.me)
    dispatch({ type: 'upsert', message: await api.react(message.id, reaction, on) })
  }

  return {
    getState: () => state,
    subscribe(listener: () => void) {
      listeners.add(listener)
      return () => {
        listeners.delete(listener)
      }
    },
    start,
    stop: () => stopRealtime?.(),
    refreshInbox,
    openConversation,
    closeConversation,
    loadOlder,
    loadReplies,
    send,
    toggleReaction,
    catchUp,
  }
}

export type ChatClient = ReturnType<typeof createChatClient>
