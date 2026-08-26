import { useState } from 'react'
import { Link } from 'react-router-dom'
import { ChevronDown } from 'lucide-react'
import { PopoverContent, PopoverRoot, PopoverTrigger } from '../../components/ui/menu'
import { CapacityBar } from '../../components/ui/card'
import { api, unwrap } from '../../lib/api'
import { useMe } from '../../lib/authContext'
import type { BoardCard } from '../board/boardRules'
import { fetchPmMetrics, type CmWorkload } from '../dashboards/pmMetricsApi'
import { useFilters } from '../shell/filtersContext'

/**
 * The staffing control for one inbox row. Which control that is depends on how far the case has
 * got, and the three states are the three steps of taking a case on:
 *
 * 1. **In the pool** — nobody owns it. One button, *Take this case*, which posts `assign-pm`
 *    with the caller's own id. That is what stamps `team_id` and opens the case to their team.
 * 2. **Taken, no Case Manager** — a link to the case page. Not a gap: `assign-cm` assigns the
 *    Case Manager *and* the expert in one call, because it advances the stage and writes the
 *    expert offer together. A popover collecting only a name would be refused with a 409.
 * 3. **Staffed** — the reassignment popover, with each candidate's current load in view.
 */
export default function AssignPopover({ card, onAssigned }: { card: BoardCard; onAssigned: () => void }) {
  const me = useMe()
  const { activeBrandId } = useFilters()
  const [workload, setWorkload] = useState<CmWorkload[] | null>(null)
  const [busy, setBusy] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  if (card.poolStatus === 'IN_POOL') {
    return <TakeCase card={card} pmId={me.id} onAssigned={onAssigned} />
  }

  if (card.assignedCm === null) {
    return (
      <Link to={`/cases/${card.id}`} className="text-sm" style={{ color: 'var(--accent-primary)' }}>
        Assign on the case
      </Link>
    )
  }

  const current = workload?.find((row) => row.cmId === card.assignedCm)

  async function reassign(cmId: string) {
    setBusy(cmId)
    setError(null)
    try {
      await unwrap(api.patch(`/cases/${card.id}/case-manager`, { cmId }))
      onAssigned()
    } catch (cause) {
      setError((cause as Error).message)
    } finally {
      setBusy(null)
    }
  }

  return (
    <PopoverRoot
      onOpenChange={(open) => {
        if (!open || workload) return
        // Loaded on open rather than per row: an inbox of forty rows would otherwise fire forty
        // identical requests for the same roster.
        // A fixed period, deliberately NOT the shell's: this reads the roster's workload to
        // populate a picker, so it wants a stable recent window rather than whatever the header
        // happens to be filtered to. `{ kind: 'month' }` is now the union member rather than the
        // bare string it was before the filter gained custom ranges.
        fetchPmMetrics({ kind: 'month' }, activeBrandId)
          .then((metrics) => setWorkload(metrics.workload))
          .catch((cause: Error) => setError(cause.message))
      }}
    >
      <PopoverTrigger asChild>
        <button type="button" className="inline-flex items-center gap-1 text-sm">
          {current?.name ?? 'Assigned'}
          <ChevronDown className="h-3.5 w-3.5" aria-hidden />
        </button>
      </PopoverTrigger>
      <PopoverContent label="Reassign case manager">
        <p className="text-xs font-medium tracking-wide uppercase" style={{ color: 'var(--text-muted)' }}>
          Move to
        </p>

        {workload === null && !error && (
          <div className="mt-2 h-16 animate-pulse rounded-md" style={{ background: 'var(--bg-raised)' }} />
        )}

        {workload?.map((row) => (
          <button
            key={row.cmId}
            type="button"
            disabled={row.cmId === card.assignedCm || busy !== null}
            onClick={() => reassign(row.cmId)}
            className="mt-1 block w-full rounded-md px-2 py-1 text-left disabled:opacity-45"
          >
            {/* The destination's load is shown before the click, not after — the point of doing
                this from a popover rather than a bare dropdown of names. */}
            <CapacityBar label={row.name} used={row.active} capacity={row.capacity} />
          </button>
        ))}

        {busy && (
          <p className="mt-2 text-xs" style={{ color: 'var(--text-muted)' }}>
            Reassigning…
          </p>
        )}
        {error && (
          <p className="mt-2 text-xs" style={{ color: 'var(--status-red)' }}>
            {error}
          </p>
        )}
      </PopoverContent>
    </PopoverRoot>
  )
}

/**
 * Claim a pooled case for yourself.
 *
 * Posts the caller's **own** member id rather than offering a picker of Project Managers: the
 * point of the change that put this button here is that the person looking at the queue takes
 * the work, and a dropdown would reintroduce the hand-off step it removed. Routing a case to a
 * different PM is still possible from the case page.
 *
 * Rendered for any role that can reach the inbox, which is the Project Manager — plus whatever
 * the server allows, since `assign-pm` also admits the GM and the Brand Manager. No role check
 * here: the server holds the gate and a refusal surfaces as the error below.
 */
function TakeCase({
  card,
  pmId,
  onAssigned,
}: {
  card: BoardCard
  pmId: string
  onAssigned: () => void
}) {
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function take() {
    setBusy(true)
    setError(null)
    try {
      await unwrap(api.post(`/cases/${card.id}/assign-pm`, { pmId }))
      onAssigned()
    } catch (cause) {
      setError((cause as Error).message)
      setBusy(false)
    }
    // No `finally`: on success the row is re-fetched and this component unmounts, so clearing
    // `busy` would be a state update on an unmounted node.
  }

  return (
    <span className="inline-flex flex-col items-start gap-1">
      <button
        type="button"
        onClick={take}
        disabled={busy}
        className="h-8 px-2.5 text-sm font-medium disabled:opacity-45"
        style={{
          borderRadius: 'var(--radius-md)',
          background: 'var(--accent-soft)',
          border: '1px solid var(--accent-primary)',
          color: 'var(--accent-primary)',
        }}
      >
        {busy ? 'Taking…' : 'Take this case'}
      </button>
      {error && (
        <span className="text-xs" style={{ color: 'var(--status-red)' }}>
          {error}
        </span>
      )}
    </span>
  )
}
