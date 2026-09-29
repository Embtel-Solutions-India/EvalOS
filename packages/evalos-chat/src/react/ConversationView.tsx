import { useEffect, useState } from 'react'
import type { Message } from '../core/types'
import { useChat, useChatClient } from './ChatProvider'
import { Composer } from './Composer'
import { MessageList, TypingLine } from './MessageList'
import { Participants } from './Participants'

/** One conversation: case header, read-only / oversight banner, history, composer. */
export function ConversationView({ conversationId, onUploadDocument }: { conversationId: string; onUploadDocument?: () => void }) {
  const client = useChatClient()
  const conversation = useChat((s) => s.conversations[conversationId])
  const realtime = useChat((s) => s.realtime)
  const [replyTo, setReplyTo] = useState<Message | null>(null)
  const [failed, setFailed] = useState(false)

  useEffect(() => {
    setFailed(false)
    setReplyTo(null) // A pending reply is this conversation's; another one starts clean (M1).
    client.openConversation(conversationId).catch(() => setFailed(true))
    return () => client.closeConversation(conversationId)
  }, [client, conversationId])

  if (!conversation) return <p className="ec-empty">This conversation is not available.</p>
  const viewer = conversation.access === 'VIEWER'
  const readOnly = viewer || conversation.status === 'READ_ONLY'

  return (
    <section className="ec-conversation">
      <header className="ec-header">
        <strong>{conversation.caseCode}</strong>
        {conversation.serviceType && <span className="ec-muted"> · {conversation.serviceType.replaceAll('_', ' ').toLowerCase()}</span>}
        <Participants conversationId={conversationId} />
      </header>
      {viewer && <p className="ec-banner">Oversight — read only</p>}
      {!viewer && conversation.status === 'READ_ONLY' && <p className="ec-banner">This case is closed. The conversation is kept as its history.</p>}
      {realtime === 'offline' && <p className="ec-banner ec-muted">Live updates are paused; new messages appear when you reopen this.</p>}
      {failed ? (
        <p className="ec-error" role="alert">
          Could not load messages.{' '}
          <button type="button" className="ec-link" onClick={() => client.openConversation(conversationId).then(() => setFailed(false), () => setFailed(true))}>
            Try again
          </button>
        </p>
      ) : (
        <MessageList conversationId={conversationId} readOnly={readOnly} onReply={setReplyTo} />
      )}
      <TypingLine conversationId={conversationId} />
      {!readOnly && (
        <Composer conversationId={conversationId} replyTo={replyTo} onCancelReply={() => setReplyTo(null)} onUploadDocument={onUploadDocument} />
      )}
    </section>
  )
}
