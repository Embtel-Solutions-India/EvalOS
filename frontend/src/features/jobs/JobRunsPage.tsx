import { useState } from 'react'
import { Card } from '../../components/ui/card'
import { useMetrics } from '../dashboards/useMetrics'
import {
  fetchRuns,
  fetchSweeps,
  runSweep,
  type JobRun,
  type RunStatus,
  type SweepStatus,
} from './jobsApi'

/**
 * Whether the background sweeps are still running — the one screen over Unit 19.
 *
 * **A stopped sweep has no symptom, and that is the entire reason this page exists.** Nobody is
 * chased, nothing escalates, no error is thrown; the system simply goes quiet and the first
 * sign is a client asking why nobody followed up. So the top of the page is not the ledger, it
 * is four lines saying when each sweep last ran — because "last ran three days ago" is the fact
 * that answers the question, and it is not visible in a list of rows.
 *
 * **GM-only** because the ledger is cross-brand by nature: a sweep runs over every brand's
 * cases at once, so there is no scoped version of this to give a Brand Manager, and a partial
 * one would misreport what ran.
 */
export default function JobRunsPage() {
  const sweeps = useMetrics((signal) => fetchSweeps(signal), [])
  const runs = useMetrics((signal) => fetchRuns(signal), [])
  const [busy, setBusy] = useState<string | null>(null)
  const [outcome, setOutcome] = useState<string | null>(null)

  async function trigger(jobType: string) {
    setBusy(jobType)
    setOutcome(null)
    try {
      const result = await runSweep(jobType)
      setOutcome(`${jobType}: ${result.message}`)
    } catch (error) {
      setOutcome(`${jobType}: ${(error as Error).message}`)
    } finally {
      setBusy(null)
      // Both, and only after the run returns: the ledger row is written by the sweep itself,
      // so a refetch before it finishes would show the RUNNING row and look stuck.
      sweeps.reload()
      runs.reload()
    }
  }

  return (
    <div className="space-y-6 p-6">
      <header>
        <h1 className="text-lg font-medium">Background jobs</h1>
        <p className="mt-1 text-sm" style={{ color: 'var(--text-muted)' }}>
          The sweeps that chase documents, escalate overdue collections, refresh SLA status and
          watch the signing deadline. A sweep that has stopped shows here as a stale “last run”,
          which is the only place it shows at all.
        </p>
      </header>

      <Card
        title="Sweeps"
        state={
          // Warning, not error: the data loaded fine — it is what it says that is the problem.
          // A stopped sweep is the one thing this page must not render as a calm row.
          sweeps.state.kind === 'ok' && (sweeps.data ?? []).some((sweep) => sweep.stale !== null)
            ? { kind: 'warning' }
            : sweeps.state
        }
        wide
      >
        <div className="space-y-3">
          {(sweeps.data ?? []).map((sweep) => (
            <SweepLine
              key={sweep.jobType}
              sweep={sweep}
              busy={busy === sweep.jobType}
              disabled={busy !== null}
              onRun={() => trigger(sweep.jobType)}
            />
          ))}
        </div>
        {outcome && (
          <p className="mt-4 text-sm" style={{ color: 'var(--text-muted)' }}>
            {outcome}
          </p>
        )}
      </Card>

      <Card
        title="Recent runs"
        note="Newest first. A row still marked RUNNING with no finish time is a sweep whose process died mid-pass."
        state={runs.state}
        wide
      >
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-slate-200 text-left text-xs text-slate-500">
                <th className="py-2 pr-3">Sweep</th>
                <th className="py-2 pr-3">Started</th>
                <th className="py-2 pr-3">Status</th>
                <th className="py-2 pr-3">Seen</th>
                <th className="py-2 pr-3">Acted</th>
                <th className="py-2 pr-3">Error</th>
              </tr>
            </thead>
            <tbody>
              {(runs.data ?? []).map((run) => (
                <RunRow key={run.id} run={run} />
              ))}
            </tbody>
          </table>
        </div>
      </Card>
    </div>
  )
}

function SweepLine({
  sweep,
  busy,
  disabled,
  onRun,
}: {
  sweep: SweepStatus
  busy: boolean
  disabled: boolean
  onRun: () => void
}) {
  return (
    <div className="flex flex-wrap items-center justify-between gap-3 border-b border-slate-100 pb-3 last:border-0">
      <div>
        <p className="font-medium">{sweep.jobType}</p>
        <p className="text-sm" style={{ color: 'var(--text-muted)' }}>
          {sweep.lastStartedAt === null
            ? /*
                Said plainly, because it is the alarming state and it looks like the calm one.
                "Never run" means the schedule is not turning at all — not that there was no
                work to do.
              */
              'Never run.'
            : `Last ran ${new Date(sweep.lastStartedAt).toLocaleString()} — ${sweep.lastStatus}` +
              `, ${sweep.lastItemsActed ?? 0} of ${sweep.lastItemsSeen ?? 0} acted` +
              (sweep.lastDurationSeconds === null ? '' : ` in ${sweep.lastDurationSeconds}s`) +
              '.'}
        </p>
        {sweep.stale && (
          <p className="text-sm font-medium" style={{ color: 'var(--status-red)' }}>
            {sweep.stale}
          </p>
        )}
        {sweep.lastError && (
          <p className="text-sm" style={{ color: 'var(--status-red)' }}>
            {sweep.lastError}
          </p>
        )}
      </div>
      <button
        type="button"
        onClick={onRun}
        disabled={disabled || !sweep.idle}
        className="rounded border px-3 py-1.5 text-sm disabled:opacity-50"
        style={{ borderColor: 'var(--border-default)' }}
      >
        {busy ? 'Running…' : sweep.idle ? 'Run now' : 'Already running'}
      </button>
    </div>
  )
}

function RunRow({ run }: { run: JobRun }) {
  return (
    <tr className="border-b border-slate-100">
      <td className="py-2 pr-3">{run.jobType}</td>
      <td className="py-2 pr-3">{new Date(run.startedAt).toLocaleString()}</td>
      <td className="py-2 pr-3" style={{ color: statusColour(run.status, run.finishedAt) }}>
        {run.status}
      </td>
      <td className="py-2 pr-3">{run.itemsSeen}</td>
      <td className="py-2 pr-3">{run.itemsActed}</td>
      <td className="py-2 pr-3" style={{ color: 'var(--text-muted)' }}>
        {run.error ?? '—'}
      </td>
    </tr>
  )
}

/** A row still RUNNING is only interesting once it has no finish time and the page has loaded. */
function statusColour(status: RunStatus, finishedAt: string | null): string {
  if (status === 'FAILED') return 'var(--status-red)'
  if (status === 'RUNNING' && finishedAt === null) return 'var(--status-amber)'
  return 'var(--text-default)'
}
