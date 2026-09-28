import type { Conversation, ConversationType, Me, Message, Page, Presence, Reaction, ReaderMark, TokenRequest } from './types'

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
    /** Your own message only; the server refuses anyone else's. */
    edit: (messageId: string, body: string) => request<Message>('PUT', `/messages/${messageId}`, { body: { body } }),
    remove: (messageId: string) => request<void>('DELETE', `/messages/${messageId}`),
    search: (q: string, params: { caseId?: string; type?: ConversationType } = {}) =>
      request<Message[]>('GET', '/search', { params: { q, ...params } }),
    readState: (conversationId: string) =>
      request<{ conversationId: string; readers: ReaderMark[] }>('GET', `/conversations/${conversationId}/read-state`),
    presence: (conversationId: string) => request<Presence>('GET', `/conversations/${conversationId}/presence`),
    /** "I am typing" — the backend relays it (no token may publish) and drops repeats within 3s. */
    typing: (conversationId: string) => request<void>('POST', `/conversations/${conversationId}/typing`),
    react: (messageId: string, reaction: Reaction, on: boolean) =>
      request<Message>(on ? 'PUT' : 'DELETE', `/messages/${messageId}/reactions/${reaction}`),
    read: (conversationId: string, messageId: string) =>
      request<void>('POST', `/conversations/${conversationId}/read`, { body: { messageId } }),
    unread: () => request<number>('GET', '/unread'),
    realtimeToken: () => request<TokenRequest>('GET', '/realtime/token'),
    /** 503 when the server has no VAPID keys: push is off, chat still works (57 §6). */
    pushPublicKey: async () => (await request<{ publicKey: string }>('GET', '/push/public-key')).publicKey,
    subscribePush: (subscription: PushSubscriptionJSON) =>
      request<void>('POST', '/push/subscriptions', { body: subscription }),
    unsubscribePush: (endpoint: string) => request<void>('DELETE', '/push/subscriptions', { body: { endpoint } }),
  }
}

export type ChatApi = ReturnType<typeof createChatApi>

/** The server's keyset cursor, `MessageService.Cursor`: `"<createdAt>|<id>"`. */
export function cursorOf(message: Message): string {
  return `${message.createdAt}|${message.id}`
}
