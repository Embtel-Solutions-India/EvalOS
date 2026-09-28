import * as Ably from 'ably'
import { ablyRealtime, createChatApi, createChatClient, type ChatClient } from '@evalos/chat'
import { apiClient, unwrap, type ApiResponse } from '@shared/services/apiClient'

/**
 * The client's case chat (Unit 57), on the portal token. The chat package never sees the token:
 * it calls this app's own axios instance, pointed at `/client/chat`.
 *
 * **One client per signed-in shell** — `PortalLayout` creates it once and `ChatProvider` starts
 * and stops it. Without an Ably key the server's token route answers 503 and chat runs REST-only.
 */
export function createPortalChat(): ChatClient {
  const api = createChatApi((method, path, options) =>
    unwrap(
      apiClient.request<ApiResponse<never>>({
        method,
        url: `/client/chat${path}`,
        data: options?.body,
        params: options?.params,
      }),
    ),
  )
  return createChatClient(api, ablyRealtime(Ably.Realtime, api.realtimeToken))
}
