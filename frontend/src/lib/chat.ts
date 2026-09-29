import * as Ably from 'ably'
import { ablyRealtime, createChatApi, createChatClient, type ChatClient } from '@evalos/chat'
import type { Role } from './session'
import { api, unwrap, type ApiResponse } from './api'

/**
 * Case chat for staff (Unit 57), on the staff JWT via this app's own axios instance at `/api/chat`.
 * Without an Ably key the token route answers 503 and chat runs REST-only.
 */
export function createStaffChat(): ChatClient {
  const chatApi = createChatApi((method, path, options) =>
    unwrap(api.request<ApiResponse<never>>({ method, url: `/chat${path}`, data: options?.body, params: options?.params })),
  )
  return createChatClient(chatApi, ablyRealtime(Ably.Realtime, chatApi.realtimeToken))
}

/** Marketing is in no conversation (`ChatMembership`): no chat client, no Conversations entry. */
export function chatsFor(role: Role): boolean {
  return role !== 'MARKETING'
}
