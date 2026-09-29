import type { ConversationType } from '../core/types'
import { useChat } from './ChatProvider'
import { ConversationView } from './ConversationView'

/** The case page's right-hand panel: that case's conversation of one type. */
export function CaseChatPanel({ caseId, type = 'CLIENT', onUploadDocument }: { caseId: string; type?: ConversationType; onUploadDocument?: () => void }) {
  const loaded = useChat((s) => s.inboxLoaded)
  const conversationId = useChat((s) => s.order.find((id) => s.conversations[id]?.caseId === caseId && s.conversations[id]?.type === type))

  if (!loaded) return <p className="ec-empty">Loading…</p>
  if (!conversationId) return <p className="ec-empty">The conversation for this case opens once the case team is assigned.</p>
  return <ConversationView conversationId={conversationId} onUploadDocument={onUploadDocument} />
}
