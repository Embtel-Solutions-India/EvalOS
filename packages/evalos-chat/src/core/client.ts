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
  /** Set by `stop()`; guards against `start()` still being in flight (Review Focus: StrictMode). */
  let stopped = false

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
    stopped = false
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
    const close = await realtime.start({
      onEvent,
      onReconnect: () => void catchUp().catch(() => {}),
      onStatus: (status) => dispatch({ type: 'realtime', status }),
    })
    // stop() may have run while realtime.start() was still in flight (StrictMode's
    // mount-unmount-remount): close what we just opened instead of leaking it.
    if (stopped) {
      close()
      return
    }
    stopRealtime = close
  }

  function onEvent(envelope: Envelope) {
    dispatch({ type: 'event', envelope })
    switch (envelope.type) {
      case 'unread.changed':
        void refreshUnread().catch(() => {})
        break
      case 'members.changed':
      case 'access.granted':
      case 'access.revoked':
        void refreshInbox().catch(() => {})
        break
      case 'message.created':
        if (envelope.conversationId === onScreen) void markRead(envelope.conversationId).catch(() => {})
        break
    }
  }

  async function openConversation(id: string) {
    onScreen = id
    if (!state.messages[id]) {
      // The list exists as [] before the fetch starts, so a live event that arrives while the
      // first page is in flight is appended instead of dropped (the reducer never starts a list
      // from a lone live message). A conversation that was never opened still has no entry.
      dispatch({ type: 'page', conversationId: id, items: [], nextCursor: null })
      try {
        const page = await api.messages(id)
        dispatch({ type: 'page', conversationId: id, items: page.items, nextCursor: page.nextCursor })
      } catch {
        // Leave the placeholder; reopening or the next reconnect retries. No retry loop here.
      }
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
    // Snapshot every loaded conversation's cursor (or "empty") synchronously, before any await,
    // so a live message that lands mid-catch-up cannot move a not-yet-processed conversation's
    // cursor past the gap catch-up exists to fill.
    const cursors: Record<string, string | null> = {}
    for (const id of Object.keys(state.messages)) {
      const list = state.messages[id]
      const last = list[list.length - 1]
      cursors[id] = last ? cursorOf(last) : null
    }
    await Promise.all([refreshInbox(), refreshUnread()])
    for (const id of Object.keys(cursors)) {
      try {
        let after = cursors[id]
        if (!after) {
          const page = await api.messages(id)
          dispatch({ type: 'page', conversationId: id, items: page.items, nextCursor: page.nextCursor })
          continue
        }
        while (after) {
          const page = await api.messages(id, { after })
          dispatch({ type: 'newer', conversationId: id, items: page.items })
          after = page.nextCursor
        }
      } catch {
        // One conversation's failure must not stop the others. No retry loop: the next
        // reconnect or reopen retries.
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
    stop: () => {
      stopped = true
      stopRealtime?.()
    },
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
