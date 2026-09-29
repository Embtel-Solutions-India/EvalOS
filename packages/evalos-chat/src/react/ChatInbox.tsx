import { useMemo, useState, type FormEvent } from 'react'
import { filterInbox, type InboxFilter } from '../core/reducer'
import { caseTitle, inboxTime, serviceLabel } from '../core/text'
import type { Conversation, ConversationType, Message } from '../core/types'
import { useChat, useChatClient } from './ChatProvider'
import { FileIcon, FilterIcon, SearchIcon } from './icons'

const TYPE_LABEL: Record<Conversation['type'], string> = { CLIENT: 'Case team', INTERNAL: 'Internal', EXPERT: 'Expert' }
const TYPES: { type?: ConversationType; label: string }[] = [
  { label: 'All' },
  { type: 'CLIENT', label: 'Client' },
  { type: 'INTERNAL', label: 'Internal' },
  { type: 'EXPERT', label: 'Expert' },
]

type CaseRow = { caseId: string; latest: Conversation; unread: number; selected: boolean }

/**
 * One row per case, most recent activity first: the client's name with the case code in brackets,
 * the service, the latest message and the case's unread count. A row opens that case's most recent
 * conversation; its other conversations are tabs beside the thread (`ChatScreen`).
 *
 * Typing narrows the list by name, code or service; Enter searches message text (`GET search`).
 *
 * @param filters staff only: a filter button with the type tabs and "open cases only". A portal
 *                caller sees one type, so it has nothing to filter by.
 */
export function ChatInbox({ onOpen, selectedId, filters = false }: { onOpen(conversationId: string): void; selectedId?: string; filters?: boolean }) {
  const client = useChatClient()
  const loaded = useChat((s) => s.inboxLoaded)
  const error = useChat((s) => s.error)
  const order = useChat((s) => s.order)
  const conversations = useChat((s) => s.conversations)
  const [filter, setFilter] = useState<InboxFilter>({})
  const [showFilters, setShowFilters] = useState(false)
  const [unreadOnly, setUnreadOnly] = useState(false)
  const [query, setQuery] = useState('')
  const [results, setResults] = useState<Message[] | null>(null)

  const cases = useMemo(() => {
    const byCase = new Map<string, Conversation[]>()
    for (const id of filterInbox({ order, conversations }, filter)) {
      const c = conversations[id]!
      byCase.set(c.caseId, [...(byCase.get(c.caseId) ?? []), c])
    }
    return [...byCase.entries()].map(
      ([caseId, list]): CaseRow => ({
        caseId,
        latest: list[0]!,
        unread: list.reduce((sum, c) => sum + c.unread, 0),
        selected: list.some((c) => c.id === selectedId),
      }),
    )
  }, [order, conversations, filter, selectedId])

  const q = query.trim().toLowerCase()
  const matching = q
    ? cases.filter(({ latest: c }) => [c.clientName, c.caseCode, serviceLabel(c.serviceType)].some((v) => v?.toLowerCase().includes(q)))
    : cases
  const unreadCases = matching.filter((c) => c.unread > 0)
  const shown = unreadOnly ? unreadCases : matching
  const filtered = filter.type !== undefined || filter.status !== undefined

  if (error) {
    return (
      <p className="ec-error ec-pad" role="alert">
        {error}{' '}
        <button type="button" className="ec-link" onClick={() => void client.start()}>Try again</button>
      </p>
    )
  }
  if (!loaded) return <p className="ec-empty ec-pad">Loading conversations…</p>

  return (
    <div className="ec-inbox">
      <div className="ec-inbox__tools">
        <div className="ec-inbox__search">
          <Search query={query} type={filter.type} onQuery={setQuery} onResults={setResults} />
          {filters && (
            <button
              type="button"
              className={filtered ? 'ec-icon-btn ec-icon-btn--boxed ec-icon-btn--on' : 'ec-icon-btn ec-icon-btn--boxed'}
              aria-label="Filter conversations"
              aria-expanded={showFilters}
              onClick={() => setShowFilters(!showFilters)}
            >
              <FilterIcon />
            </button>
          )}
        </div>
        {filters && showFilters && (
          <div className="ec-filters">
            <div className="ec-tabs" role="tablist" aria-label="Conversation type">
              {TYPES.map((t) => (
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
          </div>
        )}
        <div className="ec-segment" role="tablist" aria-label="Show">
          <button type="button" role="tab" aria-selected={!unreadOnly} className={unreadOnly ? 'ec-segment__btn' : 'ec-segment__btn ec-segment__btn--on'} onClick={() => setUnreadOnly(false)}>
            All ({matching.length})
          </button>
          <button type="button" role="tab" aria-selected={unreadOnly} className={unreadOnly ? 'ec-segment__btn ec-segment__btn--on' : 'ec-segment__btn'} onClick={() => setUnreadOnly(true)}>
            Unread ({unreadCases.length})
          </button>
        </div>
      </div>

      {results ? (
        <SearchResults results={results} conversations={conversations} onOpen={onOpen} onClear={() => setResults(null)} />
      ) : shown.length === 0 ? (
        <p className="ec-empty ec-pad">
          {order.length === 0 ? 'No conversations yet. One opens with each case.' : unreadOnly ? 'Nothing unread.' : 'Nothing matches.'}
        </p>
      ) : (
        <nav className="ec-inbox__list" aria-label="Conversations">
          {shown.map(({ caseId, latest, unread, selected }) => (
            <button key={caseId} type="button" className={selected ? 'ec-row ec-row--on' : 'ec-row'} onClick={() => onOpen(latest.id)}>
              <span className="ec-avatar ec-avatar--case">
                <FileIcon />
              </span>
              <span className="ec-row__main">
                <span className="ec-row__line">
                  <span className="ec-row__title">{caseTitle(latest)}</span>
                  {latest.lastMessageAt && <span className="ec-row__time">{inboxTime(latest.lastMessageAt)}</span>}
                </span>
                {latest.serviceType && <span className="ec-row__sub">{serviceLabel(latest.serviceType)}</span>}
                <span className="ec-row__line">
                  <span className="ec-row__preview">
                    {latest.lastMessage ? (latest.lastMessage.deleted ? 'Message deleted' : latest.lastMessage.body) : 'No messages yet'}
                  </span>
                  {unread > 0 && <span className="ec-badge">{unread}</span>}
                </span>
              </span>
            </button>
          ))}
        </nav>
      )}
    </div>
  )
}

/** Narrows the case list as you type; Enter searches message text within the current type tab. */
function Search({
  query,
  type,
  onQuery,
  onResults,
}: {
  query: string
  type?: ConversationType
  onQuery(q: string): void
  onResults(r: Message[] | null): void
}) {
  const client = useChatClient()
  const [failed, setFailed] = useState(false)

  async function submit(event: FormEvent) {
    event.preventDefault()
    if (!query.trim()) return onResults(null)
    setFailed(false)
    try {
      onResults(await client.api.search(query.trim(), type ? { type } : {}))
    } catch {
      setFailed(true)
    }
  }

  return (
    <form className="ec-search" role="search" onSubmit={(e) => void submit(e)}>
      <SearchIcon />
      <input
        type="search"
        className="ec-search__input"
        placeholder="Search conversations…"
        aria-label="Search conversations. Press Enter to search message text."
        title="Press Enter to search message text"
        value={query}
        onChange={(e) => {
          onQuery(e.target.value)
          onResults(null)
        }}
      />
      {failed && <span className="ec-error" role="alert">Search did not work.</span>}
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
    <div className="ec-inbox__list">
      <p className="ec-muted ec-pad">
        {results.length === 0 ? 'No messages match.' : `${results.length} ${results.length === 1 ? 'message' : 'messages'}`}{' '}
        <button type="button" className="ec-link" onClick={onClear}>Back to conversations</button>
      </p>
      {results.map((m) => {
        const c = conversations[m.conversationId]
        return (
          <button key={m.id} type="button" className="ec-row" onClick={() => onOpen(m.conversationId)}>
            <span className="ec-row__main">
              <span className="ec-row__title">{c ? `${caseTitle(c)} · ${TYPE_LABEL[c.type]}` : 'Conversation'}</span>
              <span className="ec-row__preview">
                {m.authorName ?? 'Someone'}: {m.body}
              </span>
            </span>
          </button>
        )
      })}
    </div>
  )
}
