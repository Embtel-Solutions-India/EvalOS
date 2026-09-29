import * as Ably from 'ably'
import { ablyRealtime, createChatApi, createChatClient, type ChatClient } from '@evalos/chat'
import { apiClient, unwrap, type ApiResponse } from '@shared/services/apiClient'

/**
 * Case chat (Unit 57) on the portal token, for either portal. The chat package never sees the
 * token: it calls the shared axios instance, pointed at `/client/chat` or `/expert/chat`.
 *
 * **Create one per page tree and hand it to `ChatProvider`**, which starts and stops it. Without an
 * Ably key the server's token route answers 503 and chat runs REST-only.
 */
export function createPortalChat(audience: 'client' | 'expert'): ChatClient {
  const api = createChatApi((method, path, options) =>
    unwrap(
      apiClient.request<ApiResponse<never>>({
        method,
        url: `/${audience}/chat${path}`,
        data: options?.body,
        params: options?.params,
      }),
    ),
  )
  return createChatClient(api, ablyRealtime(Ably.Realtime, api.realtimeToken))
}
