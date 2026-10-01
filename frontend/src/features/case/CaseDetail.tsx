import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useCallback, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { useMe } from '../../lib/authContext'
import { boardPathFor } from '../shell/navigation'
import QuickActionDialog from '../board/QuickActionDialog'
import { performAction } from '../board/boardApi'
import { prefill, type BoardCard, type QuickAction } from '../board/boardRules'
import DocumentsPanel from './DocumentsPanel'
import DraftPanel from './DraftPanel'
import ExpertCard from './ExpertCard'
import CaseHeader from './CaseHeader'
import StrategyNotes from './StrategyNotes'
import ExpertRationale from './ExpertRationale'
import SalesNote from './SalesNote'
import CaseFacts from './CaseFacts'
import Timeline from './Timeline'
import CaseChat from './CaseChat'
import {
  fetchCase,
  fetchTimeline,
  postNote,
  saveIntakeFacts,
  saveStrategyNotes,
} from './caseApi'

/**
 * One case: documents, draft and expert on the left, the append-only notes-and-timeline panel on
 * the right, with the stage actions in a sticky header.
 *
 * The dialog and the transition POST are the board's — a transition is the same operation
 * from either screen, so reusing them is what keeps the two surfaces honest with each other.
 * Every action reloads both the case and the timeline, because a transition writes an audit
 * row and the trail is half of what this page is for.
 */

export default function CaseDetailPage() {
  const { id } = useParams<{ id: string }>()
  const me = useMe()

  const way = boardPathFor(me.role)
  const [pending, setPending] = useState<QuickAction | null>(null)
  const [busy, setBusy] = useState(false)
  const [actionError, setActionError] = useState<string | null>(null)

  // Unit 70a: two cached reads under the case's key. Every write already refreshes them (the `api`
  // interceptor), so do the other panels, the board and the bell; `load` re-reads this case now,
  // for the callers that wait on it.
  const queryClient = useQueryClient()
  const detailQuery = useQuery({
    queryKey: ['case', id],
    queryFn: ({ signal }) => fetchCase(id!, signal),
    enabled: !!id,
  })
  const timelineQuery = useQuery({
    queryKey: ['case', id, 'timeline'],
    queryFn: ({ signal }) => fetchTimeline(id!, signal),
    enabled: !!id,
  })
  // `cancelRefetch: false`: the interceptor has usually just started this re-read after a write, so
  // join it rather than cancel it and fetch the case and timeline a second time.
  const load = useCallback(
    () => queryClient.invalidateQueries({ queryKey: ['case', id] }, { cancelRefetch: false }),
    [queryClient, id],
  )

  const run = useCallback(
    async (action: QuickAction, values: Record<string, string>) => {
      if (!id) return
      setPending(null)
      setBusy(true)
      setActionError(null)
      try {
        await performAction(id, action, values)
        await load()
      } catch (error: unknown) {
        // The server's reason, lifted onto the Error by the api interceptor.
        setActionError(error instanceof Error ? error.message : 'That action was refused')
      } finally {
        setBusy(false)
      }
    },
    [id, load],
  )

  const onAction = useCallback(
    (action: QuickAction) => {
      if (action.fields?.length) setPending(action)
      else void run(action, {})
    },
    [run],
  )

  const onPostNote = useCallback(
    async (note: string) => {
      if (!id) return
      // Not caught here: the composer shows the server's reason beside the box it was typed in,
      // which is where the person who has to retype it is looking. `actionError` sits in the
      // sticky header at the top of the page and would be off-screen.
      await postNote(id, note)
      await load()
    },
    [id, load],
  )

  const onSaveFacts = useCallback(
    async (applicantName: string | null, rfeDate: string | null) => {
      if (!id) return
      // Reloaded rather than patched in place, like the notes beside it: the write appends a
      // timeline row too, and a half-refreshed page would show the new fact above an old trail.
      await saveIntakeFacts(id, applicantName, rfeDate)
      await load()
    },
    [id, load],
  )

  const onSaveNotes = useCallback(
    async (notes: string) => {
      if (!id) return
      // The response is the refreshed case, but the edit also appends a timeline row, so the
      // whole page is reloaded rather than patched in place.
      await saveStrategyNotes(id, notes)
      await load()
    },
    [id, load],
  )

  const detail = detailQuery.data
  const timeline = timelineQuery.data
  // Only with nothing to show: a failed background re-read keeps the page it already has.
  const failure = detail && timeline ? null : (detailQuery.error ?? timelineQuery.error)

  if (failure) {
    return (
      <div
        className="rounded-lg border p-4"
        style={{ background: 'var(--status-red-bg)', borderColor: 'var(--border-default)' }}
      >
        <p className="text-sm" style={{ color: 'var(--status-red)' }}>
          {failure instanceof Error ? failure.message : 'Could not load this case'}
        </p>
        {/*
          Routed through the nav table, not hardcoded to `/board`. `/cases/:id` is open to every
          role, so this error state is reachable by a Case Manager (whose board is `/my-cases`)
          and by an Expert Network Manager (who has no board at all) — both of whom got a 403
          from the escape hatch on the failure screen. Found in the browser pass by opening a
          case a Coordinator is not assigned to.
        */}
        <Link
          to={way.path}
          className="mt-2 inline-block text-sm font-medium"
          style={{ color: 'var(--accent-primary)' }}
        >
          {way.label}
        </Link>
      </div>
    )
  }

  if (!detail || !timeline) {
    return (
      <p className="text-sm" style={{ color: 'var(--text-muted)' }}>
        Loading the case…
      </p>
    )
  }

  return (
    <div>
      <CaseHeader
        detail={detail}
        timeline={timeline}
        role={me.role}
        busy={busy}
        error={actionError}
        onAction={onAction}
        onChanged={() => void load()}
      />

      <div className="grid gap-4 lg:grid-cols-[minmax(0,1fr)_minmax(0,1fr)]">
        <div className="flex flex-col gap-4">
          <DocumentsPanel detail={detail} role={me.role} onChanged={() => void load()} />
          <DraftPanel detail={detail} role={me.role} onUploaded={() => void load()} />
          {/* Above the expert and the notes: who the letter is about is what the rest of the
              column is in service of, and it is the one fact the header cannot carry. */}
          <CaseFacts detail={detail} role={me.role} onSave={onSaveFacts} />
          <SalesNote detail={detail} />
          <ExpertCard detail={detail} reloadKey={timeline.length} />
          <StrategyNotes detail={detail} onSave={onSaveNotes} />
          {/*
            Below the notes and separate from them, which is the visible half of the decision to
            give the rationale its own column: a Case Manager sees the notes and not this, an ENM
            sees this and not the notes. Read-only — it is written where the expert is chosen
            (`assign-cm` / `reassign-expert`), not in a ceremony of its own.
          */}
          <ExpertRationale detail={detail} />
        </div>

        <div className="flex flex-col gap-4">
          <CaseChat caseId={detail.summary.id} />
          <Timeline entries={timeline} onPostNote={onPostNote} />
        </div>
      </div>

      {pending && (
        <QuickActionDialog
          action={pending}
          caseId={detail.summary.id}
          caseCode={detail.summary.caseCode}
          initial={prefill(detail.summary as BoardCard, pending)}
          onCancel={() => setPending(null)}
          onConfirm={(values) => void run(pending, values)}
        />
      )}
    </div>
  )
}
