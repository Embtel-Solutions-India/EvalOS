import { useState } from 'react'
import { ChatProvider, ConversationView, PushCard, useChat } from '@evalos/chat'
import '@evalos/chat/chat.css'
import { Card } from '@shared/components/ui/card'
import { createPortalChat } from '@shared/services/portalChat'

/**
 * The case's Expert conversation beside the case (Unit 57 §7): the expert and the case team, never
 * the client. The expert view names its case by reference only, so the conversation is found by
 * case code. Push opt-in sits above it — this app has no nav to put a Messages page in.
 */
export function ExpertChat({ caseReference }: { caseReference: string | null }) {
  const [client] = useState(() => createPortalChat('expert'))
  return (
    <ChatProvider client={client}>
      <Card className="flex h-[70vh] flex-col gap-2 p-4 lg:sticky lg:top-6">
        <PushCard workerUrl="/sw.js" />
        <Conversation caseReference={caseReference} />
      </Card>
    </ChatProvider>
  )
}

function Conversation({ caseReference }: { caseReference: string | null }) {
  const loaded = useChat((s) => s.inboxLoaded)
  const error = useChat((s) => s.error)
  const id = useChat((s) =>
    s.order.find((c) => s.conversations[c]?.type === 'EXPERT' && s.conversations[c]?.caseCode === caseReference),
  )

  if (error) return <p className="ec-error">{error}</p>
  if (!loaded) return <p className="ec-empty">Loading…</p>
  if (!id) return <p className="ec-empty">The conversation with the case team opens once you are offered this case.</p>
  return <ConversationView conversationId={id} />
}
