import { ChatScreen } from '@evalos/chat'
import { useNavigate, useParams } from 'react-router-dom'
import { useMe } from '../../lib/authContext'
import { mayReach } from '../shell/navigation'

/**
 * Every conversation the caller can open, one row per case (Unit 57 §7), on one screen. The
 * conversation is in the URL, so a push notification opens it directly — and Sales, who read no
 * case (D19c), get there without a case screen. The paperclip opens the case's documents, for the
 * roles that may open a case at all.
 */
export default function ConversationsPage() {
  const { conversationId } = useParams<{ conversationId: string }>()
  const navigate = useNavigate()
  const me = useMe()
  const readsCases = mayReach(me.role, '/cases/:id')

  return (
    <div className="pt-4" style={{ height: 'calc(100svh - var(--header-height) - var(--shell-gutter))' }}>
      <ChatScreen
        title="Messages"
        description="Coordinate with your team on every case."
        filters
        selectedId={conversationId}
        onOpen={(id) => navigate(`/conversations/${id}`)}
        onBack={() => navigate('/conversations')}
        onUploadDocument={readsCases ? (c) => navigate(`/cases/${c.caseId}`) : undefined}
      />
    </div>
  )
}
