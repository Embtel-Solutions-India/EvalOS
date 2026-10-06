import { useEffect } from 'react'
import { refreshPush } from '../core/push'
import { useChatClient } from './ChatProvider'

/**
 * Renders nothing. On every signed-in load, repairs a push subscription the person already allowed
 * (see {@link refreshPush}); never prompts. A failure is swallowed: this is maintenance, and the
 * Enable card is the visible path.
 */
export function PushRefresh({ workerUrl }: { workerUrl: string }) {
  const { api } = useChatClient()
  useEffect(() => {
    void refreshPush(api, workerUrl).catch(() => undefined)
  }, [api, workerUrl])
  return null
}
