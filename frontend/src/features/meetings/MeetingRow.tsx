import { useState } from 'react'
import {
  addMeetingNote,
  cancelMeeting,
  deleteMeetingNote,
  fetchMeetingNotes,
  type DiaryMeeting,
  type MeetingNote,
} from '../opportunities/opportunityApi'

/**
 * One diary row, with the two things a desk does to a booked meeting (Unit 60).
 *
 * <p>**Cancel is GHL's own status change**, so GHL tells the client; the row then shows GHL's
 * status. **Notes are read live from GHL** when opened; EvalOS stores none (spec 60 §1.7).
 */
export function MeetingRow({ meeting, onChanged }: { meeting: DiaryMeeting; onChanged: () => void }) {
  const [open, setOpen] = useState(false)
  const [notes, setNotes] = useState<MeetingNote[] | null>(null)
  const [hasMore, setHasMore] = useState(false)
  const [draft, setDraft] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const cancelled = meeting.status?.toLowerCase() === 'cancelled'
  const { opportunityId, appointmentId } = meeting

  async function run(action: () => Promise<unknown>) {
    setBusy(true)
    setError(null)
    try {
      await action()
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'GHL refused that.')
    } finally {
      setBusy(false)
    }
  }

  const load = (offset = 0) =>
    run(async () => {
      const page = await fetchMeetingNotes(opportunityId, appointmentId, offset)
      setNotes((held) => (offset === 0 ? page.notes : [...(held ?? []), ...page.notes]))
      setHasMore(page.hasMore)
    })

  function toggle() {
    const next = !open
    setOpen(next)
    if (next && notes === null) void load()
  }

  function cancel() {
    if (!window.confirm(`Cancel “${meeting.title}”? GHL will tell the client.`)) return
    void run(async () => {
      await cancelMeeting(opportunityId, appointmentId)
      onChanged()
    })
  }

  function add() {
    const body = draft.trim()
    if (!body) return
    void run(async () => {
      await addMeetingNote(opportunityId, appointmentId, body)
      setDraft('')
      const page = await fetchMeetingNotes(opportunityId, appointmentId, 0)
      setNotes(page.notes)
      setHasMore(page.hasMore)
    })
  }

  function remove(noteId: string) {
    void run(async () => {
      await deleteMeetingNote(opportunityId, appointmentId, noteId)
      setNotes((held) => (held ?? []).filter((n) => n.id !== noteId))
    })
  }

  return (
    <li className="py-1.5">
      <div className="flex flex-wrap items-baseline gap-3">
        <span className="font-num w-28 shrink-0 text-sm tabular-nums">
          {timeOf(meeting.startsAt)}–{timeOf(meeting.endsAt)}
        </span>
        <span className={`min-w-0 flex-1 truncate text-sm ${cancelled ? 'line-through' : ''}`}>
          {meeting.title}
        </span>
        {meeting.status && (
          <span className="text-xs" style={{ color: 'var(--text-muted)' }}>
            {meeting.status}
          </span>
        )}
        <button
          type="button"
          onClick={toggle}
          className="text-xs"
          style={{ color: 'var(--accent-primary)' }}
          aria-expanded={open}
        >
          Notes
        </button>
        {!cancelled && (
          <button
            type="button"
            onClick={cancel}
            disabled={busy}
            className="text-xs disabled:opacity-50"
            style={{ color: 'var(--status-red)' }}
          >
            Cancel
          </button>
        )}
      </div>
      {error && (
        <p className="mt-1 text-xs" style={{ color: 'var(--status-red)' }} role="alert">
          {error}
        </p>
      )}
      {open && (
        <div className="mt-2 grid gap-2 pl-4 sm:pl-32">
          {notes === null ? (
            <p className="text-xs" style={{ color: 'var(--text-muted)' }}>
              Loading notes…
            </p>
          ) : notes.length === 0 ? (
            <p className="text-xs" style={{ color: 'var(--text-muted)' }}>
              No internal notes yet.
            </p>
          ) : (
            <ul className="grid gap-1">
              {notes.map((note) => (
                <li key={note.id} className="flex items-baseline gap-2 text-sm">
                  <span className="min-w-0 flex-1 whitespace-pre-wrap break-words">{note.body}</span>
                  {note.author && (
                    <span className="text-xs" style={{ color: 'var(--text-muted)' }}>
                      {note.author}
                    </span>
                  )}
                  <button
                    type="button"
                    onClick={() => remove(note.id)}
                    disabled={busy}
                    className="text-xs disabled:opacity-50"
                    style={{ color: 'var(--text-muted)' }}
                    aria-label="Delete note"
                  >
                    Delete
                  </button>
                </li>
              ))}
            </ul>
          )}
          {hasMore && (
            <button
              type="button"
              onClick={() => void load(notes?.length ?? 0)}
              className="justify-self-start text-xs"
              style={{ color: 'var(--accent-primary)' }}
            >
              More notes
            </button>
          )}
          <div className="flex flex-wrap gap-2">
            <label className="sr-only" htmlFor={`note-${appointmentId}`}>
              Internal note
            </label>
            <textarea
              id={`note-${appointmentId}`}
              value={draft}
              onChange={(e) => setDraft(e.target.value)}
              maxLength={5000}
              rows={2}
              placeholder="Internal note — the client never sees it"
              className="min-w-0 flex-1 rounded-lg border px-2 py-1 text-sm"
              style={{ borderColor: 'var(--border-subtle)' }}
            />
            <button
              type="button"
              onClick={add}
              disabled={busy || draft.trim() === ''}
              className="self-start rounded-lg px-3 py-1.5 text-sm font-medium disabled:opacity-50"
              style={{ background: 'var(--accent-primary)', color: '#fff' }}
            >
              Add note
            </button>
          </div>
        </div>
      )}
    </li>
  )
}

function timeOf(iso: string): string {
  return new Date(iso).toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' })
}
