import {
  Bar,
  BarChart,
  CartesianGrid,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import { Link } from "react-router-dom";
import { Card, KpiCard } from "../../components/ui/card";
import { Donut } from "../../components/ui/widgets";
import { ROLE_LABELS } from "../../lib/session";
import {
  fetchPipelines,
  fetchStaff,
  fetchSyncHealth,
  type MirroredPipeline,
  type StaffMember,
  type SyncHealth,
} from "../admin/adminApi";
import {
  fetchRuns,
  fetchSweeps,
  type JobRun,
  type SweepStatus,
} from "../jobs/jobsApi";
import { emptyWhen, useMetrics } from "./useMetrics";
import { SERIES } from "./journeyWidgets";

/**
 * The Administrator's landing screen (spec 78): what needs an administrator, each figure linking to the
 * screen that fixes it. Deliberately not the GM's overview — that one reads revenue, which is gated to
 * whoever may see a deal value, and the Admin may not. Everything here comes from the admin endpoints
 * the role already owns (`/api/team-members`, `/api/ghl`, `/api/sync`, `/api/jobs`), so it needs no new
 * server surface.
 *
 * <p><strong>Drawn, with the numbers a hover away</strong> (2026-10-09): who is on the roster, how the
 * pipelines are tagged, what is stuck in the outbox and in drift, and whether the sweeps are turning.
 * A stopped sweep has no symptom anywhere else, so its health is on the front page rather than behind a link.
 */
export default function AdminDashboard() {
  const staff = useMetrics<StaffMember[]>((signal) => fetchStaff(signal), [], {
    refreshEvery: 60_000,
  });
  const pipelines = useMetrics<MirroredPipeline[]>(
    (signal) => fetchPipelines(signal),
    [],
    { refreshEvery: 60_000 },
  );
  const sync = useMetrics<SyncHealth>((signal) => fetchSyncHealth(signal), [], {
    refreshEvery: 60_000,
  });
  const sweeps = useMetrics<SweepStatus[]>(
    (signal) => fetchSweeps(signal),
    [],
    { refreshEvery: 60_000 },
  );
  const runs = useMetrics<JobRun[]>((signal) => fetchRuns(signal), [], {
    refreshEvery: 60_000,
  });

  const active = staff.data?.filter((member) => member.active).length ?? 0;
  const live =
    pipelines.data?.filter((pipeline) => pipeline.missingSince === null) ?? [];
  const untagged = live.filter(
    (pipeline) => pipeline.purpose === "UNASSIGNED",
  ).length;

  const byRole = count(
    (staff.data ?? [])
      .filter((m) => m.active)
      .map((m) => ROLE_LABELS[m.role] ?? m.role),
  );
  const byPurpose = count(live.map((p) => readable(p.purpose)));
  const purposeSlices = [...byPurpose].map(([name, value], i) => ({
    name,
    value,
    // Untagged is the one slice that asks for action, so it is the one that is not a series colour.
    color:
      name === readable("UNASSIGNED")
        ? "var(--status-amber)"
        : SERIES[i % SERIES.length],
  }));
  const byKind = count((sync.data?.drifts ?? []).map((d) => readable(d.kind)));
  const stale = (sweeps.data ?? []).filter((s) => s.stale !== null).length;

  return (
    <section>
      <h1 className="text-xl font-semibold tracking-tight">Administration</h1>
      <p className="mt-0.5 text-sm" style={{ color: "var(--text-muted)" }}>
        Staff, pipelines and the GHL connection. Business dashboards are
        read-only from here.
      </p>

      <div className="mt-4 grid grid-cols-12 gap-5">
        <KpiCard
          className="col-span-6 xl:col-span-3"
          title="Active staff"
          state={staff.state}
          value={active}
          denominator={
            staff.data ? `of ${staff.data.length} accounts` : undefined
          }
          to="/admin/staff"
        />
        <KpiCard
          className="col-span-6 xl:col-span-3"
          title="Pipelines with no purpose"
          state={pipelines.state}
          value={untagged}
          denominator={
            pipelines.data ? `of ${live.length} live pipelines` : undefined
          }
          tone={untagged > 0 ? "warn" : undefined}
          to="/admin/pipelines"
        />
        <KpiCard
          className="col-span-6 xl:col-span-3"
          title="Open drift with GHL"
          state={sync.state}
          value={sync.data?.open ?? 0}
          denominator={
            sync.data ? `${sync.data.needsAHuman} need a person` : undefined
          }
          tone={sync.data && sync.data.needsAHuman > 0 ? "bad" : undefined}
          to="/admin/sync"
        />
        <KpiCard
          className="col-span-6 xl:col-span-3"
          title="Dead outbox items"
          state={sync.state}
          value={sync.data?.outbox.dead ?? 0}
          denominator={
            sync.data
              ? `${sync.data.outbox.pending} waiting to send`
              : undefined
          }
          tone={sync.data && sync.data.outbox.dead > 0 ? "bad" : undefined}
          to="/admin/sync"
        />

        <Card
          className="col-span-12 md:col-span-6 xl:col-span-4"
          title="Active staff by role"
          state={emptyWhen(staff.state, byRole.size === 0, "No active staff.")}
          to="/admin/staff"
        >
          <BarList
            rows={[...byRole].sort((a, b) => b[1] - a[1])}
            color="var(--chart-1)"
          />
        </Card>

        <Card
          className="col-span-12 md:col-span-6 xl:col-span-4"
          title="Pipelines by purpose"
          state={emptyWhen(
            pipelines.state,
            purposeSlices.length === 0,
            "No live pipelines.",
          )}
          to="/admin/pipelines"
        >
          <div className="flex flex-wrap items-center gap-x-6 gap-y-3">
            <Donut
              slices={purposeSlices}
              centre={String(live.length)}
              caption="live"
              size={124}
            />
            <ul className="min-w-0 flex-1 space-y-1.5 text-sm">
              {purposeSlices.map((slice) => (
                <li key={slice.name} className="flex items-center gap-2">
                  <span
                    aria-hidden
                    className="h-2 w-2 shrink-0 rounded-full"
                    style={{ background: slice.color }}
                  />
                  <span className="min-w-0 flex-1 truncate">{slice.name}</span>
                  <span className="font-num tabular-nums">{slice.value}</span>
                </li>
              ))}
            </ul>
          </div>
        </Card>

        <Card
          className="col-span-12 md:col-span-6 xl:col-span-4"
          title="Outbox to GHL"
          state={sync.state}
          to="/admin/sync"
        >
          {sync.data && (
            <>
              <BarList
                rows={[
                  ["Waiting to send", sync.data.outbox.pending],
                  ["Dead", sync.data.outbox.dead],
                ]}
                colors={["var(--accent-primary)", "var(--status-red)"]}
              />
              <p
                className="mt-3 text-xs"
                style={{ color: "var(--text-muted)" }}
              >
                {sync.data.outbox.oldestPending
                  ? `Oldest waiting item: ${new Date(sync.data.outbox.oldestPending).toLocaleString()}`
                  : "Nothing is waiting."}
              </p>
            </>
          )}
        </Card>

        <Card
          className="col-span-12 md:col-span-6 xl:col-span-4"
          title="Open drift by kind"
          state={emptyWhen(
            sync.state,
            (sync.data?.drifts.length ?? 0) === 0,
            "EvalOS and GHL agree.",
          )}
          to="/admin/sync"
        >
          <BarList
            rows={[...byKind].sort((a, b) => b[1] - a[1])}
            color="var(--status-amber)"
          />
        </Card>

        <Card
          className="col-span-12 md:col-span-6 xl:col-span-4"
          title="Sweeps"
          state={
            sweeps.state.kind === "ok" && stale > 0
              ? { kind: "warning" }
              : emptyWhen(
                  sweeps.state,
                  (sweeps.data?.length ?? 0) === 0,
                  "No sweeps are scheduled.",
                )
          }
        >
          <ul className="space-y-2 text-sm">
            {(sweeps.data ?? []).map((sweep) => {
              const [colour, word] = health(sweep);
              return (
                <li key={sweep.jobType} className="flex items-center gap-2">
                  <span
                    aria-hidden
                    className="h-2.5 w-2.5 shrink-0 rounded-full"
                    style={{ background: colour }}
                  />
                  <span className="min-w-0 flex-1 truncate">
                    {sweep.jobType}
                  </span>
                  {/* The word is the status; the dot is only a second signal. */}
                  <span
                    className="shrink-0 text-xs"
                    style={{ color: "var(--text-muted)" }}
                  >
                    {word}
                  </span>
                </li>
              );
            })}
          </ul>
          <Link
            to="/admin/jobs"
            className="mt-3 inline-block text-xs font-medium"
            style={{ color: "var(--accent-primary)" }}
          >
            Open background jobs
          </Link>
        </Card>

        <Card
          className="col-span-12 md:col-span-12 xl:col-span-4"
          title="Sweep runs, last 7 days"
          state={emptyWhen(
            runs.state,
            (runs.data?.length ?? 0) === 0,
            "No sweep has run yet.",
          )}
          to="/admin/jobs"
        >
          <RunsByDay runs={runs.data ?? []} />
        </Card>
      </div>
    </section>
  );
}

function count(values: string[]): Map<string, number> {
  const out = new Map<string, number>();
  for (const v of values) out.set(v, (out.get(v) ?? 0) + 1);
  return out;
}

/** `CREDENTIAL_EVALUATION` → `Credential evaluation`. */
function readable(value: string): string {
  return value.charAt(0) + value.slice(1).toLowerCase().replaceAll("_", " ");
}

/** A sweep's health as a colour and a word: stale beats failed beats running beats ok. */
function health(sweep: SweepStatus): [string, string] {
  if (sweep.stale) return ["var(--status-red)", "stale"];
  if (sweep.lastStartedAt === null) return ["var(--status-red)", "never run"];
  if (sweep.lastStatus === "FAILED") return ["var(--status-red)", "failed"];
  if (!sweep.idle) return ["var(--status-amber)", "running"];
  return ["var(--status-green)", `ok · ${ago(sweep.lastStartedAt)}`];
}

function ago(iso: string): string {
  const minutes = Math.max(
    0,
    Math.round((Date.now() - Date.parse(iso)) / 60_000),
  );
  return minutes < 60
    ? `${minutes}m ago`
    : minutes < 1440
      ? `${Math.round(minutes / 60)}h ago`
      : `${Math.round(minutes / 1440)}d ago`;
}

/** Label, count, and a bar scaled to the largest — the shape of a distribution without a chart's axes. */
function BarList({
  rows,
  color,
  colors,
}: {
  rows: [string, number][];
  color?: string;
  colors?: string[];
}) {
  const max = Math.max(1, ...rows.map(([, n]) => n));
  return (
    <ul className="space-y-3">
      {rows.map(([label, n], i) => (
        <li key={label}>
          <div className="flex items-baseline justify-between gap-3 text-sm">
            <span className="min-w-0 truncate">{label}</span>
            <span className="font-num tabular-nums">{n}</span>
          </div>
          <div
            aria-hidden
            className="mt-1 h-2 w-full overflow-hidden rounded-full"
            style={{ background: "var(--bg-raised)" }}
          >
            <div
              className="h-full rounded-full"
              style={{
                width: `${Math.max((n / max) * 100, n > 0 ? 3 : 0)}%`,
                background: colors?.[i] ?? color,
              }}
            />
          </div>
        </li>
      ))}
    </ul>
  );
}

/** Runs per local day for the last week, OK against FAILED — a failure shows as red on its day. */
function RunsByDay({ runs }: { runs: JobRun[] }) {
  const days = Array.from({ length: 7 }, (_, i) => {
    const d = new Date();
    d.setDate(d.getDate() - (6 - i));
    return {
      key: d.toDateString(),
      name: d.toLocaleDateString("en-US", { weekday: "short" }),
      OK: 0,
      Failed: 0,
    };
  });
  for (const run of runs) {
    const day = days.find(
      (d) => d.key === new Date(run.startedAt).toDateString(),
    );
    if (day) day[run.status === "FAILED" ? "Failed" : "OK"] += 1;
  }
  return (
    <>
      <ul className="mb-2 flex gap-4 text-xs">
        {[
          ["OK", "var(--status-green)"],
          ["Failed", "var(--status-red)"],
        ].map(([label, color]) => (
          <li key={label} className="flex items-center gap-1.5">
            <span
              aria-hidden
              className="h-2 w-2 rounded-full"
              style={{ background: color }}
            />
            {label}
          </li>
        ))}
      </ul>
      <div
        className="h-40 w-full"
        role="img"
        aria-label="Sweep runs per day, last seven days"
      >
        <ResponsiveContainer width="100%" height="100%">
          <BarChart
            data={days}
            margin={{ top: 4, right: 4, bottom: 0, left: 0 }}
          >
            <CartesianGrid vertical={false} stroke="var(--border-default)" />
            <XAxis
              dataKey="name"
              tickLine={false}
              axisLine={false}
              tick={{ fontSize: 11, fill: "var(--text-muted)" }}
            />
            <YAxis
              width={28}
              allowDecimals={false}
              tickLine={false}
              axisLine={false}
              tick={{ fontSize: 11, fill: "var(--text-muted)" }}
            />
            <Tooltip
              cursor={{ fill: "var(--bg-raised)" }}
              contentStyle={{
                borderRadius: 12,
                border: "1px solid var(--border-tint)",
                boxShadow: "var(--shadow-pop)",
                fontSize: 12,
              }}
            />
            <Bar
              dataKey="OK"
              stackId="r"
              fill="var(--status-green)"
              maxBarSize={22}
            />
            <Bar
              dataKey="Failed"
              stackId="r"
              fill="var(--status-red)"
              radius={[6, 6, 0, 0]}
              maxBarSize={22}
            />
          </BarChart>
        </ResponsiveContainer>
      </div>
    </>
  );
}
