import {
  Bar,
  BarChart,
  CartesianGrid,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import { Card, KpiCard, type CardState } from "../../components/ui/card";
import { Donut } from "../../components/ui/widgets";
import { ROLE_LABELS } from "../../lib/session";
import { emptyWhen } from "../dashboards/useMetrics";
import { SERIES } from "../dashboards/journeyWidgets";
import type { JobRun } from "../jobs/jobsApi";
import type { MirroredPipeline, StaffMember, SyncHealth } from "./adminApi";

/**
 * The summary row at the top of each admin screen — the figures the Administrator's dashboard used to
 * carry, moved to the screen that acts on them (2026-10-10), so the dashboard can be System health.
 * Each takes the data its page already loaded: no second request for the same rows.
 */

const ROW = "grid grid-cols-12 gap-5";

export function StaffSummary({ staff, state }: { staff?: StaffMember[]; state: CardState }) {
  const active = (staff ?? []).filter((member) => member.active);
  const byRole = count(active.map((m) => ROLE_LABELS[m.role] ?? m.role));
  return (
    <div className={ROW}>
      <KpiCard
        className="col-span-12 md:col-span-4"
        title="Active staff"
        state={state}
        value={active.length}
        denominator={staff ? `of ${staff.length} accounts` : undefined}
      />
      <Card
        className="col-span-12 md:col-span-8"
        title="Active staff by role"
        state={emptyWhen(state, byRole.size === 0, "No active staff.")}
      >
        <BarList rows={[...byRole].sort((a, b) => b[1] - a[1])} color="var(--chart-1)" columns />
      </Card>
    </div>
  );
}

export function PipelineSummary({ pipelines, state }: { pipelines?: MirroredPipeline[]; state: CardState }) {
  const live = (pipelines ?? []).filter((p) => p.missingSince === null);
  const untagged = live.filter((p) => p.purpose === "UNASSIGNED").length;
  const slices = [...count(live.map((p) => readable(p.purpose)))].map(([name, value], i) => ({
    name,
    value,
    // Untagged is the one slice that asks for action, so it is the one that is not a series colour.
    color: name === readable("UNASSIGNED") ? "var(--status-amber)" : SERIES[i % SERIES.length],
  }));
  return (
    <div className={ROW}>
      <KpiCard
        className="col-span-12 md:col-span-4"
        title="Pipelines with no purpose"
        state={state}
        value={untagged}
        denominator={pipelines ? `of ${live.length} live pipelines` : undefined}
        tone={untagged > 0 ? "warn" : undefined}
      />
      <Card
        className="col-span-12 md:col-span-8"
        title="Pipelines by purpose"
        state={emptyWhen(state, slices.length === 0, "No live pipelines.")}
      >
        <div className="flex flex-wrap items-center gap-x-6 gap-y-3">
          <Donut slices={slices} centre={String(live.length)} caption="live" size={124} />
          <ul className="min-w-[10rem] flex-1 space-y-1.5 text-sm">
            {slices.map((slice) => (
              <li key={slice.name} className="flex items-center gap-2">
                <span aria-hidden className="h-2 w-2 shrink-0 rounded-full" style={{ background: slice.color }} />
                <span className="min-w-0 flex-1 truncate">{slice.name}</span>
                <span className="font-num tabular-nums">{slice.value}</span>
              </li>
            ))}
          </ul>
        </div>
      </Card>
    </div>
  );
}

export function SyncSummary({ health, state }: { health?: SyncHealth; state: CardState }) {
  const byKind = count((health?.drifts ?? []).map((d) => readable(d.kind)));
  // With no drift the Drift table below already says "EvalOS and GHL agree"; a second card saying so is noise.
  const drifting = byKind.size > 0;
  return (
    <div className={ROW}>
      <Card className={`col-span-12 ${drifting ? "md:col-span-6" : ""}`} title="Outbox to GHL" state={state}>
        {health && (
          <>
            <BarList
              rows={[
                ["Waiting to send", health.outbox.pending],
                ["Dead", health.outbox.dead],
              ]}
              colors={["var(--accent-primary)", "var(--status-red)"]}
            />
            <p className="mt-3 text-xs" style={{ color: "var(--text-muted)" }}>
              {health.outbox.oldestPending
                ? `Oldest waiting item: ${new Date(health.outbox.oldestPending).toLocaleString()}`
                : "Nothing is waiting."}
            </p>
          </>
        )}
      </Card>
      {drifting && (
        <Card className="col-span-12 md:col-span-6" title="Open drift by kind" state={state}>
          <BarList rows={[...byKind].sort((a, b) => b[1] - a[1])} color="var(--status-amber)" />
        </Card>
      )}
    </div>
  );
}

export function RunsByDayCard({ runs, state }: { runs?: JobRun[]; state: CardState }) {
  return (
    <Card
      title="Sweep runs, last 7 days"
      state={emptyWhen(state, (runs?.length ?? 0) === 0, "No sweep has run yet.")}
    >
      <RunsByDay runs={runs ?? []} />
    </Card>
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

/** Label, count, and a bar scaled to the largest — the shape of a distribution without a chart's axes. */
function BarList({
  rows,
  color,
  colors,
  columns = false,
}: {
  rows: [string, number][];
  color?: string;
  colors?: string[];
  /** Two columns from `sm` up, for a list long enough to waste a wide card's width. */
  columns?: boolean;
}) {
  const max = Math.max(1, ...rows.map(([, n]) => n));
  return (
    <ul className={columns ? "grid gap-x-8 gap-y-3 sm:grid-cols-2" : "space-y-3"}>
      {rows.map(([label, n], i) => (
        <li key={label}>
          <div className="flex items-baseline justify-between gap-3 text-sm">
            <span className="min-w-0 truncate">{label}</span>
            <span className="font-num tabular-nums">{n}</span>
          </div>
          <div aria-hidden className="mt-1 h-2 w-full overflow-hidden rounded-full" style={{ background: "var(--bg-raised)" }}>
            <div
              className="h-full rounded-full"
              style={{ width: `${Math.max((n / max) * 100, n > 0 ? 3 : 0)}%`, background: colors?.[i] ?? color }}
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
    return { key: d.toDateString(), name: d.toLocaleDateString("en-US", { weekday: "short" }), OK: 0, Failed: 0 };
  });
  for (const run of runs) {
    const day = days.find((d) => d.key === new Date(run.startedAt).toDateString());
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
            <span aria-hidden className="h-2 w-2 rounded-full" style={{ background: color }} />
            {label}
          </li>
        ))}
      </ul>
      <div className="h-40 w-full" role="img" aria-label="Sweep runs per day, last seven days">
        <ResponsiveContainer width="100%" height="100%">
          <BarChart data={days} margin={{ top: 4, right: 4, bottom: 0, left: 0 }}>
            <CartesianGrid vertical={false} stroke="var(--border-default)" />
            <XAxis dataKey="name" tickLine={false} axisLine={false} tick={{ fontSize: 11, fill: "var(--text-muted)" }} />
            <YAxis width={28} allowDecimals={false} tickLine={false} axisLine={false} tick={{ fontSize: 11, fill: "var(--text-muted)" }} />
            <Tooltip
              cursor={{ fill: "var(--bg-raised)" }}
              contentStyle={{ borderRadius: 12, border: "1px solid var(--border-tint)", boxShadow: "var(--shadow-pop)", fontSize: 12 }}
            />
            <Bar dataKey="OK" stackId="r" fill="var(--status-green)" maxBarSize={22} />
            <Bar dataKey="Failed" stackId="r" fill="var(--status-red)" radius={[6, 6, 0, 0]} maxBarSize={22} />
          </BarChart>
        </ResponsiveContainer>
      </div>
    </>
  );
}
