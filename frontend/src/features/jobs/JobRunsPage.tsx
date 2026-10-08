import { useState } from "react";
import { Card } from "../../components/ui/card";
import { Pager, usePaging } from "../../components/ui/pager";
import { useMetrics } from "../dashboards/useMetrics";
import {
  fetchRuns,
  fetchSweeps,
  runSweep,
  type JobRun,
  type RunStatus,
  type SweepStatus,
} from "./jobsApi";

/**
 * Whether the background sweeps are still running — the one screen over Unit 19.
 *
 * **A stopped sweep has no symptom, and that is the entire reason this page exists.** Nobody is
 * chased, nothing escalates, no error is thrown; the system simply goes quiet and the first
 * sign is a client asking why nobody followed up. So the left of the page is not the ledger, it
 * is the sweeps saying when each last ran — because "last ran three days ago" is the fact
 * that answers the question, and it is not visible in a list of rows.
 *
 * **Side by side, one screen** (2026-10-09): the sweeps and the ledger share a row, each scrolls inside
 * its own card and each pages, so the page itself never grows past the window. Paging is client-side —
 * the server returns the ledger whole.
 *
 * **GM-only** because the ledger is cross-brand by nature: a sweep runs over every brand's
 * cases at once, so there is no scoped version of this to give a Brand Manager, and a partial
 * one would misreport what ran.
 */
export default function JobRunsPage() {
  const sweeps = useMetrics((signal) => fetchSweeps(signal), []);
  const runs = useMetrics((signal) => fetchRuns(signal), []);
  const [busy, setBusy] = useState<string | null>(null);
  const [outcome, setOutcome] = useState<string | null>(null);
  const sweepPaging = usePaging(sweeps.data ?? [], 4);
  const runPaging = usePaging(runs.data ?? [], 10);

  async function trigger(jobType: string) {
    setBusy(jobType);
    setOutcome(null);
    try {
      const result = await runSweep(jobType);
      setOutcome(`${jobType}: ${result.message}`);
    } catch (error) {
      setOutcome(`${jobType}: ${(error as Error).message}`);
    } finally {
      setBusy(null);
      // Both, and only after the run returns: the ledger row is written by the sweep itself,
      // so a refetch before it finishes would show the RUNNING row and look stuck.
      sweeps.reload();
      runs.reload();
    }
  }

  return (
    <div className="space-y-4">
      <header>
        <h1 className="text-xl font-semibold tracking-tight">
          Background jobs
        </h1>
        <p className="mt-0.5 text-sm" style={{ color: "var(--text-muted)" }}>
          The sweeps that chase documents, escalate overdue collections, refresh
          SLA status and watch the signing deadline. A sweep that has stopped
          shows here as a stale “last run”, which is the only place it shows at
          all.
        </p>
      </header>

      <div className="grid items-start gap-5 xl:grid-cols-2">
        <Card
          title="Sweeps"
          state={
            // Warning, not error: the data loaded fine — it is what it says that is the problem.
            // A stopped sweep is the one thing this page must not render as a calm row.
            sweeps.state.kind === "ok" &&
            (sweeps.data ?? []).some((sweep) => sweep.stale !== null)
              ? { kind: "warning" }
              : sweeps.state
          }
        >
          <div className="max-h-[calc(100svh-19rem)] space-y-3 overflow-y-auto pr-1">
            {sweepPaging.slice.map((sweep) => (
              <SweepLine
                key={sweep.jobType}
                sweep={sweep}
                busy={busy === sweep.jobType}
                disabled={busy !== null}
                onRun={() => trigger(sweep.jobType)}
              />
            ))}
          </div>
          <Pager paging={sweepPaging} noun="sweeps" />
          {outcome && (
            <p className="mt-3 text-sm" style={{ color: "var(--text-muted)" }}>
              {outcome}
            </p>
          )}
        </Card>

        <Card
          title="Recent runs"
          note="Newest first. A row still marked RUNNING with no finish time is a sweep whose process died mid-pass."
          state={runs.state}
        >
          <div className="max-h-[calc(100svh-23rem)] overflow-auto">
            <table className="w-full text-sm">
              <thead>
                <tr
                  className="text-left text-xs"
                  style={{ color: "var(--text-muted)" }}
                >
                  {["Sweep", "Started", "Status", "Seen", "Acted", "Error"].map(
                    (heading) => (
                      <th
                        key={heading}
                        className="sticky top-0 py-2 pr-3 font-medium"
                        style={{ background: "var(--bg-surface)" }}
                      >
                        {heading}
                      </th>
                    ),
                  )}
                </tr>
              </thead>
              <tbody>
                {runPaging.slice.map((run) => (
                  <RunRow key={run.id} run={run} />
                ))}
              </tbody>
            </table>
          </div>
          <Pager paging={runPaging} noun="runs" />
        </Card>
      </div>
    </div>
  );
}

function SweepLine({
  sweep,
  busy,
  disabled,
  onRun,
}: {
  sweep: SweepStatus;
  busy: boolean;
  disabled: boolean;
  onRun: () => void;
}) {
  return (
    <div
      className="flex flex-wrap items-center justify-between gap-3 border-b pb-3 last:border-0"
      style={{ borderColor: "var(--border-default)" }}
    >
      <div className="min-w-0">
        <p className="font-medium">{sweep.jobType}</p>
        <p className="text-sm" style={{ color: "var(--text-muted)" }}>
          {sweep.lastStartedAt === null
            ? /*
                Said plainly, because it is the alarming state and it looks like the calm one.
                "Never run" means the schedule is not turning at all — not that there was no
                work to do.
              */
              "Never run."
            : `Last ran ${new Date(sweep.lastStartedAt).toLocaleString()} — ${sweep.lastStatus}` +
              `, ${sweep.lastItemsActed ?? 0} of ${sweep.lastItemsSeen ?? 0} acted` +
              (sweep.lastDurationSeconds === null
                ? ""
                : ` in ${sweep.lastDurationSeconds}s`) +
              "."}
        </p>
        {sweep.stale && (
          <p
            className="text-sm font-medium"
            style={{ color: "var(--status-red)" }}
          >
            {sweep.stale}
          </p>
        )}
        {sweep.lastError && (
          <p className="text-sm" style={{ color: "var(--status-red)" }}>
            {sweep.lastError}
          </p>
        )}
      </div>
      <button
        type="button"
        onClick={onRun}
        disabled={disabled || !sweep.idle}
        className="rounded-xl px-4 py-2 text-sm font-medium disabled:opacity-50"
        style={{ background: "var(--bg-raised)" }}
      >
        {busy ? "Running…" : sweep.idle ? "Run now" : "Already running"}
      </button>
    </div>
  );
}

function RunRow({ run }: { run: JobRun }) {
  return (
    <tr className="border-t" style={{ borderColor: "var(--border-default)" }}>
      <td className="py-2 pr-3">{run.jobType}</td>
      <td className="py-2 pr-3">{new Date(run.startedAt).toLocaleString()}</td>
      <td
        className="py-2 pr-3"
        style={{ color: statusColour(run.status, run.finishedAt) }}
      >
        {run.status}
      </td>
      <td className="font-num py-2 pr-3 tabular-nums">{run.itemsSeen}</td>
      <td className="font-num py-2 pr-3 tabular-nums">{run.itemsActed}</td>
      <td className="py-2 pr-3" style={{ color: "var(--text-muted)" }}>
        {run.error ?? "—"}
      </td>
    </tr>
  );
}

/** A row still RUNNING is only interesting once it has no finish time and the page has loaded. */
function statusColour(status: RunStatus, finishedAt: string | null): string {
  if (status === "FAILED") return "var(--status-red)";
  if (status === "RUNNING" && finishedAt === null) return "var(--status-amber)";
  return "var(--text-primary)";
}
