import { cursorOf, type ChatApi, type InboxParams } from './api'
import { initialState, reactedByMe, reduce, type Action, type ChatState } from './reducer'
import type { Realtime } from './realtime'
import { keyOf, MAX_BODY, type Envelope, type Me, type Message, type Reaction } from './types'

/** How long a "typing" lasts without another; the backend relays at most one per 3s. */
export const TYPING_MS = 5000

/**
 * The chat store (Unit 57 §7): REST for every read and write, Ably for live events, REST again to
 * catch up after a reconnect. Framework-free — the React layer subscribes to it.
 */
export function createChatClient(api: ChatApi, realtime: Realtime | null) {
  let state: ChatState = initialState
  const listeners = new Set<() => void>()
  const incoming = new Set<(message: Message) => void>()
  const live = new Set<(signal: LiveSignal) => void>()
  const typingTimers = new Map<string, ReturnType<typeof setTimeout>>()
  const typingSent = new Map<string, number>()
  let inboxParams: InboxParams = {}
  let onScreen: string | null = null
  let stopRealtime: (() => void) | null = null
  /**
   * Bumped by `stop()`, and on every `start()`. A `start()` whose generation is no longer current
   * by the time `realtime.start()` resolves is stale — StrictMode's mount→unmount→remount runs
   * start, stop, start on the very same client, and a plain boolean can't tell the first start
   * from the second once stop has reset it.
   */
  let generation = 0
  /** Conversations whose first page loaded (Review Focus: a failed first fetch must be retryable). */
  const loaded = new Set<string>()

  /** Presence = reading a conversation in a visible tab; anything else and the server pushes. */
  function syncViewing() {
    realtime?.setViewing?.(onScreen !== null && !(typeof document !== 'undefined' && document.hidden))
  }
  if (typeof document !== 'undefined') document.addEventListener('visibilitychange', syncViewing)

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

  async function start(params: InboxParams = inboxParams) {
    const gen = ++generation
    inboxParams = params
    try {
      const me = await api.me()
      if (gen !== generation) return
      dispatch({ type: 'me', me })
      await Promise.all([refreshInbox(), refreshUnread()])
      if (gen !== generation) return
    } catch {
      if (gen !== generation) return
      dispatch({ type: 'error', message: 'Could not load your conversations.' })
      return
    }
    if (!realtime) {
      dispatch({ type: 'realtime', status: 'offline' })
      return
    }
    const close = await realtime.start({
      onEvent,
      onReconnect: () => {
        void catchUp().catch(() => {})
        // Unit 70: the screens outside chat re-read what they may have missed while offline.
        live.forEach((listener) => listener({ type: 'reconnected' }))
      },
      onStatus: (status) => {
        if (gen === generation) dispatch({ type: 'realtime', status })
      },
    })
    // A stop(), or a second start() on the same client (StrictMode's mount→unmount→remount),
    // may have run while realtime.start() was still in flight: close what this call just opened
    // instead of leaking it, and dispatch nothing more for it.
    if (gen !== generation) {
      close()
      return
    }
    stopRealtime = close
  }

  function onEvent(envelope: Envelope) {
    // Unit 70: screen-refresh signals are not chat state, so they never reach the reducer.
    if (envelope.type === 'case.changed' || envelope.type === 'notifications.changed') {
      const signal: LiveSignal =
        envelope.type === 'case.changed'
          ? { type: 'case.changed', caseId: (envelope.data as { caseId: string }).caseId }
          : { type: 'notifications.changed' }
      live.forEach((listener) => listener(signal))
      return
    }
    dispatch({ type: 'event', envelope })
    switch (envelope.type) {
      case 'typing': {
        const key = keyOf(envelope.data as Me)
        const timer = `${envelope.conversationId}|${key}`
        clearTimeout(typingTimers.get(timer))
        dispatch({ type: 'typing', conversationId: envelope.conversationId, key, on: true })
        typingTimers.set(timer, setTimeout(() => {
          typingTimers.delete(timer)
          dispatch({ type: 'typing', conversationId: envelope.conversationId, key, on: false })
        }, TYPING_MS))
        break
      }
      case 'unread.changed':
        void refreshUnread().catch(() => {})
        break
      case 'members.changed':
      case 'access.granted':
      case 'access.revoked':
        void refreshInbox().catch(() => {})
        break
      case 'message.created': {
        if (envelope.conversationId === onScreen) {
          void markRead(envelope.conversationId).catch(() => {})
          break
        }
        // Someone else's message in a conversation that is not on screen: the app's toast (57 §6).
        const message = envelope.data as Message
        const me = state.me
        if (!me || message.authorKind !== me.kind || message.authorId !== me.id) incoming.forEach((listener) => listener(message))
        break
      }
    }
  }

  async function openConversation(id: string) {
    onScreen = id
    syncViewing()
    // Whether to fetch depends on whether the first page actually loaded, not on whether the
    // list exists: the placeholder below makes it exist before that is known, so a failed fetch
    // must stay retryable on the next open instead of looking permanently (silently) loaded.
    if (!loaded.has(id)) {
      if (!state.messages[id]) {
        // The list exists as [] before the fetch starts, so a live event that arrives while the
        // first page is in flight is appended instead of dropped (the reducer never starts a list
        // from a lone live message). A conversation that was never opened still has no entry.
        dispatch({ type: 'page', conversationId: id, items: [], nextCursor: null })
      }
      // No try/catch: a failed fetch leaves the placeholder and `loaded` unmarked (so the next
      // open retries), and propagates so the caller's `.catch()` can show a retry affordance.
      const page = await api.messages(id)
      dispatch({ type: 'page', conversationId: id, items: page.items, nextCursor: page.nextCursor })
      loaded.add(id)
    } else {
      // Reopening an already-loaded conversation: without a reconnect there is otherwise no
      // catch-up, so a case team reply posted while the panel was closed never appears (I2). Page
      // it forward exactly as a reconnect would, and refresh the inbox/unread the same way.
      const list = state.messages[id]
      const last = list?.[list.length - 1]
      await catchUpOne(id, last ? cursorOf(last) : null)
      try {
        await Promise.all([refreshInbox(), refreshUnread()])
      } catch {
        // Best-effort: the conversation's own page above already succeeded.
      }
    }
    // Only a failed first fetch should reject and show "Could not load messages.": a failed
    // read-mark is a transient watermark failure, not a reason to hide history that did load.
    await markRead(id).catch(() => {})
    // "Seen by" is a nicety: without it the list simply shows none.
    await loadReadState(id).catch(() => {})
  }

  function closeConversation(id: string) {
    if (onScreen === id) {
      onScreen = null
      syncViewing()
    }
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
    try {
      await Promise.all([refreshInbox(), refreshUnread()])
    } catch {
      // The inbox or unread refresh failing must not abandon paging every conversation: those
      // are the first requests after a reconnect, and the most likely to fail on a flaky network.
    }
    for (const id of Object.keys(cursors)) {
      try {
        await catchUpOne(id, cursors[id])
      } catch {
        // One conversation's failure must not stop the others. No retry loop: the next
        // reconnect or reopen retries.
      }
    }
    if (onScreen) await markRead(onScreen)
  }

  /**
   * Pages one conversation forward from `cursor` (its last known top-level message) until
   * `nextCursor` is null, or fetches its first page when there is none yet. Shared by catch-up
   * after a reconnect (§5) and by reopening an already-loaded conversation without one (I2).
   */
  async function catchUpOne(id: string, cursor: string | null) {
    if (!cursor) {
      const page = await api.messages(id)
      dispatch({ type: 'page', conversationId: id, items: page.items, nextCursor: page.nextCursor })
      loaded.add(id)
      return
    }
    let after: string | null = cursor
    while (after) {
      const page = await api.messages(id, { after })
      dispatch({ type: 'newer', conversationId: id, items: page.items })
      after = page.nextCursor
    }
  }

  /**
   * Moves my watermark to the newest message this client knows for the conversation — only when
   * there is something unread. `conversations[id].lastMessage` already includes replies (the
   * inbox's own last message, and every live reply touches it too), so a reply that is the
   * newest thing in the conversation gets marked read even though it is never the list's last
   * top-level item (I1). The backend accepts either: `markRead` only checks the message is in
   * this conversation.
   */
  async function markRead(id: string) {
    const list = state.messages[id]
    const last = list?.[list.length - 1] ?? null
    const lastMessage = state.conversations[id]?.lastMessage ?? null
    const target = last && lastMessage ? (cursorOf(lastMessage) > cursorOf(last) ? lastMessage : last) : last ?? lastMessage
    if (!target || (state.conversations[id]?.unread ?? 0) === 0) return
    await api.read(id, target.id)
    dispatch({ type: 'read', conversationId: id })
    await refreshUnread()
  }

  async function send(id: string, body: string, parentId?: string) {
    const text = body.trim()
    if (!text) return
    if (text.length > MAX_BODY) throw new Error('A message is at most 4,000 characters.')
    dispatch({ type: 'upsert', message: await api.send(id, text, parentId) })
  }

  async function loadReadState(id: string) {
    dispatch({ type: 'readState', conversationId: id, readers: (await api.readState(id)).readers })
  }

  /** Call on every keystroke; sends at most one "typing" per 3s per conversation, matching the relay. */
  function typing(id: string) {
    const now = Date.now()
    if (now - (typingSent.get(id) ?? 0) < 3000) return
    typingSent.set(id, now)
    void api.typing(id).catch(() => {})
  }

  async function edit(message: Message, body: string) {
    const text = body.trim()
    if (!text) throw new Error('A message cannot be empty.')
    if (text.length > MAX_BODY) throw new Error('A message is at most 4,000 characters.')
    dispatch({ type: 'upsert', message: await api.edit(message.id, text) })
  }

  /** DELETE answers nothing; the live `message.deleted` carries the view, and REST-only needs this. */
  async function remove(message: Message) {
    await api.remove(message.id)
    dispatch({ type: 'upsert', message: { ...message, deleted: true, body: '', reactions: {} } })
  }

  async function toggleReaction(message: Message, reaction: Reaction) {
    const on = !reactedByMe(message, reaction, state.me)
    dispatch({ type: 'upsert', message: await api.react(message.id, reaction, on) })
  }

  return {
    /** The REST calls, for what the store does not hold (push subscriptions). */
    api,
    getState: () => state,
    subscribe(listener: () => void) {
      listeners.add(listener)
      return () => {
        listeners.delete(listener)
      }
    },
    /** Fires for a live message from someone else in a conversation that is not on screen. */
    onIncoming(listener: (message: Message) => void) {
      incoming.add(listener)
      return () => {
        incoming.delete(listener)
      }
    },
    /** Unit 70: `case.changed`, `notifications.changed`, and `reconnected` after a gap. */
    onLive(listener: (signal: LiveSignal) => void) {
      live.add(listener)
      return () => {
        live.delete(listener)
      }
    },
    start,
    stop: () => {
      generation++
      stopRealtime?.()
      stopRealtime = null
      typingTimers.forEach(clearTimeout)
      typingTimers.clear()
    },
    refreshInbox,
    openConversation,
    closeConversation,
    loadOlder,
    loadReplies,
    send,
    toggleReaction,
    typing,
    edit,
    remove,
    loadReadState,
    catchUp,
  }
}

export type ChatClient = ReturnType<typeof createChatClient>

/** A screen-refresh signal (Unit 70): never data — the screen re-reads over its own REST route. */
export type LiveSignal = { type: 'case.changed'; caseId: string } | { type: 'notifications.changed' } | { type: 'reconnected' }
