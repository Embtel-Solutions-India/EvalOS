import { useMemo } from 'react'
import type { Conversation } from '../core/types'
import { useChat, useChatClient } from './ChatProvider'

const TYPE_LABEL: Record<Conversation['type'], string> = { CLIENT: 'Case team', INTERNAL: 'Internal', EXPERT: 'Expert' }

/** Conversations grouped by case, most recent activity first, each with its unread count. */
export function ChatInbox({ onOpen, selectedId }: { onOpen(conversationId: string): void; selectedId?: string }) {
  const client = useChatClient()
  const loaded = useChat((s) => s.inboxLoaded)
  const error = useChat((s) => s.error)
  const order = useChat((s) => s.order)
  const conversations = useChat((s) => s.conversations)

  const groups = useMemo(() => {
    const byCase = new Map<string, Conversation[]>()
    for (const id of order) {
      const c = conversations[id]
      if (c) byCase.set(c.caseCode, [...(byCase.get(c.caseCode) ?? []), c])
    }
    return [...byCase.entries()]
  }, [order, conversations])

  if (error) {
    return (
      <p className="ec-error" role="alert">
        {error}{' '}
        <button type="button" className="ec-link" onClick={() => void client.start()}>Try again</button>
      </p>
    )
  }
  if (!loaded) return <p className="ec-empty">Loading conversations…</p>
  if (groups.length === 0) return <p className="ec-empty">No conversations yet. One opens with each case.</p>

  return (
    <nav className="ec-inbox" aria-label="Conversations">
      {groups.map(([caseCode, list]) => (
        <div key={caseCode} className="ec-inbox__case">
          <p className="ec-inbox__title">{caseCode}</p>
          {list.map((c) => (
            <button
              key={c.id}
              type="button"
              className={c.id === selectedId ? 'ec-inbox__row ec-inbox__row--selected' : 'ec-inbox__row'}
              onClick={() => onOpen(c.id)}
            >
              <span className="ec-inbox__type">{TYPE_LABEL[c.type]}</span>
              <span className="ec-inbox__preview ec-muted">
                {c.lastMessage ? (c.lastMessage.deleted ? 'Message deleted' : c.lastMessage.body) : 'No messages yet'}
              </span>
              {c.unread > 0 && <span className="ec-badge">{c.unread}</span>}
            </button>
          ))}
        </div>
      ))}
    </nav>
  )
}
