import { useState } from 'react'
import type { CaseDetail } from './caseApi'

/**
 * The PM's guidance to the Case Manager working the draft.
 *
 * Three states, and the middle one is the point: a role that may not read the notes is told the
 * field exists but is not theirs, rather than shown an empty box that looks like nobody has
 * written anything.
 *
 * **Read and write are two separate answers from the server, and both have to be.** The Case
 * Manager reads without writing, so neither flag implies the other; and `pmStrategyNotes` is
 * null both when the server withholds it and when the PM simply has not written it yet, so the
 * value cannot stand in for either flag.
 */
export default function StrategyNotes({
  detail,
  onSave,
}: {
  detail: CaseDetail
  onSave: (notes: string) => Promise<void>
}) {
  const [editing, setEditing] = useState(false)
  const [draft, setDraft] = useState(detail.pmStrategyNotes ?? '')
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)

  // The server's own answer, not a guess. This was inferred from `mayEditStrategyNotes`, which
  // is wrong for the Case Manager — the one role that reads without writing — so every case
  // before the PM wrote anything told a CM the notes were not theirs while naming their own
  // role. A null value cannot distinguish "withheld" from "not written yet"; only this can.
  const withheld = !detail.maySeeStrategyNotes

  async function save() {
    setSaving(true)
    setError(null)
    try {
      await onSave(draft)
      setEditing(false)
    } catch (caught: unknown) {
      setError(caught instanceof Error ? caught.message : 'Could not save the notes')
    } finally {
      setSaving(false)
    }
  }

  return (
    <section
      className="rounded-lg border p-4"
      style={{ background: 'var(--bg-surface)', borderColor: 'var(--border-default)' }}
    >
      <div className="flex items-baseline justify-between gap-2">
        <h2 className="text-sm font-semibold tracking-tight">PM strategy notes</h2>
        {detail.mayEditStrategyNotes && !editing && (
          <button
            type="button"
            onClick={() => {
              setDraft(detail.pmStrategyNotes ?? '')
              setEditing(true)
            }}
            className="text-sm font-medium"
            style={{ color: 'var(--accent-primary)' }}
          >
            Edit
          </button>
        )}
      </div>

      {withheld ? (
        <p className="mt-2 text-sm" style={{ color: 'var(--text-muted)' }}>
          Visible to the project manager and case manager on this case.
        </p>
      ) : editing ? (
        <>
          <textarea
            value={draft}
            rows={5}
            onChange={(event) => setDraft(event.target.value)}
            className="mt-2 w-full rounded-md border px-2.5 py-1.5 text-sm"
            style={{ background: 'var(--bg-base)', borderColor: 'var(--border-default)' }}
          />
          {error && (
            <p className="mt-1 text-xs" style={{ color: 'var(--status-red)' }}>
              {error}
            </p>
          )}
          <div className="mt-2 flex justify-end gap-2">
            <button
              type="button"
              disabled={saving}
              onClick={() => setEditing(false)}
              className="rounded-md px-3 py-1.5 text-sm font-medium disabled:opacity-40"
              style={{ background: 'var(--bg-raised)' }}
            >
              Cancel
            </button>
            <button
              type="button"
              disabled={saving}
              onClick={() => void save()}
              className="rounded-md px-3 py-1.5 text-sm font-medium text-white disabled:opacity-40"
              style={{ background: 'var(--accent-primary)' }}
            >
              {saving ? 'Saving…' : 'Save'}
            </button>
          </div>
        </>
      ) : (
        <p className="mt-2 text-sm whitespace-pre-wrap" style={{ color: 'var(--text-primary)' }}>
          {detail.pmStrategyNotes?.trim() || (
            <span style={{ color: 'var(--text-muted)' }}>No notes yet.</span>
          )}
        </p>
      )}
    </section>
  )
}
