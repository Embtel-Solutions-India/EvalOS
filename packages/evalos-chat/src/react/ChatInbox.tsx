import { useMemo, useState, type FormEvent } from 'react'
import { filterInbox, type InboxFilter } from '../core/reducer'
import type { Conversation, ConversationType, Message } from '../core/types'
import { useChat, useChatClient } from './ChatProvider'

const TYPE_LABEL: Record<Conversation['type'], string> = { CLIENT: 'Case team', INTERNAL: 'Internal', EXPERT: 'Expert' }
const TABS: { type?: ConversationType; label: string }[] = [
  { label: 'All' },
  { type: 'CLIENT', label: 'Client' },
  { type: 'INTERNAL', label: 'Internal' },
  { type: 'EXPERT', label: 'Expert' },
]

/**
 * Conversations grouped by case, most recent activity first, each with its unread count.
 *
 * @param filters staff only: type tabs, an active / closed filter and search. A portal caller sees
 *                one type, so it has nothing to filter by.
 */
export function ChatInbox({ onOpen, selectedId, filters = false }: { onOpen(conversationId: string): void; selectedId?: string; filters?: boolean }) {
  const client = useChatClient()
  const loaded = useChat((s) => s.inboxLoaded)
  const error = useChat((s) => s.error)
  const order = useChat((s) => s.order)
  const conversations = useChat((s) => s.conversations)
  const [filter, setFilter] = useState<InboxFilter>({})
  const [results, setResults] = useState<Message[] | null>(null)

  const groups = useMemo(() => {
    const byCase = new Map<string, Conversation[]>()
    for (const id of filterInbox({ order, conversations }, filter)) {
      const c = conversations[id]!
      byCase.set(c.caseCode, [...(byCase.get(c.caseCode) ?? []), c])
    }
    return [...byCase.entries()]
  }, [order, conversations, filter])

  if (error) {
    return (
      <p className="ec-error" role="alert">
        {error}{' '}
        <button type="button" className="ec-link" onClick={() => void client.start()}>Try again</button>
      </p>
    )
  }
  if (!loaded) return <p className="ec-empty">Loading conversations…</p>

  return (
    <div className="ec-inbox">
      {filters && (
        <>
          <Search type={filter.type} onResults={setResults} />
          <div className="ec-tabs" role="tablist" aria-label="Conversation type">
            {TABS.map((t) => (
              <button
                key={t.label}
                type="button"
                role="tab"
                aria-selected={filter.type === t.type}
                className={filter.type === t.type ? 'ec-tab ec-tab--on' : 'ec-tab'}
                onClick={() => setFilter({ ...filter, type: t.type })}
              >
                {t.label}
              </button>
            ))}
          </div>
          <label className="ec-muted ec-filter">
            <input
              type="checkbox"
              checked={filter.status === 'ACTIVE'}
              onChange={(e) => setFilter({ ...filter, status: e.target.checked ? 'ACTIVE' : undefined })}
            />{' '}
            Open cases only
          </label>
        </>
      )}
      {results ? (
        <SearchResults results={results} conversations={conversations} onOpen={onOpen} onClear={() => setResults(null)} />
      ) : groups.length === 0 ? (
        <p className="ec-empty">{order.length === 0 ? 'No conversations yet. One opens with each case.' : 'Nothing matches this filter.'}</p>
      ) : (
        <nav aria-label="Conversations">
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
      )}
    </div>
  )
}

/** Full-text search over what the caller can open (`GET search`), within the current type tab. */
function Search({ type, onResults }: { type?: ConversationType; onResults(r: Message[] | null): void }) {
  const client = useChatClient()
  const [q, setQ] = useState('')
  const [busy, setBusy] = useState(false)
  const [failed, setFailed] = useState(false)

  async function submit(event: FormEvent) {
    event.preventDefault()
    if (!q.trim()) return onResults(null)
    setBusy(true)
    setFailed(false)
    try {
      onResults(await client.api.search(q.trim(), type ? { type } : {}))
    } catch {
      setFailed(true)
    } finally {
      setBusy(false)
    }
  }

  return (
    <form className="ec-search" role="search" onSubmit={(e) => void submit(e)}>
      <input
        type="search"
        className="ec-search__input"
        placeholder="Search messages"
        aria-label="Search messages"
        value={q}
        onChange={(e) => {
          setQ(e.target.value)
          if (!e.target.value) onResults(null)
        }}
      />
      <button type="submit" className="ec-send" disabled={busy}>Search</button>
      {failed && <span className="ec-error" role="alert">Search did not work. Try again.</span>}
    </form>
  )
}

function SearchResults({
  results,
  conversations,
  onOpen,
  onClear,
}: {
  results: Message[]
  conversations: Record<string, Conversation>
  onOpen(id: string): void
  onClear(): void
}) {
  return (
    <div className="ec-results">
      <p className="ec-muted">
        {results.length === 0 ? 'No messages match.' : `${results.length} ${results.length === 1 ? 'message' : 'messages'}`}{' '}
        <button type="button" className="ec-link" onClick={onClear}>Back to conversations</button>
      </p>
      {results.map((m) => {
        const c = conversations[m.conversationId]
        return (
          <button key={m.id} type="button" className="ec-inbox__row" onClick={() => onOpen(m.conversationId)}>
            <span className="ec-inbox__type">{c ? `${c.caseCode} · ${TYPE_LABEL[c.type]}` : 'Conversation'}</span>
            <span className="ec-inbox__preview ec-muted">
              {m.authorName ?? 'Someone'}: {m.body}
            </span>
          </button>
        )
      })}
    </div>
  )
}
