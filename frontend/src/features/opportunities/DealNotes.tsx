import { useEffect, useState } from 'react'
import { useMe } from '../../lib/authContext'
import { addNote, deleteNote, editNote, fetchNotes, type Note } from './opportunityApi'

/**
 * The note stream on one deal.
 *
 * **Its author can edit or delete a note** (Unit 54a, 2026-09-24). Only on their own EvalOS notes:
 * the server refuses anyone else, so the controls are drawn only where they will work — a button
 * that 403s is worse than none. A GHL note has neither; it is changed in GHL. An edit overwrites the
 * text and a delete removes it for good, in EvalOS and then in GHL.
 *
 * **Both sides' notes, in one list** (Unit 54, 2026-09-24). A note written here is sent to the
 * deal's GHL contact within a couple of minutes; a note written in GHL shows here once the mirror
 * has read it. Each says where it came from and who wrote it, because a GHL note belongs to the
 * *contact* — one on the contact rather than this deal shows on every deal that person has, and is
 * labelled so rather than passed off as being about this one.
 */
export default function DealNotes({ opportunityId }: { opportunityId: string }) {
  const me = useMe()
  const [notes, setNotes] = useState<readonly Note[] | null>(null)
  const [draft, setDraft] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  // One note in edit at a time, and one confirm-before-delete at a time.
  const [editing, setEditing] = useState<{ id: string; body: string } | null>(null)
  const [confirming, setConfirming] = useState<string | null>(null)

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

  async function run(action: () => Promise<void>, fallback: string) {
    if (busy) return
    setBusy(true)
    setError(null)
    try {
      await action()
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : fallback)
    } finally {
      setBusy(false)
    }
  }

  function submit(event: React.FormEvent) {
    event.preventDefault()
    if (draft.trim() === '') return
    void run(async () => {
      const saved = await addNote(opportunityId, draft.trim())
      // Prepended rather than refetched: the server returned the row it wrote, so this is what
      // was actually saved and not an optimistic guess at it.
      setNotes((current) => [saved, ...(current ?? [])])
      setDraft('')
    }, 'Could not add the note')
  }

  function saveEdit(event: React.FormEvent) {
    event.preventDefault()
    if (!editing || editing.body.trim() === '') return
    const { id, body } = editing
    void run(async () => {
      const saved = await editNote(opportunityId, id, body.trim())
      setNotes((current) => (current ?? []).map((note) => (note.id === id ? saved : note)))
      setEditing(null)
    }, 'Could not save the edit')
  }

  function remove(id: string) {
    void run(async () => {
      await deleteNote(opportunityId, id)
      setNotes((current) => (current ?? []).filter((note) => note.id !== id))
      setConfirming(null)
    }, 'Could not delete the note')
  }

  return (
    <div className="space-y-3">
      <form onSubmit={submit} className="space-y-2">
        <textarea
          value={draft}
          onChange={(event) => setDraft(event.target.value)}
          rows={2}
          placeholder="Add a note…"
          className="field w-full"
        />
        <button type="submit" disabled={busy || draft.trim() === ''} className="btn btn-primary">
          {busy ? 'Saving…' : 'Add note'}
        </button>
      </form>

      {error && (
        <p className="text-xs" style={{ color: 'var(--status-red)' }}>
          {error}
        </p>
      )}

      {notes === null && (
        <p className="text-xs" style={{ color: 'var(--text-muted)' }}>
          Loading notes…
        </p>
      )}
      {notes?.length === 0 && (
        <p className="text-xs" style={{ color: 'var(--text-muted)' }}>
          No notes yet.
        </p>
      )}

      {/* A left rule rather than a filled block: a stream of tinted boxes on a white panel is
          the striping `tokens.css` warns about, and the accent edge reads as one conversation
          instead of a stack of unrelated cards. */}
      <ul className="space-y-2">
        {notes?.map((note) => {
          const mine = note.origin === 'EVALOS' && note.authorId === me.id
          return (
            <li
              key={note.id}
              className="border-l-2 py-0.5 pl-3 text-xs"
              style={{ borderColor: 'var(--accent-soft)' }}
            >
              {editing?.id === note.id ? (
                <form onSubmit={saveEdit} className="space-y-1.5">
                  <textarea
                    value={editing.body}
                    onChange={(event) => setEditing({ id: note.id, body: event.target.value })}
                    rows={3}
                    className="field w-full"
                    aria-label="Edit note"
                    autoFocus
                  />
                  <div className="flex gap-2">
                    <button type="submit" disabled={busy || editing.body.trim() === ''} className="btn btn-primary">
                      Save
                    </button>
                    <button type="button" onClick={() => setEditing(null)} className="btn">
                      Cancel
                    </button>
                  </div>
                </form>
              ) : (
                <>
                  {note.title && <p className="font-medium">{note.title}</p>}
                  <p className="whitespace-pre-wrap">{note.body}</p>
                </>
              )}
              <p
                className="mt-1 flex flex-wrap items-center gap-x-2 text-[11px]"
                style={{ color: 'var(--text-muted)' }}
              >
                <span
                  className="rounded px-1.5 py-px text-[10px] font-medium"
                  style={{
                    background: note.origin === 'GHL' ? 'var(--bg-raised)' : 'var(--accent-soft)',
                    color: note.origin === 'GHL' ? 'var(--text-muted)' : 'var(--accent-primary)',
                  }}
                >
                  {note.origin === 'GHL' ? (note.onContact ? 'GHL · on the contact' : 'GHL') : 'EvalOS'}
                </span>
                {note.authorName && <span>{note.authorName}</span>}
                <span>{note.createdAt ? new Date(note.createdAt).toLocaleString() : 'just now'}</span>
                {note.updatedAt && <span>· edited</span>}
                {/* Said, not hidden: a note still in the queue is not in GHL yet, and a salesperson
                    who opens GHL straight after writing one should know why it is not there. */}
                {note.origin === 'EVALOS' && !note.inGhl && <span>· not in GHL yet</span>}
                {mine && editing?.id !== note.id && (
                  <span className="ml-auto flex gap-2">
                    {confirming === note.id ? (
                      <>
                        {/* Inline, not `window.confirm`: a native dialog blocks the page, and a
                            delete that cannot be undone deserves a second click in place. */}
                        <span style={{ color: 'var(--status-red)' }}>Delete for good?</span>
                        <button
                          type="button"
                          disabled={busy}
                          onClick={() => remove(note.id)}
                          className="font-medium underline"
                          style={{ color: 'var(--status-red)' }}
                        >
                          Delete
                        </button>
                        <button type="button" onClick={() => setConfirming(null)} className="underline">
                          Keep
                        </button>
                      </>
                    ) : (
                      <>
                        <button
                          type="button"
                          onClick={() => {
                            setConfirming(null)
                            setEditing({ id: note.id, body: note.body })
                          }}
                          className="underline"
                        >
                          Edit
                        </button>
                        <button type="button" onClick={() => setConfirming(note.id)} className="underline">
                          Delete
                        </button>
                      </>
                    )}
                  </span>
                )}
              </p>
            </li>
          )
        })}
      </ul>
    </div>
  )
}
