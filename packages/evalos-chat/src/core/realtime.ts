import type * as Ably from 'ably'
import type { RealtimeStatus } from './reducer'
import type { Envelope, TokenRequest } from './types'

export type RealtimeHandlers = {
  onEvent(envelope: Envelope): void
  onReconnect(): void
  onStatus(status: RealtimeStatus): void
}

export type Realtime = {
  start(handlers: RealtimeHandlers): Promise<() => void>
  /** Whether this person is looking at a conversation; only then does the server hold their push back. */
  setViewing?(on: boolean): void
  /**
   * Hears every event of one conversation, for someone who is not a member of it (a GM taking part,
   * a viewer): their personal channel carries only the conversations they belong to. Returns the stop.
   */
  watch?(channel: string): () => void
}

/** ably-js's `Realtime` constructor, passed in by the app so this package never imports Ably at runtime. */
export type AblyRealtimeCtor = new (options: Ably.ClientOptions) => Ably.Realtime

/** The backend's personal channel (`ChatChannels.personal`); a token's clientId is `KIND:uuid`. */
export function channelOf(clientId: string): string {
  return `chat:user:${clientId}`
}

/** The backend's per-conversation channel (`ChatChannels.view`): every chat event of one conversation. */
export function viewChannel(brandId: string, conversationId: string): string {
  return `chat:view:${brandId}:${conversationId}`
}

/**
 * The brand signal channels a token may subscribe to (`live:brand:{id}`, Unit 70 §3).
 * A GM's token names every brand's channel one by one (the server lists them), since no client
 * can subscribe to a wildcard; a `*` entry, should one appear, is skipped.
 */
export function liveChannels(capability: string): string[] {
  try {
    return Object.keys(JSON.parse(capability) as Record<string, unknown>).filter(
      (name) => name.startsWith('live:brand:') && !name.endsWith('*'),
    )
  } catch {
    return []
  }
}

/**
 * Live delivery over Ably (Unit 57 §5): subscribe and enter presence on my own channel, never
 * publish. The token is fetched once up front: if the server has no Ably key (503) this answers
 * REST-only without ever constructing Ably, so nothing retries in a loop.
 */
export function ablyRealtime(RealtimeClass: AblyRealtimeCtor, fetchToken: () => Promise<TokenRequest>): Realtime {
  let wanted = false
  let setViewing: (on: boolean) => void = () => {}
  let live: Ably.Realtime | null = null
  let emit: ((envelope: Envelope) => void) | null = null
  /** Channels asked for before the connection existed; subscribed the moment it does. */
  const waiting = new Map<string, () => void>()

  function subscribeTo(name: string): () => void {
    const channel = live!.channels.get(name)
    const listener = (message: Ably.InboundMessage) => emit?.(message.data as Envelope)
    void channel.subscribe(listener).catch(() => {})
    return () => channel.unsubscribe(listener)
  }

  return {
    setViewing(on) {
      wanted = on
      setViewing(on)
    },
    watch(name) {
      if (live) return subscribeTo(name)
      let stop = () => {
        waiting.delete(name)
      }
      waiting.set(name, () => {
        stop = subscribeTo(name)
      })
      return () => stop()
    },
    async start({ onEvent, onReconnect, onStatus }) {
      let first: TokenRequest
      try {
        first = await fetchToken()
      } catch {
        onStatus('offline')
        return () => {}
      }
      let prefetched: TokenRequest | null = first
      const client = new RealtimeClass({
        clientId: first.clientId,
        authCallback: (_params, callback) => {
          const ready = prefetched
          prefetched = null
          ;(ready ? Promise.resolve(ready) : fetchToken()).then(
            (t) => callback(null, t as Ably.TokenRequest),
            (e: unknown) => callback(e instanceof Error ? e.message : String(e), null),
          )
        },
      })
      live = client
      emit = onEvent
      waiting.forEach((subscribe) => subscribe())
      waiting.clear()
      const channel = client.channels.get(channelOf(first.clientId))
      // A rejected subscribe (attach refused) would otherwise be silent: status would still say
      // 'live' while no events ever arrive.
      void channel.subscribe((message) => onEvent(message.data as Envelope)).catch(() => onStatus('offline'))
      // Presence is how the backend decides between a live update and a push (§6). It means "reading chat
      // right now", not "connected": the app calls setViewing, so any other screen still gets the push.
      setViewing = (on) => void (on ? channel.presence.enter() : channel.presence.leave()).catch(() => {})
      if (wanted) setViewing(true)
      // Unit 70: staff also hear their brand's "case X changed" signals. The channel is named in
      // the token's capability, so the package learns no new configuration.
      for (const name of liveChannels(first.capability)) {
        void client.channels.get(name).subscribe((message) => onEvent(message.data as Envelope)).catch(() => {})
      }
      let connectedBefore = false
      client.connection.on((change) => {
        if (change.current === 'connected') {
          onStatus('live')
          if (connectedBefore) onReconnect()
          connectedBefore = true
        } else if (change.current === 'disconnected' || change.current === 'suspended' || change.current === 'failed') {
          onStatus('offline')
        }
      })
      return () => {
        client.close()
        if (live === client) live = null
      }
    },
  }
}
