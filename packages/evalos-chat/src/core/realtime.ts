import type * as Ably from 'ably'
import type { RealtimeStatus } from './reducer'
import type { Envelope, TokenRequest } from './types'

export type RealtimeHandlers = {
  onEvent(envelope: Envelope): void
  onReconnect(): void
  onStatus(status: RealtimeStatus): void
}

export type Realtime = { start(handlers: RealtimeHandlers): Promise<() => void> }

/** ably-js's `Realtime` constructor, passed in by the app so this package never imports Ably at runtime. */
export type AblyRealtimeCtor = new (options: Ably.ClientOptions) => Ably.Realtime

/** The backend's personal channel (`ChatChannels.personal`); a token's clientId is `KIND:uuid`. */
export function channelOf(clientId: string): string {
  return `chat:user:${clientId}`
}

/**
 * The brand signal channels a token may subscribe to (`live:brand:{id}`, Unit 70 §3).
 * ponytail: the GM's `live:brand:*` cannot be subscribed as a wildcard, so the GM's case screens
 * refresh on focus and on their own writes only; pass the GM's brand ids in if that falls short.
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
  return {
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
      const channel = client.channels.get(channelOf(first.clientId))
      // A rejected subscribe (attach refused) would otherwise be silent: status would still say
      // 'live' while no events ever arrive.
      void channel.subscribe((message) => onEvent(message.data as Envelope)).catch(() => onStatus('offline'))
      // Presence is how the backend decides between a live update and a push (§6).
      void channel.presence.enter().catch(() => {})
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
      return () => client.close()
    },
  }
}
