import { useEffect } from 'react'
import type { Message } from '../core/types'
import { useChat, useChatClient } from './ChatProvider'
import { Composer } from './Composer'
import { MessageRow } from './MessageList'

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
        <MessageRow key={reply.id} message={reply} readOnly={readOnly} nested />
      ))}
      {!readOnly && <Composer conversationId={parent.conversationId} replyTo={parent} compact />}
    </div>
  )
}
