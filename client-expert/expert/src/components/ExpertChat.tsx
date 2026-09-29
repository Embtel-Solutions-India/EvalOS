import { ConversationView, useChat } from '@evalos/chat'
import { Card } from '@shared/components/ui/card'

/**
 * The case's Expert conversation beside the case (Unit 57 §7): the expert and the case team, never
 * the client. The expert view names its case by reference only, so the conversation is found by
 * case code. The chat client is the shell's (`ExpertLayout`); push opt-in lives on Messages.
 */
export function ExpertChat({ caseReference }: { caseReference: string | null }) {
  return (
    <Card className="flex h-[70vh] flex-col gap-2 p-4 xl:sticky xl:top-8">
      <h2 className="font-semibold text-foreground">Case team</h2>
      <Conversation caseReference={caseReference} />
    </Card>
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
