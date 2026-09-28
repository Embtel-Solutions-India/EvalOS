import { useEffect } from 'react'
import type { Message } from '../core/types'
import { useChat, useChatClient } from './ChatProvider'
import { Composer } from './Composer'
import { MessageBody } from './MessageList'
import { Reactions } from './Reactions'

const EMPTY: Message[] = []

/** The replies to one message, loaded when opened; a reply composer unless read-only. */
export function ThreadPanel({ parent, readOnly }: { parent: Message; readOnly: boolean }) {
  const client = useChatClient()
  const replies = useChat((s) => s.replies[parent.id] ?? EMPTY)

  useEffect(() => {
    void client.loadReplies(parent.id)
  }, [client, parent.id])

  return (
    <div className="ec-thread">
      {replies.map((reply) => (
        <div key={reply.id} className="ec-message ec-message--reply">
          <p className="ec-meta">
            <strong>{reply.mine ? 'You' : (reply.authorName ?? 'Unknown')}</strong>
          </p>
          <MessageBody message={reply} />
          {!reply.deleted && <Reactions message={reply} disabled={readOnly} />}
        </div>
      ))}
      {!readOnly && <Composer conversationId={parent.conversationId} replyTo={parent} />}
    </div>
  )
}
