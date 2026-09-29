import { useState } from 'react'
import { MAX_BODY, type Message } from '../core/types'
import { useChatClient } from './ChatProvider'

export type OwnMode = 'idle' | 'editing' | 'confirming'

/**
 * Edit inline, or confirm a delete — on your own message only; the server refuses anyone else's
 * (Unit 57 §4). The message's "⋮" chooses the mode; this renders it and calls `onDone` after.
 */
export function OwnMessageActions({ message, mode, onDone }: { message: Message; mode: OwnMode; onDone(): void }) {
  const client = useChatClient()
  const [draft, setDraft] = useState(message.body)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function run(action: () => Promise<void>) {
    setBusy(true)
    setError(null)
    try {
      await action()
      onDone()
    } catch (e) {
      setError(e instanceof Error && e.message.startsWith('A message') ? e.message : 'That did not work. Try again.')
    } finally {
      setBusy(false)
    }
  }

  if (mode === 'editing') {
    return (
      <div className="ec-edit">
        <textarea
          className="ec-edit__input"
          rows={2}
          maxLength={MAX_BODY}
          value={draft}
          aria-label="Edit message"
          autoFocus
          onChange={(e) => setDraft(e.target.value)}
        />
        <div className="ec-actions">
          <button type="button" className="ec-link" disabled={busy || !draft.trim()} onClick={() => void run(() => client.edit(message, draft))}>
            Save
          </button>
          <button type="button" className="ec-link" disabled={busy} onClick={onDone}>
            Cancel
          </button>
        </div>
        {error && <p className="ec-error" role="alert">{error}</p>}
      </div>
    )
  }

  if (mode === 'confirming') {
    return (
      <div className="ec-actions">
        <span className="ec-muted">Delete this message?</span>
        <button type="button" className="ec-link ec-link--danger" disabled={busy} onClick={() => void run(() => client.remove(message))}>
          Delete
        </button>
        <button type="button" className="ec-link" disabled={busy} onClick={onDone}>
          Keep
        </button>
        {error && <span className="ec-error" role="alert"> {error}</span>}
      </div>
    )
  }

  return null
}
