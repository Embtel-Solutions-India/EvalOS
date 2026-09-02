import { useCallback, useEffect, useState } from 'react'
import {
  addChecklistItem,
  fetchChecklist,
  markDocsComplete,
  sendChase,
  setItemStatus,
} from './checklistApi'
import {
  ITEM_STATUSES,
  STATUS_META,
  agingHours,
  agingLabel,
  type ChecklistItemStatus,
  type ChecklistView,
} from './checklistRules'

/**
 * One case's documents: what is required, where each one stands, and the two things the
 * Coordinator can do about it.
 *
 * **There is no upload control, and that is not an omission.** Documents are collected in
 * Google Drive and EvalOS hosts no files (invariant 14) — the Drive link is the way to the
 * actual documents and this panel tracks only whether each one has arrived and passed review.
 *
 * Every write returns the whole refreshed checklist, so the panel replaces its state from the
 * server rather than patching a row: adding an item changes `checklistSatisfied`, and so can
 * a status change, and recomputing that here would be a second copy of the server's answer.
 */

type Busy = 'idle' | 'saving' | 'chasing' | 'completing'

export default function CaseChecklist({
  caseId,
  onChecklistChanged,
  onCaseLeftTheStage,
}: {
  caseId: string
  /**
   * Every write here changes something the board draws from its own copy of the case, and the
   * board owns that copy — so the refreshed checklist is handed up rather than kept here.
   *
   * Reported from `run`, so all three writes report it rather than the chase alone: a status
   * change left the row's fraction stale and a finished case sitting under "Due a chase" until
   * the next full reload, which is the nagging the 24-hour rule exists to stop.
   */
  onChecklistChanged: (view: ChecklistView) => void
  /** Docs complete moves the case to the PM, so the board above has to re-read. */
  onCaseLeftTheStage: () => void
}) {
  const [view, setView] = useState<ChecklistView | null>(null)
  const [busy, setBusy] = useState<Busy>('idle')
  const [error, setError] = useState<string | null>(null)
  const [newLabel, setNewLabel] = useState('')

  useEffect(() => {
    const controller = new AbortController()
    fetchChecklist(caseId, controller.signal)
      .then(setView)
      .catch((cause: unknown) => {
        if (controller.signal.aborted) return
        setError(cause instanceof Error ? cause.message : 'Could not load the checklist')
      })
    return () => controller.abort()
  }, [caseId])

  /**
   * One place every write goes through, so none of them can forget to clear the last error, to
   * leave the panel disabled if the server refuses, or to tell the board what changed. A
   * refusal leaves the checklist exactly as the last successful read had it — nothing is
   * optimistically moved, and the board is told nothing.
   */
  const run = useCallback(
    async (state: Busy, write: () => Promise<ChecklistView>) => {
      setBusy(state)
      setError(null)
      try {
        const refreshed = await write()
        setView(refreshed)
        onChecklistChanged(refreshed)
      } catch (cause: unknown) {
        setError(cause instanceof Error ? cause.message : 'That change was refused')
      } finally {
        setBusy('idle')
      }
    },
    [onChecklistChanged],
  )

  const onChase = useCallback(async () => {
    await run('chasing', () => sendChase(caseId))
  }, [caseId, run])

  const onAdd = useCallback(async () => {
    const label = newLabel.trim()
    if (!label) return
    await run('saving', async () => {
      const refreshed = await addChecklistItem(caseId, label)
      setNewLabel('')
      return refreshed
    })
  }, [caseId, newLabel, run])

  const onComplete = useCallback(async () => {
    setBusy('completing')
    setError(null)
    try {
      await markDocsComplete(caseId)
      onCaseLeftTheStage()
    } catch (cause: unknown) {
      // The server's reason — "the case has not been paid", "no project manager is assigned
      // yet" — which is exactly why this button is not disabled on those two conditions.
      setError(cause instanceof Error ? cause.message : 'That case could not be completed')
      setBusy('idle')
    }
  }, [caseId, onCaseLeftTheStage])

  if (error && !view) {
    return (
      <p className="p-3 text-sm" style={{ color: 'var(--status-red)' }}>
        {error} Nothing was changed.
      </p>
    )
  }

  if (!view) {
    return (
      <p className="p-3 text-sm" style={{ color: 'var(--text-muted)' }}>
        Loading the checklist…
      </p>
    )
  }

  const working = busy !== 'idle'
  const sinceChase = agingHours(view.lastChasedAt)

  return (
    <div className="flex flex-col gap-3 p-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        {/*
          **No folder link any more (Unit 30).** There is no folder: a client's documents are S3
          objects, opened one at a time through a URL minted at the click. The per-item status
          below is what says whether a document arrived, which is the question this board is for —
          the old link answered "where would it be" rather than "did it come".
        */}
        <span className="text-sm font-medium">Documents</span>
        <span className="font-num text-xs tabular-nums" style={{ color: 'var(--text-muted)' }}>
          {view.lastChasedAt ? `Last chased ${agingLabel(sinceChase)} ago` : 'Never chased'}
        </span>
      </div>

      <ul className="flex flex-col gap-1">
        {view.items.map((item) => (
          <li
            key={item.id}
            className="flex flex-wrap items-center justify-between gap-2 rounded-md px-2 py-1.5"
            style={{ background: 'var(--bg-base)' }}
          >
            <span className="min-w-0 flex-1 truncate text-sm">{item.label}</span>
            <span
              className="rounded-md px-1.5 py-0.5 text-[11px] font-semibold"
              style={{ color: STATUS_META[item.status].fg, background: STATUS_META[item.status].bg }}
            >
              {STATUS_META[item.status].label}
            </span>
            <label className="flex items-center gap-1 text-xs">
              <span className="sr-only">Status of {item.label}</span>
              <select
                value={item.status}
                disabled={working}
                onChange={(event) =>
                  void run('saving', () =>
                    setItemStatus(caseId, item.id, event.target.value as ChecklistItemStatus),
                  )
                }
                className="rounded-md border px-1.5 py-1 text-xs disabled:opacity-40"
                style={{ background: 'var(--bg-surface)', borderColor: 'var(--border-default)' }}
              >
                {ITEM_STATUSES.map((status) => (
                  <option key={status} value={status}>
                    {STATUS_META[status].label}
                  </option>
                ))}
              </select>
            </label>
          </li>
        ))}
        {view.items.length === 0 && (
          <li className="text-sm" style={{ color: 'var(--text-muted)' }}>
            This case has no checklist at all, which the intake template should have seeded. Add
            what it needs below — docs complete will refuse an empty checklist.
          </li>
        )}
      </ul>

      <div className="flex flex-wrap items-center gap-2">
        <input
          value={newLabel}
          disabled={working}
          onChange={(event) => setNewLabel(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === 'Enter') void onAdd()
          }}
          placeholder="Add a required document"
          maxLength={200}
          aria-label="Add a required document"
          className="min-w-52 flex-1 rounded-md border px-2 py-1.5 text-sm"
          style={{ background: 'var(--bg-surface)', borderColor: 'var(--border-default)' }}
        />
        <button
          type="button"
          disabled={working || !newLabel.trim()}
          onClick={() => void onAdd()}
          className="rounded-md bg-(--bg-raised) px-2.5 py-1.5 text-sm font-medium text-(--accent-primary) transition-colors enabled:hover:bg-(--accent-primary) enabled:hover:text-white disabled:opacity-40"
        >
          Add
        </button>
      </div>

      {error && (
        <p className="text-sm" style={{ color: 'var(--status-red)' }}>
          {error}
        </p>
      )}

      <div className="flex flex-wrap items-center gap-2 border-t pt-3" style={{ borderColor: 'var(--border-default)' }}>
        <button
          type="button"
          disabled={working}
          onClick={() => void onChase()}
          className="rounded-md bg-(--bg-raised) px-2.5 py-1.5 text-sm font-medium text-(--accent-primary) transition-colors enabled:hover:bg-(--accent-primary) enabled:hover:text-white disabled:opacity-40"
        >
          {busy === 'chasing' ? 'Sending…' : 'Send document chase'}
        </button>
        {/*
          Enabled on the checklist alone. The transition also requires the case to be paid and
          to have a PM, and this screen deliberately does not restate those: they are Unit 04's
          rule, the server answers 409 naming whichever one failed, and the reason is shown
          above. A button disabled for a reason the user cannot see is worse than one that
          explains itself when pressed.
        */}
        <button
          type="button"
          disabled={working || !view.checklistSatisfied}
          onClick={() => void onComplete()}
          title={
            view.checklistSatisfied
              ? 'Send this case to the project manager'
              : 'Every document has to be uploaded or approved first'
          }
          className="rounded-md bg-(--accent-primary) px-2.5 py-1.5 text-sm font-medium text-white transition-colors enabled:hover:bg-(--accent-hover) disabled:opacity-40"
        >
          {busy === 'completing' ? 'Sending…' : 'Mark docs complete'}
        </button>
        <span className="font-num text-xs tabular-nums" style={{ color: 'var(--text-muted)' }}>
          {view.complete} of {view.total} in
        </span>
      </div>
    </div>
  )
}
