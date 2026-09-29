import type { ReactNode } from 'react'
import type { Conversation, ConversationType } from '../core/types'
import { useChat } from './ChatProvider'
import { ChatInbox } from './ChatInbox'
import { ConversationView } from './ConversationView'
import { BackIcon } from './icons'
import { PushCard } from './PushCard'

const TYPES: { type: ConversationType; label: string }[] = [
  { type: 'CLIENT', label: 'Client' },
  { type: 'INTERNAL', label: 'Internal' },
  { type: 'EXPERT', label: 'Expert' },
]

/**
 * The whole messages page, one screen tall: title and the notifications banner, then the inbox
 * and the open conversation side by side, each scrolling on its own. On a phone, one pane at a
 * time with Back. It fills its parent's height, so the app decides how tall the screen is.
 *
 * @param onUploadDocument where the paperclip goes for this conversation's case; omit to hide it
 */
export function ChatScreen({
  title,
  description,
  breadcrumb,
  selectedId,
  onOpen,
  onBack,
  filters = false,
  workerUrl = '/sw.js',
  onUploadDocument,
}: {
  title: string
  description?: string
  breadcrumb?: ReactNode
  selectedId?: string
  onOpen(conversationId: string): void
  onBack(): void
  filters?: boolean
  workerUrl?: string
  onUploadDocument?: (conversation: Conversation) => void
}) {
  const selected = useChat((s) => (selectedId ? s.conversations[selectedId] : undefined))

  return (
    <div className="ec-screen">
      <div className="ec-screen__head">
        <div>
          {breadcrumb && <nav className="ec-crumbs" aria-label="Breadcrumb">{breadcrumb}</nav>}
          <h1 className="ec-screen__title">{title}</h1>
          {description && <p className="ec-muted">{description}</p>}
        </div>
        {/* The one place permission is asked — never on load (57 §6). */}
        <PushCard workerUrl={workerUrl} />
      </div>
      <div className={selectedId ? 'ec-screen__panes ec-screen__panes--open' : 'ec-screen__panes'}>
        <div className="ec-pane ec-pane--inbox">
          <ChatInbox filters={filters} selectedId={selectedId} onOpen={onOpen} />
        </div>
        <div className="ec-pane ec-pane--thread">
          {selectedId ? (
            <>
              <button type="button" className="ec-link ec-back" onClick={onBack}>
                <BackIcon size={14} /> All conversations
              </button>
              <ConversationView
                key={selectedId}
                conversationId={selectedId}
                tabs={<CaseTabs conversationId={selectedId} onOpen={onOpen} />}
                onUploadDocument={selected && onUploadDocument ? () => onUploadDocument(selected) : undefined}
              />
            </>
          ) : (
            <p className="ec-empty ec-pane__empty">Choose a conversation.</p>
          )}
        </div>
      </div>
    </div>
  )
}

/**
 * The open case's conversations as tabs: Client, Internal, Expert. Only the ones the caller can
 * open are drawn, and nothing at all when there is only one — so a portal never shows a tab bar.
 */
export function CaseTabs({ conversationId, onOpen }: { conversationId: string; onOpen(id: string): void }) {
  const caseId = useChat((s) => s.conversations[conversationId]?.caseId)
  const order = useChat((s) => s.order)
  const conversations = useChat((s) => s.conversations)
  const tabs = TYPES.flatMap(({ type, label }) => {
    const id = order.find((c) => conversations[c]?.caseId === caseId && conversations[c]?.type === type)
    return id ? [{ id, label, unread: conversations[id]!.unread }] : []
  })
  if (tabs.length < 2) return null

  return (
    <div className="ec-tabs ec-case-tabs" role="tablist" aria-label="Conversations on this case">
      {tabs.map((t) => (
        <button
          key={t.id}
          type="button"
          role="tab"
          aria-selected={t.id === conversationId}
          className={t.id === conversationId ? 'ec-tab ec-tab--on' : 'ec-tab'}
          onClick={() => onOpen(t.id)}
        >
          {t.label}
          {t.unread > 0 && t.id !== conversationId && <span className="ec-badge">{t.unread}</span>}
        </button>
      ))}
    </div>
  )
}
