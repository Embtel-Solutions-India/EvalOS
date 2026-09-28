import { useState } from 'react'
import { MAX_BODY, type Message } from '../core/types'
import { useChatClient } from './ChatProvider'

/** Edit and delete, on your own message only; the server refuses anyone else's (Unit 57 §4). */
export function OwnMessageActions({ message }: { message: Message }) {
  const client = useChatClient()
  const [mode, setMode] = useState<'idle' | 'editing' | 'confirming'>('idle')
  const [draft, setDraft] = useState(message.body)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function run(action: () => Promise<void>) {
    setBusy(true)
    setError(null)
    try {
      await action()
      setMode('idle')
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
          className="ec-composer__input"
          rows={2}
          maxLength={MAX_BODY}
          value={draft}
          aria-label="Edit message"
          onChange={(e) => setDraft(e.target.value)}
        />
        <div className="ec-actions">
          <button type="button" className="ec-link" disabled={busy || !draft.trim()} onClick={() => void run(() => client.edit(message, draft))}>
            Save
          </button>
          <button type="button" className="ec-link" disabled={busy} onClick={() => setMode('idle')}>
            Cancel
          </button>
        </div>
        {error && <p className="ec-error" role="alert">{error}</p>}
      </div>
    )
  }

  return (
    <div className="ec-actions">
      {mode === 'confirming' ? (
        <>
          <span className="ec-muted">Delete this message?</span>
          <button type="button" className="ec-link" disabled={busy} onClick={() => void run(() => client.remove(message))}>
            Delete
          </button>
          <button type="button" className="ec-link" disabled={busy} onClick={() => setMode('idle')}>
            Keep
          </button>
        </>
      ) : (
        <>
          <button
            type="button"
            className="ec-link"
            onClick={() => {
              setDraft(message.body)
              setMode('editing')
            }}
          >
            Edit
          </button>
          <button type="button" className="ec-link" onClick={() => setMode('confirming')}>
            Delete
          </button>
        </>
      )}
      {error && <span className="ec-error" role="alert"> {error}</span>}
    </div>
  )
}
