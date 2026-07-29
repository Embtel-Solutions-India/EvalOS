import { useCallback, useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { useMe } from '../../lib/authContext'
import QuickActionDialog from '../board/QuickActionDialog'
import { performAction } from '../board/boardApi'
import type { QuickAction } from '../board/boardRules'
import DocumentsPanel from './DocumentsPanel'
import DraftPanel from './DraftPanel'
import ExpertCard from './ExpertCard'
import StageActions from './StageActions'
import StrategyNotes from './StrategyNotes'
import Timeline from './Timeline'
import { fetchCase, fetchTimeline, saveStrategyNotes, type CaseDetail, type TimelineEntry } from './caseApi'

/**
 * One case: documents, draft and expert on the left, the append-only timeline on the right,
 * with the stage actions in a sticky header.
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
        <Link to="/board" className="mt-2 inline-block text-sm font-medium" style={{ color: 'var(--accent-primary)' }}>
          Back to the board
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
          <ExpertCard detail={detail} />
          <StrategyNotes detail={detail} onSave={onSaveNotes} />
        </div>

        <Timeline entries={timeline} />
      </div>

      {pending && (
        <QuickActionDialog
          action={pending}
          caseCode={detail.summary.caseCode}
          onCancel={() => setPending(null)}
          onConfirm={(values) => void run(pending, values)}
        />
      )}
    </div>
  )
}
