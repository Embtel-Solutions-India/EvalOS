import { useEffect, useRef, useState, type KeyboardEvent } from 'react'
import { MAX_BODY, type Message } from '../core/types'
import { useChatClient } from './ChatProvider'
import { ClipIcon, CloseIcon, SendIcon, SmileIcon } from './icons'

/** What the emoji button offers. Plain characters in the text — chat stays text only (57 §4). */
const EMOJI = ['😀', '😊', '😂', '😉', '😍', '🙂', '🤔', '😅', '😢', '😮', '👍', '👎', '👏', '🙏', '🙌', '💪', '🎉', '✅', '❌', '⭐', '❤️', '🔥', '📄', '📎']

/**
 * Enter sends, Shift+Enter breaks a line; a reply names its parent; the paperclip hands off to the
 * app's own document flow (chat carries no files); the smiley inserts an emoji at the cursor.
 *
 * @param compact the thread's reply box: no paperclip, no emoji
 */
export function Composer({
  conversationId,
  replyTo,
  onCancelReply,
  onUploadDocument,
  compact = false,
}: {
  conversationId: string
  replyTo?: Message | null
  onCancelReply?: () => void
  onUploadDocument?: () => void
  compact?: boolean
}) {
  const client = useChatClient()
  const [body, setBody] = useState('')
  const [sending, setSending] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [emoji, setEmoji] = useState(false)
  const input = useRef<HTMLTextAreaElement>(null)

  // Grow with the text up to the CSS max-height, then scroll inside.
  useEffect(() => {
    const el = input.current
    if (!el) return
    el.style.height = 'auto'
    el.style.height = `${el.scrollHeight}px`
  }, [body])

  useEffect(() => {
    if (replyTo) input.current?.focus()
  }, [replyTo])

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

  function insert(char: string) {
    const el = input.current
    const at = el?.selectionStart ?? body.length
    const end = el?.selectionEnd ?? at
    const next = (body.slice(0, at) + char + body.slice(end)).slice(0, MAX_BODY)
    setBody(next)
    setEmoji(false)
    requestAnimationFrame(() => {
      el?.focus()
      el?.setSelectionRange(at + char.length, at + char.length)
    })
  }

  return (
    <div className={compact ? 'ec-composer ec-composer--compact' : 'ec-composer'}>
      {/* No onCancelReply means there is nothing to cancel to (the thread composer always
          replies to its parent, M2): the banner would show a "×" that does nothing. */}
      {replyTo && onCancelReply && (
        <p className="ec-composer__reply">
          Replying to {replyTo.mine ? 'yourself' : (replyTo.authorName ?? 'a message')}
          <button type="button" className="ec-icon-btn" onClick={onCancelReply} aria-label="Cancel reply">
            <CloseIcon size={14} />
          </button>
        </p>
      )}
      <div className="ec-composer__row">
        {!compact && onUploadDocument && (
          <button type="button" className="ec-icon-btn ec-icon-btn--boxed ec-icon-btn--tall" aria-label="Upload a document" title="Upload a document" onClick={onUploadDocument}>
            <ClipIcon size={18} />
          </button>
        )}
        <div className="ec-composer__field">
          <textarea
            ref={input}
            className="ec-composer__input"
            rows={1}
            maxLength={MAX_BODY}
            value={body}
            placeholder={compact ? 'Write a reply…' : 'Write a message…'}
            aria-label="Message"
            onChange={(e) => {
              setBody(e.target.value)
              client.typing(conversationId)
            }}
            onKeyDown={onKeyDown}
          />
          {!compact && (
            <span className="ec-emoji">
              <button type="button" className="ec-icon-btn" aria-label="Insert an emoji" aria-expanded={emoji} onClick={() => setEmoji(!emoji)}>
                <SmileIcon size={18} />
              </button>
              {emoji && (
                <span className="ec-emoji__grid" role="group" aria-label="Emoji">
                  {EMOJI.map((e) => (
                    <button key={e} type="button" onClick={() => insert(e)}>
                      {e}
                    </button>
                  ))}
                </span>
              )}
            </span>
          )}
        </div>
        <button type="button" className="ec-send" disabled={!body.trim() || sending} onClick={() => void send()}>
          <SendIcon size={16} /> {compact ? 'Reply' : 'Send'}
        </button>
      </div>
      {body.length > MAX_BODY - 200 && <p className="ec-muted ec-count">{body.length}/{MAX_BODY}</p>}
      {error && <p className="ec-error" role="alert">{error}</p>}
    </div>
  )
}
