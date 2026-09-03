import { useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowLeft, MessageSquare, Paperclip, Send } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import { toast } from 'sonner'
import { Button } from '@shared/components/ui/button'
import { Card } from '@shared/components/ui/card'
import { Textarea } from '@shared/components/ui/textarea'
import { EmptyState } from '@shared/components/common/EmptyState'
import { ErrorState } from '@shared/components/common/ErrorState'
import { ListSkeleton } from '@shared/components/common/LoadingState'
import { PageHeader } from '@shared/components/common/PageHeader'
import { listConversations, listMessages, sendMessage } from '@/services/messageService'
import { formatDateTime, relativeTime } from '@shared/utils/formatters'
import { cn } from '@shared/utils/cn'

export default function Messages() {
  const queryClient = useQueryClient()
  const [activeId, setActiveId] = useState<string | null>(null)
  const [draft, setDraft] = useState('')
  const [isSending, setIsSending] = useState(false)
  const scrollRef = useRef<HTMLDivElement>(null)
  const attachInputRef = useRef<HTMLInputElement>(null)

  const conversationsQuery = useQuery({ queryKey: ['conversations'], queryFn: listConversations })
  const messagesQuery = useQuery({
    queryKey: ['messages', activeId],
    queryFn: () => listMessages(activeId as string),
    enabled: Boolean(activeId),
  })

  useEffect(() => {
    if (!activeId && conversationsQuery.data && conversationsQuery.data.length > 0) {
      setActiveId(conversationsQuery.data[0].id)
    }
  }, [activeId, conversationsQuery.data])

  useEffect(() => {
    scrollRef.current?.scrollTo({ top: scrollRef.current.scrollHeight })
  }, [messagesQuery.data])

  async function handleSend() {
    if (!activeId || draft.trim().length === 0) return
    setIsSending(true)
    try {
      await sendMessage(activeId, draft.trim())
      setDraft('')
      await queryClient.invalidateQueries({ queryKey: ['messages', activeId] })
    } finally {
      setIsSending(false)
    }
  }

  const activeConversation = conversationsQuery.data?.find((conversation) => conversation.id === activeId)

  return (
    <div>
      <PageHeader title="Messages" description="Communicate securely with the International Evaluations team." />

      {conversationsQuery.isLoading && <ListSkeleton />}
      {conversationsQuery.isError && (
        <ErrorState description="We couldn't load your conversations." onRetry={() => void conversationsQuery.refetch()} />
      )}

      {!conversationsQuery.isLoading && !conversationsQuery.isError && conversationsQuery.data?.length === 0 && (
        <EmptyState icon={MessageSquare} title="No Messages" description="Your conversations with support will appear here." />
      )}

      {!conversationsQuery.isLoading && conversationsQuery.data && conversationsQuery.data.length > 0 && (
        <Card className="grid grid-cols-1 overflow-hidden p-0 md:grid-cols-[18rem_1fr] md:h-[36rem]">
          <div className={cn('flex-col overflow-y-auto border-b md:border-b-0 md:border-r', activeId ? 'hidden md:flex' : 'flex')}>
            {conversationsQuery.data.map((conversation) => (
              <button
                key={conversation.id}
                type="button"
                onClick={() => setActiveId(conversation.id)}
                className={cn(
                  'flex flex-col gap-1 border-b px-4 py-3 text-left transition-colors last:border-0 hover:bg-muted/40',
                  conversation.id === activeId && 'bg-accent',
                )}
              >
                <div className="flex items-center justify-between gap-2">
                  <p className="truncate text-sm font-medium text-foreground">{conversation.subject}</p>
                  {conversation.unreadCount > 0 && (
                    <span className="h-2 w-2 shrink-0 rounded-full bg-destructive" aria-hidden="true" />
                  )}
                </div>
                <p className="truncate text-xs text-muted-foreground">{conversation.lastMessagePreview}</p>
                <p className="text-[11px] text-muted-foreground">{relativeTime(conversation.lastMessageAt)}</p>
              </button>
            ))}
          </div>

          <div className={cn('flex-col md:flex', activeId ? 'flex' : 'hidden')}>
            <div className="flex items-center gap-2 border-b px-4 py-3">
              <Button variant="ghost" size="icon" className="md:hidden" onClick={() => setActiveId(null)} aria-label="Back to conversations">
                <ArrowLeft className="h-4 w-4" />
              </Button>
              <p className="text-sm font-semibold text-foreground">{activeConversation?.subject}</p>
            </div>

            <div ref={scrollRef} className="flex-1 space-y-4 overflow-y-auto px-4 py-4">
              {messagesQuery.isLoading && <ListSkeleton rows={3} />}
              {messagesQuery.data?.map((message) => (
                <div key={message.id} className={cn('flex', message.sender === 'client' ? 'justify-end' : 'justify-start')}>
                  <div
                    className={cn(
                      'max-w-[80%] rounded-lg px-3.5 py-2.5 text-sm',
                      message.sender === 'client' ? 'bg-primary text-primary-foreground' : 'bg-muted text-foreground',
                    )}
                  >
                    {message.sender !== 'client' && (
                      <p className="mb-0.5 text-xs font-semibold opacity-80">{message.senderName}</p>
                    )}
                    <p>{message.body}</p>
                    <p className={cn('mt-1 text-[10px] opacity-70')}>{formatDateTime(message.createdAt)}</p>
                  </div>
                </div>
              ))}
            </div>

            <div className="flex items-end gap-2 border-t p-3">
              <Button
                type="button"
                variant="outline"
                size="icon"
                aria-label="Attach a file"
                onClick={() => attachInputRef.current?.click()}
              >
                <Paperclip className="h-4 w-4" />
              </Button>
              <input
                ref={attachInputRef}
                type="file"
                className="sr-only"
                onChange={(event) => {
                  const file = event.target.files?.[0]
                  event.target.value = ''
                  if (file) toast.info(`Attaching files is a mock in this development phase. Selected: ${file.name}`)
                }}
              />
              <Textarea
                value={draft}
                onChange={(event) => setDraft(event.target.value)}
                placeholder="Type a message..."
                rows={1}
                className="min-h-10 flex-1 resize-none"
                onKeyDown={(event) => {
                  if (event.key === 'Enter' && !event.shiftKey) {
                    event.preventDefault()
                    void handleSend()
                  }
                }}
              />
              <Button type="button" size="icon" onClick={() => void handleSend()} loading={isSending} aria-label="Send message">
                <Send className="h-4 w-4" />
              </Button>
            </div>
          </div>
        </Card>
      )}
    </div>
  )
}
