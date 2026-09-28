import { useState, type KeyboardEvent } from 'react'
import { MAX_BODY, type Message } from '../core/types'
import { useChatClient } from './ChatProvider'

/** Enter sends, Shift+Enter breaks a line; a reply names its parent; documents go through the app's own flow. */
export function Composer({
  conversationId,
  replyTo,
  onCancelReply,
  onUploadDocument,
}: {
  conversationId: string
  replyTo?: Message | null
  onCancelReply?: () => void
  onUploadDocument?: () => void
}) {
  const client = useChatClient()
  const [body, setBody] = useState('')
  const [sending, setSending] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function send() {
    if (!body.trim() || sending) return
    setSending(true)
    setError(null)
    try {
      await client.send(conversationId, body, replyTo?.id)
      setBody('')
      onCancelReply?.()
    } catch (e) {
      setError(e instanceof Error && e.message.includes('4,000') ? e.message : 'Your message was not sent. Try again.')
    } finally {
      setSending(false)
    }
  }

  function onKeyDown(event: KeyboardEvent<HTMLTextAreaElement>) {
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault()
      void send()
    }
  }

  return (
    <div className="ec-composer">
      {replyTo && (
        <p className="ec-composer__reply">
          Replying to {replyTo.mine ? 'yourself' : (replyTo.authorName ?? 'a message')}
          <button type="button" onClick={onCancelReply} aria-label="Cancel reply">×</button>
        </p>
      )}
      <textarea
        className="ec-composer__input"
        rows={2}
        maxLength={MAX_BODY}
        value={body}
        placeholder="Write a message"
        aria-label="Message"
        onChange={(e) => setBody(e.target.value)}
        onKeyDown={onKeyDown}
      />
      <div className="ec-composer__bar">
        {onUploadDocument && (
          <button type="button" className="ec-link" onClick={onUploadDocument}>
            Upload a document
          </button>
        )}
        <span className="ec-muted">{body.length > MAX_BODY - 200 ? `${body.length}/${MAX_BODY}` : ''}</span>
        <button type="button" className="ec-send" disabled={!body.trim() || sending} onClick={() => void send()}>
          Send
        </button>
      </div>
      {error && <p className="ec-error" role="alert">{error}</p>}
    </div>
  )
}
