import { ChatInbox, ConversationView, PushCard } from '@evalos/chat'
import { Link, useNavigate, useParams } from 'react-router-dom'

/**
 * Every conversation the caller can open, grouped by case (Unit 57 §7). The conversation is in the
 * URL, so a push notification opens it directly — and Sales, who read no case (D19c), get there
 * without a case screen.
 */
export default function ConversationsPage() {
  const { conversationId } = useParams<{ conversationId: string }>()
  const navigate = useNavigate()

  return (
    <div className="flex flex-col gap-4 pt-4">
      {/* The one place permission is asked — never on load (57 §6). */}
      <PushCard workerUrl="/sw.js" />
      <div className="grid gap-4 lg:grid-cols-[20rem_minmax(0,1fr)]">
        <div
          className={`rounded-lg border p-3 ${conversationId ? 'hidden lg:block' : ''}`}
          style={{ background: 'var(--bg-surface)', borderColor: 'var(--border-default)' }}
        >
          <ChatInbox selectedId={conversationId} onOpen={(id) => navigate(`/conversations/${id}`)} />
        </div>
        <div
          className={`flex h-[calc(100svh-10rem)] flex-col rounded-lg border p-4 ${conversationId ? '' : 'hidden lg:flex'}`}
          style={{ background: 'var(--bg-surface)', borderColor: 'var(--border-default)' }}
        >
          {conversationId ? (
            <>
              <Link to="/conversations" className="mb-2 self-start text-sm lg:hidden" style={{ color: 'var(--accent-primary)' }}>
                Back
              </Link>
              <ConversationView conversationId={conversationId} />
            </>
          ) : (
            <p className="text-sm" style={{ color: 'var(--text-muted)' }}>
              Choose a conversation.
            </p>
          )}
        </div>
      </div>
    </div>
  )
}
