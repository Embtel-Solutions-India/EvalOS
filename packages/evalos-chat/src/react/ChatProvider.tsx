import { createContext, useContext, useEffect, useSyncExternalStore, type ReactNode } from 'react'
import type { InboxParams } from '../core/api'
import type { ChatClient } from '../core/client'
import type { ChatState } from '../core/reducer'

const ChatContext = createContext<ChatClient | null>(null)

/** Starts the chat client for the subtree and stops it on unmount. Create the client once, outside render. */
export function ChatProvider({ client, inbox, children }: { client: ChatClient; inbox?: InboxParams; children: ReactNode }) {
  useEffect(() => {
    void client.start(inbox)
    return () => client.stop()
    // The inbox filter is fixed for a provider's life; a new filter is a new provider.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [client])
  return <ChatContext.Provider value={client}>{children}</ChatContext.Provider>
}

export function useChatClient(): ChatClient {
  const client = useContext(ChatContext)
  if (!client) throw new Error('useChatClient needs a <ChatProvider> above it')
  return client
}

/** A slice of chat state. `select` must return state it was given (not a new object), or React re-renders forever. */
export function useChat<T>(select: (state: ChatState) => T): T {
  const client = useChatClient()
  return useSyncExternalStore(client.subscribe, () => select(client.getState()))
}
