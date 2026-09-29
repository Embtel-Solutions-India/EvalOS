import { useState } from 'react'
import { ChatInbox, ConversationView, PushCard } from '@evalos/chat'
import { Button } from '@shared/components/ui/button'
import { Card } from '@shared/components/ui/card'
import { PageHeader } from '@shared/components/common/PageHeader'
import { cn } from '@shared/utils/cn'

/** Every case conversation with the case team (Unit 57), in one inbox. Same shape as the client's. */
export default function Messages() {
  const [open, setOpen] = useState<string | null>(null)

  return (
    <div className="mx-auto max-w-7xl space-y-6">
      <PageHeader title="Messages" description="Your conversations with the team on each case." />
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
