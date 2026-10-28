import { api, unwrap } from '../../lib/api'

/** How a sweep's last run ended. `RUNNING` on a finished row means the JVM died mid-sweep. */
export type RunStatus = 'RUNNING' | 'OK' | 'FAILED'

/** One line of the panel: a sweep and how it last went. */
export type SweepStatus = {
  jobType: string
  /** False while it is mid-run — the "Run now" button is disabled and says so. */
  idle: boolean
  lastStartedAt: string | null
  lastStatus: RunStatus | null
  lastItemsSeen: number | null
  lastItemsActed: number | null
  lastError: string | null
  lastDurationSeconds: number | null
  /**
   * Why this sweep looks stopped, in words, or null if it looks healthy.
   *
   * Computed on the server against the sweep's own configured interval — deliberately not
   * derived here from a threshold this file would have to guess, because a client-side guess
   * that drifts from the real interval stops warning without failing.
   */
  stale: string | null
}

/** One row of the run ledger. */
export type JobRun = {
  id: string
  jobType: string
  startedAt: string
  finishedAt: string | null
  status: RunStatus
  itemsSeen: number
  itemsActed: number
  error: string | null
}

export function fetchSweeps(signal?: AbortSignal): Promise<SweepStatus[]> {
  return unwrap(api.get('/jobs/sweeps', { signal }))
}

export function fetchRuns(signal?: AbortSignal): Promise<JobRun[]> {
  return unwrap(api.get('/jobs/runs', { signal }))
}

/** `JobAdminService.DayRuns`: one local day's runs, oldest first, every day present. */
export type DayRuns = { day: string; ok: number; failed: number }

/**
 * Runs per day for the last week, counted by the server in the viewer's own zone. The chart used to bucket
 * `/jobs/runs`, which is the last 50 runs — about half an hour — so every bar but today's was empty.
 */
export function fetchRunsByDay(signal?: AbortSignal): Promise<DayRuns[]> {
  const zone = Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC'
  return unwrap(api.get('/jobs/runs/daily', { params: { zone }, signal }))
}

export type RunResult = { jobType: string; started: boolean; message: string }

/**
 * Runs a sweep now — synchronously, so this resolves when the sweep has finished.
 *
 * The default 15s client timeout is deliberately not raised. A sweep that takes longer than
 * that is a sweep that has outgrown running inside a request, and a longer spinner would hide
 * exactly the moment that becomes true. The run itself is unaffected either way; the ledger
 * records it whether or not this call is still listening.
 */
export function runSweep(jobType: string): Promise<RunResult> {
  return unwrap(api.post(`/jobs/${jobType}/run`))
}
