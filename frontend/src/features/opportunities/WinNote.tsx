import { useState } from 'react'

/** The note a win needs for the production team (D70). The server refuses a win without it. */
export default function WinNote({
  busy,
  onConfirm,
  onCancel,
}: {
  busy: boolean
  onConfirm: (note: string) => void
  onCancel: () => void
}) {
  const [note, setNote] = useState('')
  return (
    <form
      className="flex flex-col gap-2"
      onSubmit={(event) => {
        event.preventDefault()
        onConfirm(note)
      }}
    >
      <label className="text-xs" style={{ color: 'var(--text-muted)' }}>
        Note for the production team (required — they see it on the case)
        <textarea
          required
          autoFocus
          rows={4}
          maxLength={4000}
          value={note}
          onChange={(event) => setNote(event.target.value)}
          className="field mt-1 w-full"
        />
      </label>
      <div className="flex gap-2">
        <button type="submit" disabled={busy || !note.trim()} className="btn flex-1">
          Mark won
        </button>
        <button type="button" disabled={busy} onClick={onCancel} className="btn">
          Cancel
        </button>
      </div>
    </form>
  )
}
