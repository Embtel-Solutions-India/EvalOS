import { useState } from 'react'
import type { TimelineEntry } from './caseApi'

/**
 * The append-only trail, read forwards — every transition, and every note anybody left.
 *
 * **Notes and history are one panel on purpose (Unit 23).** A note is almost always *about* the
 * transition beside it — "returned the draft because the dates are wrong", "chased again, no
 * answer" — so a separate notes tab would put the sentence on one screen and the event it
 * explains on another, and leave the reader merging two orderings in their head. A note is
 * written as an audit row (`NOTE_ADDED`), which is what lets them interleave at all.
 *
 * **Still no edit control, now including on notes.** The trail is the record of what happened
 * (invariant 13) and `AuditEventRepository` has no method that could change it. Somebody who
 * writes the wrong thing writes a correction underneath, and both stay — which is the property
 * that makes "what were we told, and when" answerable at all.
 */

const ACTION_LABEL: Record<string, string> = {
  CREATED: 'created',
  UPDATED: 'updated',
  ASSIGNED: 'assigned',
  STAGE_CHANGED: 'moved stage',
  CHASED: 'chased the client',
  DELETED: 'deleted',
  EXPORTED: 'exported',
  PORTAL_LINK_ISSUED: 'sent a portal link',
  FLAGGED: 'flagged this case to the PM',
  PERFORMANCE_FLAGGED: 'recorded a performance concern',
  CLIENT_REVISION_REQUESTED: 'client asked for changes',
  NOTE_ADDED: 'left a note',
  LOGIN: 'signed in',
}

/**
 * The entries whose whole content is the text — drawn with the note as the body rather than as
 * an aside under a state change, because for these there is no state change to be an aside to.
 */
const IS_NOTE: Record<string, true> = { NOTE_ADDED: true, FLAGGED: true }

function readable(value: string | null): string | null {
  return value ? value.replaceAll('_', ' ').toLowerCase() : null
}

export default function Timeline({
  entries,
  onPostNote,
}: {
  entries: readonly TimelineEntry[]
  onPostNote: (note: string) => Promise<void>
}) {
  return (
    <section
      className="flex flex-col rounded-lg border"
      style={{ background: 'var(--bg-surface)', borderColor: 'var(--border-default)' }}
    >
      <header className="border-b px-4 py-3" style={{ borderColor: 'var(--border-default)' }}>
        <h2 className="text-sm font-semibold tracking-tight">Notes &amp; timeline</h2>
        <p className="mt-0.5 text-xs" style={{ color: 'var(--text-muted)' }}>
          Every change and every note, oldest first. Append-only — nothing here can be edited.
        </p>
      </header>

      {entries.length === 0 ? (
        <p className="px-4 py-6 text-sm" style={{ color: 'var(--text-muted)' }}>
          Nothing recorded yet.
        </p>
      ) : (
        <ol className="max-h-[32rem] overflow-y-auto">
          {entries.map((entry, index) => {
            const isNote = IS_NOTE[entry.action] === true
            return (
              <li
                key={`${entry.at}-${index}`}
                className="border-b px-4 py-3 last:border-b-0"
                style={{
                  borderColor: 'var(--border-default)',
                  // A note is somebody talking, so it gets a left rule to sit behind — the one
                  // visual difference between "the system recorded this" and "a person said this".
                  borderLeft: isNote ? '2px solid var(--accent-primary)' : undefined,
                }}
              >
                <div className="flex items-baseline justify-between gap-3">
                  <span className="text-sm font-medium">
                    {entry.actorName} {ACTION_LABEL[entry.action] ?? entry.action.toLowerCase()}
                  </span>
                  <time
                    className="font-num shrink-0 text-xs tabular-nums"
                    dateTime={entry.at}
                    style={{ color: 'var(--text-muted)' }}
                  >
                    {new Date(entry.at).toLocaleString()}
                  </time>
                </div>

                {/* Suppressed on a note: a note changes no state, so repeating the stage it was
                    written in adds a line that is the same on every note in a row. */}
                {!isNote && (entry.stage || entry.exceptionState) && (
                  <p className="mt-0.5 text-xs" style={{ color: 'var(--text-muted)' }}>
                    {readable(entry.stage)}
                    {entry.exceptionState && entry.exceptionState !== 'NONE'
                      ? ` · ${readable(entry.exceptionState)}`
                      : ''}
                  </p>
                )}

                {/* The reason somebody typed is the point of the entry, not decoration. Whitespace
                    is preserved so a note written as two paragraphs reads as two paragraphs. */}
                {entry.note && (
                  <p
                    className="mt-1 text-sm whitespace-pre-wrap"
                    style={{ color: 'var(--text-primary)' }}
                  >
                    {isNote ? entry.note : `“${entry.note}”`}
                  </p>
                )}
              </li>
            )
          })}
        </ol>
      )}

      <NoteComposer onPostNote={onPostNote} />
    </section>
  )
}

/**
 * Write a note for whoever picks the case up next.
 *
 * **Rendered for every role, with no client-side permission check.** The server's gate is the
 * case scope — if you could load this page you may write on it — so a role list here would be a
 * second copy of that scope, and the copy is what goes stale. A caller the scope refuses never
 * reaches this component, because the page above it failed first.
 */
function NoteComposer({ onPostNote }: { onPostNote: (note: string) => Promise<void> }) {
  const [text, setText] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const empty = text.trim().length === 0

  async function submit(event: React.FormEvent) {
    event.preventDefault()
    if (empty || busy) return
    setBusy(true)
    setError(null)
    try {
      await onPostNote(text.trim())
      // Cleared only after the server took it. Clearing optimistically loses what somebody
      // typed when the post fails, which on a permanent record is the wrong way to be wrong.
      setText('')
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'That note was not saved')
    } finally {
      setBusy(false)
    }
  }

  return (
    <form
      onSubmit={submit}
      className="border-t px-4 py-3"
      style={{ borderColor: 'var(--border-default)' }}
    >
      <label htmlFor="case-note" className="sr-only">
        Write a note on this case
      </label>
      <textarea
        id="case-note"
        rows={2}
        value={text}
        disabled={busy}
        onChange={(event) => setText(event.target.value)}
        placeholder="Write a note for whoever works this next…"
        className="w-full resize-y rounded-md px-3 py-2 text-sm"
        style={{
          background: 'var(--bg-raised)',
          border: '1px solid var(--border-default)',
          color: 'var(--text-primary)',
        }}
      />

      <div className="mt-2 flex items-center justify-between gap-3">
        <p className="text-xs" style={{ color: 'var(--text-muted)' }}>
          Everyone on this case can read this. It cannot be edited or deleted.
        </p>
        <button
          type="submit"
          disabled={empty || busy}
          className="h-9 shrink-0 px-3 text-sm font-medium disabled:opacity-45"
          style={{
            borderRadius: 'var(--radius-md)',
            background: 'var(--accent-primary)',
            color: '#fff',
          }}
        >
          {busy ? 'Posting…' : 'Post note'}
        </button>
      </div>

      {error && (
        <p className="mt-2 text-sm" style={{ color: 'var(--status-red)' }}>
          {error}
        </p>
      )}
    </form>
  )
}
