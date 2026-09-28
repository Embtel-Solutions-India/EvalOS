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
      void channel.subscribe((message) => onEvent(message.data as Envelope))
      // Presence is how the backend decides between a live update and a push (§6).
      void channel.presence.enter().catch(() => {})
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
