import { useEffect, useState } from 'react'
import { addNote, fetchNotes, type Note } from './opportunityApi'

/**
 * The note stream on one deal.
 *
 * **Append-only, and the UI says so by having nowhere to edit.** There is no pencil and no bin,
 * because there is no route and no database path either — a correction is a new note. This is
 * the client conversation rather than a record of it.
 *
 * **Notes are EvalOS's, unlike everything else on this screen.** GHL has no opportunity notes:
 * its only note endpoints hang off the *contact*, so a repeat client's two deals would share one
 * stream. The cost is that a note written in GHL never appears here — acceptable only because
 * the whole point of this desk is that nobody opens GHL.
 */
export default function DealNotes({ opportunityId }: { opportunityId: string }) {
  const [notes, setNotes] = useState<readonly Note[] | null>(null)
  const [draft, setDraft] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    const controller = new AbortController()
    fetchNotes(opportunityId, controller.signal)
      .then(setNotes)
      .catch((failure) => {
        // An aborted request is the effect cleaning up after itself, not a failure to report.
        if (!controller.signal.aborted) {
          setError(failure instanceof Error ? failure.message : 'Could not load notes')
        }
      })
    return () => controller.abort()
  }, [opportunityId])

  async function submit(event: React.FormEvent) {
    event.preventDefault()
    if (busy || draft.trim() === '') return
    setBusy(true)
    setError(null)
    try {
      const saved = await addNote(opportunityId, draft.trim())
      // Prepended rather than refetched: the server returned the row it wrote, so this is what
      // was actually saved and not an optimistic guess at it.
      setNotes((current) => [saved, ...(current ?? [])])
      setDraft('')
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : 'Could not add the note')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="space-y-2 border-t border-slate-200 pt-2">
      <form onSubmit={submit} className="space-y-1">
        <textarea
          value={draft}
          onChange={(event) => setDraft(event.target.value)}
          rows={2}
          placeholder="Add a note…"
          className="w-full rounded border border-slate-300 px-2 py-1 text-xs text-slate-900"
        />
        <button
          type="submit"
          disabled={busy || draft.trim() === ''}
          className="rounded bg-slate-900 px-2 py-1 text-xs text-white disabled:opacity-40"
        >
          {busy ? 'Saving…' : 'Add note'}
        </button>
      </form>

      {error && <p className="text-xs text-rose-600">{error}</p>}

      {notes === null && <p className="text-xs text-slate-400">Loading notes…</p>}
      {notes?.length === 0 && <p className="text-xs text-slate-400">No notes yet.</p>}

      <ul className="space-y-1">
        {notes?.map((note) => (
          <li key={note.id} className="rounded bg-slate-50 p-2 text-xs text-slate-700">
            <p className="whitespace-pre-wrap">{note.body}</p>
            <p className="mt-1 text-[11px] text-slate-400">
              {new Date(note.createdAt).toLocaleString()}
            </p>
          </li>
        ))}
      </ul>
    </div>
  )
}
