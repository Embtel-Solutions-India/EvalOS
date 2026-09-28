import { useState } from 'react'
import { ChatInbox, ConversationView, PushCard } from '@evalos/chat'
import { Button } from '@shared/components/ui/button'
import { Card } from '@shared/components/ui/card'
import { PageHeader } from '@shared/components/common/PageHeader'
import { cn } from '@shared/utils/cn'

/**
 * The client's case conversations, grouped by case (Unit 58 §4, Unit 57). Two panes on desktop;
 * on a phone, the inbox or one conversation with Back.
 */
export default function Conversations() {
  const [open, setOpen] = useState<string | null>(null)

  return (
    <div className="mx-auto max-w-6xl space-y-6">
      <PageHeader title="Conversations" description="Talk to the team working on each of your cases." />
      {/* The one place permission is asked — never on load (57 §6). */}
      <PushCard workerUrl="/sw.js" />
      <div className="grid gap-4 lg:grid-cols-[20rem_1fr]">
        <Card className={cn('p-3', open && 'hidden lg:block')}>
          <ChatInbox onOpen={setOpen} selectedId={open ?? undefined} />
        </Card>
        <Card className={cn('flex h-[70vh] flex-col p-4', !open && 'hidden lg:flex')}>
          {open ? (
            <>
              <Button variant="ghost" size="sm" className="mb-2 self-start lg:hidden" onClick={() => setOpen(null)}>
                Back
              </Button>
              <ConversationView conversationId={open} />
            </>
          ) : (
            <p className="text-sm text-muted-foreground">Choose a conversation.</p>
          )}
        </Card>
      </div>
    </div>
  )
}
