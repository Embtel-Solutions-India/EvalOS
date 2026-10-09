import { useMemo } from 'react'
import { useChat, type ConversationType } from '@evalos/chat'

export type CaseUnread = Record<ConversationType, number> & { total: number }

/** Unread messages in this case's three conversations, from the chat client's own inbox state. */
export function useCaseUnread(caseId: string): CaseUnread {
  const conversations = useChat((s) => s.conversations)
  return useMemo(() => {
    const unread: CaseUnread = { CLIENT: 0, INTERNAL: 0, EXPERT: 0, total: 0 }
    for (const conversation of Object.values(conversations)) {
      if (conversation.caseId !== caseId) continue
      unread[conversation.type] += conversation.unread
      unread.total += conversation.unread
    }
    return unread
  }, [conversations, caseId])
}
