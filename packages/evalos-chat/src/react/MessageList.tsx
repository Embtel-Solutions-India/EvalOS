import { Fragment, useMemo, useState } from 'react'
import { seenBy } from '../core/reducer'
import { linkify, rowsWithDays } from '../core/text'
import { keyOf, type Message } from '../core/types'
import { useChat, useChatClient } from './ChatProvider'
import { OwnMessageActions } from './OwnMessageActions'
import { Reactions } from './Reactions'
import { ThreadPanel } from './ThreadPanel'

const EMPTY: Message[] = []
const NOBODY: string[] = []

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

/** "Cam and Pat are typing…", from the conversation's own participant names. */
export function TypingLine({ conversationId }: { conversationId: string }) {
  const keys = useChat((s) => s.typing[conversationId] ?? NOBODY)
  const participants = useChat((s) => s.conversations[conversationId]?.participants)
  if (keys.length === 0) return null
  const names = keys.map((k) => participants?.find((p) => keyOf(p) === k)?.name ?? 'Someone')
  return (
    <p className="ec-typing ec-muted" aria-live="polite">
      {names.join(' and ')} {names.length === 1 ? 'is' : 'are'} typing…
    </p>
  )
}

/** One conversation's history: earlier on request, day separators, reactions, replies inline. */
export function MessageList({ conversationId, readOnly, onReply }: { conversationId: string; readOnly: boolean; onReply(m: Message): void }) {
  const client = useChatClient()
  const messages = useChat((s) => s.messages[conversationId] ?? EMPTY)
  const older = useChat((s) => s.olderCursor[conversationId])
  const readers = useChat((s) => s.readers[conversationId])
  const me = useChat((s) => s.me)
  const [thread, setThread] = useState<string | null>(null)
  const seen = useMemo(() => seenBy(messages, readers, me), [messages, readers, me])
  const latestMine = useMemo(() => [...messages].reverse().find((m) => m.mine && !m.deleted)?.id, [messages])

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
            {!readOnly && row.message.mine && !row.message.deleted && <OwnMessageActions message={row.message} />}
            {row.message.id === latestMine && seen.length > 0 && <p className="ec-seen ec-muted">Seen by {seen.join(', ')}</p>}
            {thread === row.message.id && <ThreadPanel parent={row.message} readOnly={readOnly} />}
          </div>
        ),
      )}
    </div>
  )
}
