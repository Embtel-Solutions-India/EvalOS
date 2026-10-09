import { Fragment, useEffect, useMemo, useRef, useState } from 'react'
import { authorBadge, typingNames } from '../core/labels'
import { seenBy } from '../core/reducer'
import { initials, linkify, rowsWithDays } from '../core/text'
import { keyOf, type Message } from '../core/types'
import { useChat, useChatClient } from './ChatProvider'
import { ReplyIcon } from './icons'
import { Menu, type MenuItem } from './Menu'
import { OwnMessageActions, type OwnMode } from './OwnMessageActions'
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
  const messages = useChat((s) => s.messages[conversationId] ?? EMPTY)
  if (keys.length === 0) return null
  const names = typingNames(keys, participants, messages)
  return (
    <p className="ec-typing ec-muted" aria-live="polite">
      {names.join(' and ')} {names.length === 1 ? 'is' : 'are'} typing…
    </p>
  )
}

/**
 * One conversation's history, scrolling on its own: earlier on request, day separators, avatars,
 * a "⋮" per message (react; edit and delete on your own), Reply, and replies inline.
 *
 * Stays pinned to the newest message when one arrives, unless you have scrolled up to read.
 */
export function MessageList({ conversationId, readOnly, onReply }: { conversationId: string; readOnly: boolean; onReply(m: Message): void }) {
  const client = useChatClient()
  const messages = useChat((s) => s.messages[conversationId] ?? EMPTY)
  const older = useChat((s) => s.olderCursor[conversationId])
  const readers = useChat((s) => s.readers[conversationId])
  const me = useChat((s) => s.me)
  const [thread, setThread] = useState<string | null>(null)
  const seen = useMemo(() => seenBy(messages, readers, me), [messages, readers, me])
  const latestMine = useMemo(() => [...messages].reverse().find((m) => m.mine && !m.deleted)?.id, [messages])
  const list = useRef<HTMLDivElement>(null)
  const pinned = useRef(true)
  const newest = messages[messages.length - 1]?.id
  // The newest message at the moment the reader scrolled away from the bottom. What arrives after it
  // is "new" for the bar below; scrolling back down, or pressing the bar, clears it.
  const [away, setAway] = useState<{ conversationId: string; after: string } | null>(null)
  const behind = useMemo(() => {
    if (!away || away.conversationId !== conversationId) return 0
    const at = messages.findIndex((m) => m.id === away.after)
    return at < 0 ? 0 : messages.slice(at + 1).filter((m) => !m.mine && !m.deleted).length
  }, [away, conversationId, messages])

  useEffect(() => {
    pinned.current = true
  }, [conversationId])

  useEffect(() => {
    const el = list.current
    if (el && pinned.current) el.scrollTop = el.scrollHeight
  }, [newest, conversationId])

  if (messages.length === 0) return <p className="ec-empty ec-list ec-list--empty">No messages yet. Say hello.</p>

  return (
    <div
      className="ec-list"
      ref={list}
      onScroll={(e) => {
        const el = e.currentTarget
        const wasPinned = pinned.current
        pinned.current = el.scrollHeight - el.scrollTop - el.clientHeight < 48
        if (pinned.current) setAway(null)
        else if (wasPinned && newest) setAway({ conversationId, after: newest })
      }}
    >
      {older && (
        <button type="button" className="ec-link ec-older" onClick={() => void client.loadOlder(conversationId)}>
          Show earlier messages
        </button>
      )}
      {rowsWithDays(messages).map((row) =>
        row.kind === 'day' ? (
          <div key={`day-${row.key}`} className="ec-day">
            <span>{new Date(row.iso).toLocaleDateString(undefined, { weekday: 'short', day: 'numeric', month: 'short', year: 'numeric' })}</span>
          </div>
        ) : (
          <MessageRow
            key={row.message.id}
            message={row.message}
            readOnly={readOnly}
            onReply={onReply}
            threadOpen={thread === row.message.id}
            onToggleThread={() => setThread(thread === row.message.id ? null : row.message.id)}
            seen={row.message.id === latestMine && seen.length > 0 ? seen : null}
          />
        ),
      )}
      {behind > 0 && (
        <button
          type="button"
          className="ec-newbar"
          onClick={() => list.current?.scrollTo({ top: list.current.scrollHeight, behavior: 'smooth' })}
        >
          ↓ {behind} new {behind === 1 ? 'message' : 'messages'}
        </button>
      )}
    </div>
  )
}

/**
 * One message with its avatar, name, time, bubble and "⋮". Also a reply inside a thread
 * (`nested`): no Reply of its own and no thread, since replies go one level deep.
 */
export function MessageRow({
  message,
  readOnly,
  onReply,
  threadOpen = false,
  onToggleThread,
  seen = null,
  nested = false,
}: {
  message: Message
  readOnly: boolean
  onReply?: (m: Message) => void
  threadOpen?: boolean
  onToggleThread?: () => void
  seen?: string[] | null
  nested?: boolean
}) {
  const me = useChat((s) => s.me)
  const myName = useChat((s) => (me ? s.conversations[message.conversationId]?.participants.find((p) => keyOf(p) === keyOf(me))?.name : undefined))
  const [mode, setMode] = useState<OwnMode>('idle')
  const [picking, setPicking] = useState(false)
  const canAct = !readOnly && !message.deleted
  const mine = message.mine

  const items: MenuItem[] = canAct
    ? [
        { label: 'Add reaction', onSelect: () => setPicking(true) },
        ...(mine
          ? [
              { label: 'Edit', onSelect: () => setMode('editing') },
              { label: 'Delete', danger: true, onSelect: () => setMode('confirming') },
            ]
          : []),
      ]
    : []

  return (
    <div className={`ec-msg${mine ? ' ec-msg--mine' : ''}${nested ? ' ec-msg--nested' : ''}`}>
      <span className="ec-avatar" aria-hidden="true">
        {initials(mine ? (myName ?? 'You') : message.authorName)}
      </span>
      <div className="ec-msg__col">
        <div className="ec-msg__line">
          <div className="ec-bubble">
            <p className="ec-meta">
              <strong>{mine ? 'You' : (message.authorName ?? 'Unknown')}</strong>
              {authorBadge(message) && <span className="ec-muted"> · {authorBadge(message)}</span>}{' '}
              <span className="ec-muted">{new Date(message.createdAt).toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' })}</span>
            </p>
            {mode === 'editing' ? (
              <OwnMessageActions message={message} mode={mode} onDone={() => setMode('idle')} />
            ) : (
              <MessageBody message={message} />
            )}
          </div>
          <Menu label="Message options" items={items} />
        </div>
        {mode === 'confirming' && <OwnMessageActions message={message} mode={mode} onDone={() => setMode('idle')} />}
        {!message.deleted && <Reactions message={message} disabled={readOnly} picking={picking} onPicked={() => setPicking(false)} />}
        {!nested && (canAct || message.replyCount > 0) && (
          <p className="ec-msg__actions">
            {canAct && onReply && (
              <button type="button" className="ec-link ec-link--quiet" onClick={() => onReply(message)}>
                <ReplyIcon size={14} /> Reply
              </button>
            )}
            {message.replyCount > 0 && (
              <button type="button" className="ec-link" onClick={onToggleThread}>
                {threadOpen ? 'Hide replies' : `${message.replyCount} ${message.replyCount === 1 ? 'reply' : 'replies'}`}
              </button>
            )}
          </p>
        )}
        {seen && <p className="ec-seen ec-muted">Seen by {seen.join(', ')}</p>}
        {!nested && threadOpen && <ThreadPanel parent={message} readOnly={readOnly} />}
      </div>
    </div>
  )
}
