import { useCallback, useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { useMe } from '../../lib/authContext'
import { boardPathFor } from '../shell/navigation'
import QuickActionDialog from '../board/QuickActionDialog'
import { performAction } from '../board/boardApi'
import type { QuickAction } from '../board/boardRules'
import DocumentsPanel from './DocumentsPanel'
import DraftPanel from './DraftPanel'
import ExpertCard from './ExpertCard'
import StageActions from './StageActions'
import StrategyNotes from './StrategyNotes'
import DraftHistory from './DraftHistory'
import ExpertRationale from './ExpertRationale'
import CaseFacts from './CaseFacts'
import Timeline from './Timeline'
import {
  fetchCase,
  fetchTimeline,
  postNote,
  saveIntakeFacts,
  saveStrategyNotes,
  type CaseDetail,
  type TimelineEntry,
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

type LoadState =
  | { status: 'loading' }
  | { status: 'ready'; detail: CaseDetail; timeline: readonly TimelineEntry[] }
  | { status: 'failed'; message: string }

export default function CaseDetailPage() {
  const { id } = useParams<{ id: string }>()
  const me = useMe()

  const way = boardPathFor(me.role)
  const [state, setState] = useState<LoadState>({ status: 'loading' })
  const [pending, setPending] = useState<QuickAction | null>(null)
  const [busy, setBusy] = useState(false)
  const [actionError, setActionError] = useState<string | null>(null)

  const load = useCallback(
    async (signal?: AbortSignal) => {
      if (!id) return
      try {
        // Both reads together: a timeline that lags the case it describes is worse than a
        // slightly slower page.
        const [detail, timeline] = await Promise.all([fetchCase(id, signal), fetchTimeline(id, signal)])
        setState({ status: 'ready', detail, timeline })
      } catch (error: unknown) {
        if (signal?.aborted) return
        setState({
          status: 'failed',
          message: error instanceof Error ? error.message : 'Could not load this case',
        })
      }
    },
    [id],
  )

  useEffect(() => {
    const controller = new AbortController()
    void load(controller.signal)
    return () => controller.abort()
  }, [load])

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

  if (state.status === 'loading') {
    return (
      <p className="text-sm" style={{ color: 'var(--text-muted)' }}>
        Loading the case…
      </p>
    )
  }

  if (state.status === 'failed') {
    return (
      <div
        className="rounded-lg border p-4"
        style={{ background: 'var(--status-red-bg)', borderColor: 'var(--border-default)' }}
      >
        <p className="text-sm" style={{ color: 'var(--status-red)' }}>
          {state.message}
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

  const { detail, timeline } = state

  return (
    <div>
      <StageActions
        detail={detail}
        role={me.role}
        busy={busy}
        error={actionError}
        onAction={onAction}
      />

      <div className="grid gap-4 lg:grid-cols-[minmax(0,1fr)_minmax(0,1fr)]">
        <div className="flex flex-col gap-4">
          <DocumentsPanel detail={detail} />
          <DraftPanel detail={detail} />
          {/* Immediately under the draft's status: the same subject at more depth. */}
          <DraftHistory caseId={detail.summary.id} />
          {/* Above the expert and the notes: who the letter is about is what the rest of the
              column is in service of, and it is the one fact the header cannot carry. */}
          <CaseFacts detail={detail} role={me.role} onSave={onSaveFacts} />
          <ExpertCard detail={detail} />
          <StrategyNotes detail={detail} onSave={onSaveNotes} />
          {/*
            Below the notes and separate from them, which is the visible half of the decision to
            give the rationale its own column: a Case Manager sees the notes and not this, an ENM
            sees this and not the notes. Read-only — it is written where the expert is chosen
            (`assign-cm` / `reassign-expert`), not in a ceremony of its own.
          */}
          <ExpertRationale detail={detail} />
        </div>

        <Timeline entries={timeline} onPostNote={onPostNote} />
      </div>

      {pending && (
        <QuickActionDialog
          action={pending}
          caseId={detail.summary.id}
          caseCode={detail.summary.caseCode}
          onCancel={() => setPending(null)}
          onConfirm={(values) => void run(pending, values)}
        />
      )}
    </div>
  )
}
