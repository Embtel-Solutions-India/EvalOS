import { useEffect, useState, type ReactNode } from 'react'
import { caseTitle, serviceLabel } from '../core/text'
import type { Message } from '../core/types'
import { useChat, useChatClient } from './ChatProvider'
import { Composer } from './Composer'
import { FileIcon } from './icons'
import { Menu } from './Menu'
import { MessageList, TypingLine } from './MessageList'
import { Participants } from './Participants'

/**
 * One conversation: case header (title, service, status, options, participants), banners, the
 * scrolling history, and the composer pinned under it. It fills its parent's height.
 *
 * @param tabs optional control under the title — `ChatScreen` puts the case's other conversations here
 */
export function ConversationView({
  conversationId,
  onUploadDocument,
  tabs,
}: {
  conversationId: string
  onUploadDocument?: () => void
  tabs?: ReactNode
}) {
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
  const service = serviceLabel(conversation.serviceType)
  const status = viewer ? { label: 'Read only', tone: 'off' } : conversation.status === 'READ_ONLY' ? { label: 'Closed', tone: 'off' } : { label: 'Open', tone: 'on' }

  return (
    <section className="ec-conversation">
      <header className="ec-header">
        <div className="ec-header__top">
          <span className="ec-avatar ec-avatar--case ec-avatar--lg">
            <FileIcon size={20} />
          </span>
          <div className="ec-header__title">
            <h2>{caseTitle(conversation)}</h2>
            {service && <p className="ec-muted">{service}</p>}
          </div>
          <span className={`ec-status ec-status--${status.tone}`}>{status.label}</span>
          <Menu
            label="Conversation options"
            items={[
              { label: 'Copy case code', onSelect: () => void navigator.clipboard?.writeText(conversation.caseCode).catch(() => {}) },
              ...(onUploadDocument && !readOnly ? [{ label: 'Upload a document', onSelect: onUploadDocument }] : []),
            ]}
          />
        </div>
        {tabs}
        <Participants conversationId={conversationId} />
      </header>
      {viewer && <p className="ec-banner">Oversight — read only</p>}
      {!viewer && conversation.status === 'READ_ONLY' && <p className="ec-banner">This case is closed. The conversation is kept as its history.</p>}
      {realtime === 'offline' && <p className="ec-banner ec-muted">Live updates are paused; new messages appear when you reopen this.</p>}
      {failed ? (
        <p className="ec-error ec-pad" role="alert">
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
