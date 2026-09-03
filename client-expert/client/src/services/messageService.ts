import { MOCK_CONVERSATIONS, MOCK_MESSAGES } from '@/mock/mockData'
import { mockDelay } from '@shared/mock/mockDelay'
import type { Conversation, Message } from '@/types'

const conversationStore: Record<string, Message[]> = structuredClone(MOCK_MESSAGES)

export async function listConversations(): Promise<Conversation[]> {
  await mockDelay()
  return MOCK_CONVERSATIONS
}

export async function listMessages(conversationId: string): Promise<Message[]> {
  await mockDelay()
  return conversationStore[conversationId] ?? []
}

export async function sendMessage(conversationId: string, body: string): Promise<Message> {
  await mockDelay(400)
  const message: Message = {
    id: `msg-${Date.now()}`,
    conversationId,
    sender: 'client',
    senderName: 'You',
    body,
    createdAt: new Date().toISOString(),
  }
  conversationStore[conversationId] = [...(conversationStore[conversationId] ?? []), message]
  return message
}
