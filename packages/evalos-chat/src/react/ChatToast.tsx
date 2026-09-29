import { caseTitle } from '../core/text'
import { useEffect, useState } from 'react'
import type { Message } from '../core/types'
import { useChat, useChatClient } from './ChatProvider'

/**
 * The in-app toast (Unit 57 §6): a message arrived in a conversation that is not on screen. Says
 * who and where, like the push does; clicking it opens the conversation. One at a time, 6 seconds.
 */
export function ChatToast({ onOpen }: { onOpen(conversationId: string): void }) {
  const client = useChatClient()
  const conversations = useChat((s) => s.conversations)
  const [latest, setLatest] = useState<Message | null>(null)

  useEffect(() => client.onIncoming(setLatest), [client])
  useEffect(() => {
    if (!latest) return
    const timer = setTimeout(() => setLatest(null), 6000)
    return () => clearTimeout(timer)
  }, [latest])

  if (!latest) return null
  const c = conversations[latest.conversationId]
  const where = c ? caseTitle(c) : undefined
  return (
    <div className="ec-toast" role="status">
      <button
        type="button"
        className="ec-toast__open"
        onClick={() => {
          setLatest(null)
          onOpen(latest.conversationId)
        }}
      >
        New message from {latest.authorName ?? 'the case team'}
        {where && <span className="ec-muted"> · {where}</span>}
      </button>
      <button type="button" className="ec-link" aria-label="Dismiss" onClick={() => setLatest(null)}>
        ×
      </button>
    </div>
  )
}
