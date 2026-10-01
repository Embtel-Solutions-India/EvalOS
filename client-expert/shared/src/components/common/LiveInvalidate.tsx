import { useEffect } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { useChatClient } from '@evalos/chat'

/**
 * Unit 70 §5: a staff write to one of this person's cases reaches the open portal. The backend
 * sends `case.changed` on their own private channel (a signal, never data); this re-reads the
 * portal's queries in the background, and every query after a reconnect. Signals within 500 ms
 * become one re-read. Mount inside `ChatProvider`.
 */
export function LiveInvalidate({ prefix }: { prefix: 'portal' | 'expert-portal' }) {
  const chat = useChatClient()
  const queries = useQueryClient()

  useEffect(() => {
    let timer: ReturnType<typeof setTimeout> | null = null
    let everything = false
    const stop = chat.onLive((signal) => {
      if (signal.type === 'notifications.changed') return // a staff bell; portals have none
      everything ||= signal.type === 'reconnected'
      timer ??= setTimeout(() => {
        timer = null
        void queries.invalidateQueries(everything ? undefined : { queryKey: [prefix] })
        everything = false
      }, 500)
    })
    return () => {
      stop()
      if (timer) clearTimeout(timer)
    }
  }, [chat, queries, prefix])

  return null
}
