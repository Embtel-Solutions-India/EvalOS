import type { Conversation, ConversationType, Me, Message, Page, Reaction, TokenRequest } from './types'

export type Method = 'GET' | 'POST' | 'PUT' | 'DELETE'
export type Params = Record<string, string | number | undefined>

/**
 * The app's own HTTP call, already pointed at its chat surface (`/api/chat`,
 * `/api/portal/client/chat`, `/api/portal/expert/chat`) and unwrapping EvalOS's `{ success, data }`.
 * Injected so the package never knows about tokens or axios.
 */
export type Request = <T>(method: Method, path: string, options?: { body?: unknown; params?: Params }) => Promise<T>

export type InboxParams = { caseId?: string; type?: ConversationType }

export function createChatApi(request: Request) {
  return {
    me: () => request<Me>('GET', '/me'),
    inbox: (params: InboxParams = {}) =>
      request<Page<Conversation>>('GET', '/conversations', { params: { ...params, limit: 100 } }),
    messages: (conversationId: string, cursor: { before?: string; after?: string } = {}) =>
      request<Page<Message>>('GET', `/conversations/${conversationId}/messages`, { params: { ...cursor, limit: 50 } }),
    replies: (messageId: string) => request<Message[]>('GET', `/messages/${messageId}/replies`),
    send: (conversationId: string, body: string, parentId?: string) =>
      request<Message>('POST', `/conversations/${conversationId}/messages`, { body: { body, parentId: parentId ?? null } }),
    react: (messageId: string, reaction: Reaction, on: boolean) =>
      request<Message>(on ? 'PUT' : 'DELETE', `/messages/${messageId}/reactions/${reaction}`),
    read: (conversationId: string, messageId: string) =>
      request<void>('POST', `/conversations/${conversationId}/read`, { body: { messageId } }),
    unread: () => request<number>('GET', '/unread'),
    realtimeToken: () => request<TokenRequest>('GET', '/realtime/token'),
  }
}

export type ChatApi = ReturnType<typeof createChatApi>

/** The server's keyset cursor, `MessageService.Cursor`: `"<createdAt>|<id>"`. */
export function cursorOf(message: Message): string {
  return `${message.createdAt}|${message.id}`
}
