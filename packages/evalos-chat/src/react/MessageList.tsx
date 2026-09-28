import { Fragment, useState } from 'react'
import { linkify, rowsWithDays } from '../core/text'
import type { Message } from '../core/types'
import { useChat, useChatClient } from './ChatProvider'
import { Reactions } from './Reactions'
import { ThreadPanel } from './ThreadPanel'

const EMPTY: Message[] = []

export function MessageBody({ message }: { message: Message }) {
  if (message.deleted) return <p className="ec-body ec-muted">Message deleted</p>
  return (
    <p className="ec-body">
      {linkify(message.body).map((part, i) =>
        part.href ? (
          <a key={i} href={part.href} target="_blank" rel="noopener noreferrer nofollow">
            {part.text}
          </a>
        ) : (
          <Fragment key={i}>{part.text}</Fragment>
        ),
      )}
      {message.editedAt && <span className="ec-muted"> (edited)</span>}
    </p>
  )
}

/** One conversation's history: earlier on request, day separators, reactions, replies inline. */
export function MessageList({ conversationId, readOnly, onReply }: { conversationId: string; readOnly: boolean; onReply(m: Message): void }) {
  const client = useChatClient()
  const messages = useChat((s) => s.messages[conversationId] ?? EMPTY)
  const older = useChat((s) => s.olderCursor[conversationId])
  const [thread, setThread] = useState<string | null>(null)

  if (messages.length === 0) return <p className="ec-empty">No messages yet.</p>

  return (
    <div className="ec-list">
      {older && (
        <button type="button" className="ec-link ec-older" onClick={() => void client.loadOlder(conversationId)}>
          Show earlier messages
        </button>
      )}
      {rowsWithDays(messages).map((row) =>
        row.kind === 'day' ? (
          <p key={`day-${row.key}`} className="ec-day">
            {new Date(row.iso).toLocaleDateString(undefined, { weekday: 'short', day: 'numeric', month: 'short', year: 'numeric' })}
          </p>
        ) : (
          <div key={row.message.id} className={row.message.mine ? 'ec-message ec-message--mine' : 'ec-message'}>
            <p className="ec-meta">
              <strong>{row.message.mine ? 'You' : (row.message.authorName ?? 'Unknown')}</strong>{' '}
              <span className="ec-muted">{new Date(row.message.createdAt).toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' })}</span>
            </p>
            <MessageBody message={row.message} />
            {!row.message.deleted && <Reactions message={row.message} disabled={readOnly} />}
            <p className="ec-actions">
              {!readOnly && !row.message.deleted && (
                <button type="button" className="ec-link" onClick={() => onReply(row.message)}>Reply</button>
              )}
              {row.message.replyCount > 0 && (
                <button type="button" className="ec-link" onClick={() => setThread(thread === row.message.id ? null : row.message.id)}>
                  {row.message.replyCount} {row.message.replyCount === 1 ? 'reply' : 'replies'}
                </button>
              )}
            </p>
            {thread === row.message.id && <ThreadPanel parent={row.message} readOnly={readOnly} />}
          </div>
        ),
      )}
    </div>
  )
}
