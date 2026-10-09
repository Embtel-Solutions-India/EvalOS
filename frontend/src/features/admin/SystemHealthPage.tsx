import { useEffect, useRef, useState, type ReactNode } from "react";
import { useQuery } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import {
  Area,
  AreaChart,
  CartesianGrid,
  Legend,
  Line,
  LineChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import { Card, ChartCard, type CardState, type KpiTone } from "../../components/ui/card";
import { Donut, PillSelect, PillToggle, Sparkline } from "../../components/ui/widgets";
import { SERIES } from "../dashboards/journeyWidgets";
import { fetchSystemHealth, type SystemHealthReport } from "./systemHealthApi";
import {
  errorRateTone,
  formatBytes,
  formatDuration,
  formatMs,
  latencyTone,
  percent,
  pushSample,
  routeTone,
  sortRoutes,
  toSample,
  utilisationTone,
  type RouteSort,
  type Sample,
} from "./systemHealthRules";

/**
 * The Administrator's System health page (D82): this server and its database, live.
 *
 * <p><strong>Two kinds of number, and the page says which.</strong> Gauges (heap, CPU, the pool) are
 * now. Counters (requests, errors, log lines, sign-ins) are since the process started — the footer
 * names when. The charts are neither: they are this page's own reads, kept in memory from when it was
 * opened, because EvalOS stores no metric history (D82). Close the tab and they are gone.
 */
export default function SystemHealthPage() {
  const [live, setLive] = useState<"live" | "paused">("live");
  const [every, setEvery] = useState("5000");
  const [sortBy, setSortBy] = useState<RouteSort>("count");
  const [history, setHistory] = useState<Sample[]>([]);
  const previous = useRef<SystemHealthReport | null>(null);

  const query = useQuery({
    queryKey: ["admin", "system-health"],
    queryFn: ({ signal }) => fetchSystemHealth(signal),
    refetchInterval: live === "live" ? Number(every) : false,
    refetchIntervalInBackground: false,
  });
  const report = query.data;

  useEffect(() => {
    if (!report) return;
    const sample = toSample(report, previous.current);
    previous.current = report;
    setHistory((h) => pushSample(h, sample));
  }, [report]);

  const state: CardState = report
    ? { kind: "ok" }
    : query.isError
      ? { kind: "error", note: query.error.message, onRetry: () => void query.refetch() }
      : { kind: "loading" };
  const last = history[history.length - 1];

  return (
    <section className="space-y-5">
      <header className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="text-xl font-semibold tracking-tight">System health</h1>
          <p className="text-sm" style={MUTED}>
            This server and its database, read every {Number(every) / 1000} seconds. Detail for every figure is a hover away.
          </p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          {report && (
            <span className="text-xs tabular-nums" style={MUTED}>
              Updated {new Date(report.at).toLocaleTimeString()}
              {query.isError && <span style={{ color: "var(--status-red)" }}> · last read failed</span>}
            </span>
          )}
          <PillSelect label="Refresh every" value={every} onChange={(e) => setEvery(e.target.value)}>
            <option value="5000">Every 5 s</option>
            <option value="15000">Every 15 s</option>
            <option value="60000">Every minute</option>
          </PillSelect>
          <PillToggle
            label="Refresh"
            value={live}
            onChange={setLive}
            options={[
              ["live", "Live"],
              ["paused", "Paused"],
            ]}
          />
        </div>
      </header>

      {report && <StatusStrip report={report} />}

      <div className="grid grid-cols-12 gap-5">
        <Stat
          title="Heap in use"
          state={state}
          value={pctText(percent(report?.memory.heapUsed, report?.memory.heapMax))}
          tone={utilisationTone(percent(report?.memory.heapUsed, report?.memory.heapMax))}
          sub={`${formatBytes(report?.memory.heapUsed)} of ${formatBytes(report?.memory.heapMax)}`}
          spark={history.map((s) => s.heapUsedMb)}
          note="Java heap used against its maximum (-Xmx). Sustained above 90% means the collector is struggling."
        />
        <Stat
          title="Process CPU"
          state={state}
          value={pctText(last?.processCpu ?? null)}
          tone={utilisationTone(last?.processCpu ?? null)}
          sub={`Machine ${pctText(last?.systemCpu ?? null)} · ${report?.cpu.processors ?? "—"} cores${report?.cpu.loadAverage != null ? ` · load ${report.cpu.loadAverage.toFixed(2)}` : ""}`}
          spark={history.map((s) => s.processCpu)}
          note="Share of all cores this JVM used over the last moment, and the whole machine's."
        />
        <Stat
          title="Database round trip"
          state={state}
          value={report?.database.reachable ? formatMs(report.database.roundTripMs) : "Unreachable"}
          tone={report?.database.reachable === false ? "bad" : latencyTone(report?.database.roundTripMs ?? null)}
          sub={report?.database.reachable ? `PostgreSQL ${report.database.serverVersion ?? ""}` : (report?.database.error ?? "")}
          spark={history.map((s) => s.dbRoundTripMs)}
          note="Time for one SELECT 1 through the connection pool, measured on this read."
        />
        <Stat
          title="Connections in use"
          state={state}
          value={report ? `${fmt(report.pool.active)} / ${fmt(report.pool.max)}` : "—"}
          tone={
            (report?.pool.pending ?? 0) > 0
              ? "warn"
              : utilisationTone(percent(report?.pool.active, report?.pool.max))
          }
          sub={`${fmt(report?.pool.idle)} idle · ${fmt(report?.pool.pending)} waiting · ${fmt(report?.pool.timeouts)} timeouts`}
          spark={history.map((s) => s.poolActive)}
          note="HikariCP: connections lent out against the pool's ceiling. Any request waiting for one is a warning."
        />
        <Stat
          title="Requests per second"
          state={state}
          value={last?.requestsPerSec == null ? "—" : last.requestsPerSec.toFixed(2)}
          sub={`${fmt(report?.http.requests)} since start · mean ${formatMs(report?.http.meanMs)}`}
          spark={history.map((s) => s.requestsPerSec)}
          note="HTTP requests between the last two reads, per second. The total is since the server started."
        />
        <Stat
          title="Server error rate"
          state={state}
          value={pctText(percent(report?.http.byStatusClass["5xx"] ?? 0, report?.http.requests))}
          tone={errorRateTone(percent(report?.http.byStatusClass["5xx"] ?? 0, report?.http.requests))}
          sub={`${fmt(report?.http.byStatusClass["5xx"] ?? 0)} × 5xx · ${fmt(report?.http.byStatusClass["4xx"] ?? 0)} × 4xx since start`}
          spark={history.map((s) => s.errorsPerSec)}
          note="5xx responses as a share of all requests since start. 4xx are callers' mistakes and shown for context."
        />
        <Stat
          title="Errors logged"
          state={state}
          value={fmt(report?.logs.byLevel.error ?? 0)}
          tone={(report?.logs.byLevel.error ?? 0) > 0 ? "warn" : "good"}
          sub={`${fmt(report?.logs.byLevel.warn ?? 0)} warnings since start`}
          note="ERROR lines written since the server started. Find one in the container log by its request id."
        />
        <Stat
          title="Refused sign-ins"
          state={state}
          value={fmt(report?.auth.signInsRefused ?? 0)}
          tone={(report?.auth.signInsRefused ?? 0) > 20 ? "warn" : undefined}
          sub={`${fmt(report?.auth.signInsOk ?? 0)} successful since start`}
          note="Staff sign-ins refused (wrong password or unknown address) since start. A spike is a guessing attack."
        />

        <LiveChart
          title="Heap memory (MB)"
          state={chartState(state, history)}
          data={history}
          series={[
            { key: "heapUsedMb", name: "Used", color: SERIES[0] },
            { key: "heapCommittedMb", name: "Committed", color: SERIES[1] },
          ]}
          area
        />
        <LiveChart
          title="CPU (%)"
          state={chartState(state, history)}
          data={history}
          series={[
            { key: "processCpu", name: "This process", color: SERIES[0] },
            { key: "systemCpu", name: "Whole machine", color: SERIES[2] },
          ]}
          domain={[0, 100]}
        />
        <LiveChart
          title="Traffic (per second)"
          state={chartState(state, history)}
          data={history}
          series={[
            { key: "requestsPerSec", name: "Requests", color: SERIES[0] },
            { key: "errorsPerSec", name: "5xx", color: "var(--status-red)" },
          ]}
        />
        <LiveChart
          title="Database connections"
          state={chartState(state, history)}
          data={history}
          series={[
            { key: "poolActive", name: "Active", color: SERIES[0] },
            { key: "poolIdle", name: "Idle", color: SERIES[4] },
            { key: "poolPending", name: "Waiting", color: "var(--status-red)" },
          ]}
          area
        />
        <LiveChart
          title="Threads"
          state={chartState(state, history)}
          data={history}
          series={[
            { key: "threadsLive", name: "JVM live", color: SERIES[1] },
            { key: "busyThreads", name: "Busy request threads", color: SERIES[3] },
          ]}
        />
        <LiveChart
          title="Database round trip (ms)"
          state={chartState(state, history)}
          data={history}
          series={[{ key: "dbRoundTripMs", name: "SELECT 1 through the pool", color: SERIES[0] }]}
        />

        <DonutCard
          title="Responses by status"
          state={state}
          caption="requests"
          entries={Object.entries(report?.http.byStatusClass ?? {})}
          colors={{ "2xx": "var(--status-green)", "3xx": SERIES[1], "4xx": "var(--status-amber)", "5xx": "var(--status-red)" }}
          note="Every response since start, by status class."
        />
        <DonutCard
          title="Log lines by level"
          state={state}
          caption="lines"
          entries={Object.entries(report?.logs.byLevel ?? {}).filter(([, v]) => v > 0)}
          colors={{ error: "var(--status-red)", warn: "var(--status-amber)", info: SERIES[0], debug: SERIES[1], trace: SERIES[3] }}
          note="Lines written since start, by level."
        />
        <DonutCard
          title="Thread states"
          state={state}
          caption="threads"
          entries={Object.entries(report?.threads.states ?? {}).filter(([, v]) => v > 0)}
          note={`Live ${fmt(report?.threads.live)}, of which daemon ${fmt(report?.threads.daemon)}; peak ${fmt(report?.threads.peak)}. Many BLOCKED threads means lock contention.`}
        />
        <DonutCard
          title="Database server sessions"
          state={state}
          caption="sessions"
          entries={Object.entries(report?.database.serverConnections ?? {})}
          note="Sessions on this database as PostgreSQL sees them (pg_stat_activity), by state — every client, not only this server."
        />

        <MemoryPools report={report} state={state} />
        <Storage report={report} state={state} />

        <Facts
          title="Garbage collection"
          state={state}
          className="col-span-12 md:col-span-6 xl:col-span-4"
          rows={[
            ["Pauses since start", fmt(report?.gc.pauses)],
            ["Total pause time", formatMs(report?.gc.pauseTotalMs)],
            ["Longest pause (recent)", formatMs(report?.gc.pauseMaxMs)],
            ["Time spent collecting", pctText(report?.gc.overhead == null ? null : Math.round(report.gc.overhead * 1000) / 10)],
            ["Allocated since start", formatBytes(report?.gc.allocatedBytes)],
            ["Promoted to old generation", formatBytes(report?.gc.promotedBytes)],
            ["Old generation after last collection", formatBytes(report?.gc.liveDataBytes)],
            ["Old generation maximum", formatBytes(report?.gc.maxDataBytes)],
          ]}
        />
        <Facts
          title="Connection pool"
          state={state}
          className="col-span-12 md:col-span-6 xl:col-span-4"
          rows={[
            ["Active / idle / total", `${fmt(report?.pool.active)} / ${fmt(report?.pool.idle)} / ${fmt(report?.pool.total)}`],
            ["Minimum / maximum", `${fmt(report?.pool.min)} / ${fmt(report?.pool.max)}`],
            ["Waiting for a connection", fmt(report?.pool.pending)],
            ["Timed out waiting (since start)", fmt(report?.pool.timeouts)],
            ["Wait for a connection, mean / max", `${formatMs(report?.pool.acquireMeanMs)} / ${formatMs(report?.pool.acquireMaxMs)}`],
            ["Held per use, mean / max", `${formatMs(report?.pool.usageMeanMs)} / ${formatMs(report?.pool.usageMaxMs)}`],
            ["Request threads busy / current / max", `${fmt(report?.web.busyThreads)} / ${fmt(report?.web.currentThreads)} / ${fmt(report?.web.maxThreads)}`],
          ]}
        />
        <Facts
          title="Runtime"
          state={state}
          className="col-span-12 md:col-span-6 xl:col-span-4"
          rows={[
            ["Application", `${report?.runtime.application ?? "—"} ${report?.runtime.version ?? ""}`],
            ["Built", report?.runtime.builtAt ? new Date(report.runtime.builtAt).toLocaleString() : "— (no build info)"],
            ["Profile", report?.runtime.profiles.join(", ") || "—"],
            ["Started", report ? new Date(report.runtime.startedAt).toLocaleString() : "—"],
            ["Uptime", formatDuration(report?.runtime.uptimeMs)],
            ["Java", report ? `${report.runtime.javaVersion} (${report.runtime.javaVendor})` : "—"],
            ["Operating system", report?.runtime.os ?? "—"],
            ["Cores / process id", report ? `${report.runtime.processors} / ${report.runtime.pid}` : "—"],
            ["Time zone", report?.runtime.zone ?? "—"],
            ["Classes loaded", fmt(report?.runtime.loadedClasses)],
            ["Non-heap used / committed", `${formatBytes(report?.memory.nonHeapUsed)} / ${formatBytes(report?.memory.nonHeapCommitted)}`],
          ]}
        />

        <Components report={report} state={state} />
        <Routes report={report} state={state} sortBy={sortBy} onSort={setSortBy} />
        <Jobs report={report} state={state} />
      </div>

      {report && (
        <p className="text-xs break-words" style={MUTED}>
          Totals count from {new Date(report.runtime.startedAt).toLocaleString()}, when this server last started. The
          charts hold this page&rsquo;s own reads since it was opened ({history.length} so far, up to the last 120);
          EvalOS keeps no metric history, so they reset when the page closes. Logs are in the container output
          (<code>docker logs evalos-backend</code>); every response carries the request id that finds its lines.
        </p>
      )}
    </section>
  );
}

// --- pieces ----------------------------------------------------------------------------------------

const MUTED = { color: "var(--text-muted)" };
const TOOLTIP = {
  borderRadius: 12,
  border: "1px solid var(--border-tint)",
  boxShadow: "var(--shadow-pop)",
  fontSize: 12,
};
const TONE: Record<KpiTone, string> = {
  good: "var(--status-green)",
  warn: "var(--status-amber)",
  bad: "var(--status-red)",
};

const fmt = (n: number | null | undefined) => (n == null ? "—" : Math.round(n).toLocaleString());
const pctText = (n: number | null) => (n == null ? "—" : `${n.toFixed(1)}%`);
const chartState = (state: CardState, history: Sample[]): CardState =>
  state.kind === "ok" && history.length < 2 ? { kind: "empty", note: "Collecting — the chart starts with the second read." } : state;

function statusColor(status: string): string {
  if (["UP", "CORRECT", "ACCEPTING_TRAFFIC"].includes(status)) return "var(--status-green)";
  if (["UNKNOWN", "OUT_OF_SERVICE"].includes(status)) return "var(--status-amber)";
  return "var(--status-red)";
}

function StatusPill({ status }: { status: string }) {
  const color = statusColor(status);
  return (
    <span
      className="inline-flex items-center gap-1.5 rounded-full px-2.5 py-0.5 text-xs font-semibold"
      style={{ color, background: `color-mix(in srgb, ${color} 12%, transparent)` }}
    >
      <span className="h-1.5 w-1.5 rounded-full" style={{ background: color }} aria-hidden />
      {status.replaceAll("_", " ").toLowerCase()}
    </span>
  );
}

function StatusStrip({ report }: { report: SystemHealthReport }) {
  const items: [string, ReactNode, string][] = [
    ["Overall", <StatusPill key="Overall" status={report.status.overall} />, "Every health check below is UP."],
    ["Alive", <StatusPill key="Alive" status={report.status.liveness} />, "Liveness: the process is working. Ignores the database by design."],
    ["Taking traffic", <StatusPill key="Taking traffic" status={report.status.readiness} />, "Readiness: would receive traffic. Falls when the database does."],
    ["Database", <StatusPill key="Database" status={report.database.reachable ? "UP" : "DOWN"} />, "Answered the reads on this page."],
    ["Uptime", <span key="Uptime" className="font-num text-sm font-semibold tabular-nums">{formatDuration(report.runtime.uptimeMs)}</span>, `Since ${new Date(report.runtime.startedAt).toLocaleString()}`],
    ["Schema", <span key="Schema" className="font-num text-sm font-semibold tabular-nums">V{report.database.schemaVersion ?? "?"}</span>, "Latest Flyway migration applied."],
  ];
  return (
    <div
      className="grid grid-cols-2 gap-4 rounded-[1.25rem] px-5 py-4 sm:grid-cols-3 xl:grid-cols-6"
      style={{
        background: "var(--bg-surface)",
        boxShadow: "var(--shadow-soft)",
        border: report.status.overall === "UP" ? "none" : "1px solid var(--status-red)",
      }}
    >
      {items.map(([label, value, hint]) => (
        <div key={label} title={hint}>
          <p className="text-xs" style={MUTED}>{label}</p>
          <div className="mt-1">{value}</div>
        </div>
      ))}
    </div>
  );
}

function Stat({
  title,
  state,
  value,
  sub,
  tone,
  spark,
  note,
}: {
  title: string;
  state: CardState;
  value: string;
  sub?: string;
  tone?: KpiTone;
  spark?: (number | null)[];
  note: string;
}) {
  const series = (spark ?? []).filter((v): v is number => v != null);
  return (
    <Card title={title} note={note} state={state} variant="tile" className="col-span-12 sm:col-span-6 xl:col-span-3">
      <div className="flex items-end justify-between gap-3">
        <div className="min-w-0">
          <p
            className="font-num text-2xl leading-none font-semibold tracking-tight tabular-nums"
            style={{ color: tone ? TONE[tone] : "var(--text-primary)" }}
          >
            {value}
          </p>
          {sub && <p className="font-num mt-1.5 truncate text-xs tabular-nums" style={MUTED} title={sub}>{sub}</p>}
        </div>
        {series.length >= 2 && (
          <Sparkline values={series.slice(-40)} width={64} height={32} color={tone ? TONE[tone] : "var(--accent-primary)"} label={`${title} trend`} />
        )}
      </div>
    </Card>
  );
}

type SeriesDef = { key: keyof Sample; name: string; color: string };

function LiveChart({
  title,
  state,
  data,
  series,
  area = false,
  domain,
}: {
  title: string;
  state: CardState;
  data: Sample[];
  series: SeriesDef[];
  area?: boolean;
  domain?: [number, number];
}) {
  const axes = (
    <>
      <CartesianGrid strokeDasharray="3 3" stroke="var(--border-default)" vertical={false} />
      <XAxis dataKey="label" tick={{ fontSize: 10 }} stroke="var(--text-muted)" minTickGap={40} />
      <YAxis tick={{ fontSize: 10 }} stroke="var(--text-muted)" width={40} domain={domain ?? [0, "auto"]} />
      <Tooltip contentStyle={TOOLTIP} />
      <Legend iconType="circle" iconSize={8} wrapperStyle={{ fontSize: 11 }} />
    </>
  );
  return (
    <ChartCard title={title} note="This page's own reads since it was opened." state={state} className="col-span-12 md:col-span-6 xl:col-span-4">
      <ResponsiveContainer width="100%" height="100%">
        {area ? (
          <AreaChart data={data} margin={{ top: 4, right: 8, left: 0, bottom: 0 }}>
            {axes}
            {series.map((s) => (
              <Area
                key={s.key}
                type="monotone"
                dataKey={s.key}
                name={s.name}
                stroke={s.color}
                fill={s.color}
                fillOpacity={0.15}
                strokeWidth={2}
                isAnimationActive={false}
                connectNulls
              />
            ))}
          </AreaChart>
        ) : (
          <LineChart data={data} margin={{ top: 4, right: 8, left: 0, bottom: 0 }}>
            {axes}
            {series.map((s) => (
              <Line
                key={s.key}
                type="monotone"
                dataKey={s.key}
                name={s.name}
                stroke={s.color}
                strokeWidth={2}
                dot={false}
                isAnimationActive={false}
                connectNulls
              />
            ))}
          </LineChart>
        )}
      </ResponsiveContainer>
    </ChartCard>
  );
}

function DonutCard({
  title,
  state,
  caption,
  entries,
  colors = {},
  note,
}: {
  title: string;
  state: CardState;
  caption: string;
  entries: [string, number][];
  colors?: Record<string, string>;
  note: string;
}) {
  const total = entries.reduce((n, [, v]) => n + v, 0);
  const slices = entries.map(([name, value], i) => ({ name, value, color: colors[name] ?? SERIES[i % SERIES.length] }));
  return (
    <Card
      title={title}
      note={note}
      state={state.kind === "ok" && total === 0 ? { kind: "empty", note: "Nothing counted yet." } : state}
      className="col-span-12 md:col-span-6 xl:col-span-3"
    >
      <div className="flex flex-col items-center gap-4">
        <Donut slices={slices} centre={total.toLocaleString()} caption={caption} size={128} />
        <ul className="w-full space-y-1.5 text-sm">
          {slices.map((s) => (
            <li key={s.name} className="flex items-center justify-between gap-2">
              <span className="flex min-w-0 items-center gap-2">
                <span className="h-2 w-2 shrink-0 rounded-full" style={{ background: s.color }} aria-hidden />
                <span className="truncate">{s.name.replaceAll("_", " ").toLowerCase()}</span>
              </span>
              <span className="font-num shrink-0 tabular-nums" style={MUTED}>
                {Math.round(s.value).toLocaleString()} · {total ? Math.round((s.value / total) * 100) : 0}%
              </span>
            </li>
          ))}
        </ul>
      </div>
    </Card>
  );
}

/** A labelled bar: used against a ceiling, with the 70 / 90 bands and the figures as text. */
function Meter({ label, used, max, detail }: { label: string; used: number | null; max: number | null; detail?: string }) {
  const pct = percent(used, max);
  const tone = utilisationTone(pct);
  const color = tone ? TONE[tone] : "var(--accent-primary)";
  return (
    <div className="py-1.5">
      <div className="flex items-baseline justify-between gap-2 text-sm">
        <span className="truncate" title={label}>{label}</span>
        <span className="font-num shrink-0 text-xs tabular-nums" style={MUTED}>
          {detail ?? `${formatBytes(used)}${max != null ? ` / ${formatBytes(max)}` : " (no limit)"}`}
          {pct != null && <span className="ml-2 font-semibold" style={{ color }}>{pct.toFixed(1)}%</span>}
        </span>
      </div>
      <div
        className="mt-1 h-1.5 w-full overflow-hidden rounded-md"
        style={{ background: "var(--bg-raised)" }}
        role="img"
        aria-label={`${label}: ${pct == null ? "no limit" : `${pct}% used`}`}
      >
        <div className="h-full rounded-md" style={{ width: `${Math.min(pct ?? 0, 100)}%`, background: color }} />
      </div>
    </div>
  );
}

function MemoryPools({ report, state }: { report?: SystemHealthReport; state: CardState }) {
  const pools = report?.memory.pools ?? [];
  return (
    <Card
      title="Memory pools"
      note="Each JVM memory region, used against its own maximum. A region with no limit of its own draws no percentage."
      state={state}
      className="col-span-12 xl:col-span-8"
    >
      <Meter label="Heap (all regions)" used={report?.memory.heapUsed ?? null} max={report?.memory.heapMax ?? null} />
      <div className="mt-2 grid gap-x-8 lg:grid-cols-2">
        {pools.map((p) => (
          <Meter key={`${p.area}:${p.name}`} label={`${p.name} · ${p.area}`} used={p.used} max={p.max} />
        ))}
      </div>
    </Card>
  );
}

function Storage({ report, state }: { report?: SystemHealthReport; state: CardState }) {
  const db = report?.database;
  const diskUsed = db?.diskTotalBytes != null && db.diskFreeBytes != null ? db.diskTotalBytes - db.diskFreeBytes : null;
  return (
    <Card
      title="Database and disk"
      note="Disk is the volume this server writes to; the database figures are PostgreSQL's own."
      state={state}
      className="col-span-12 xl:col-span-4"
    >
      <Meter label="Server disk" used={diskUsed} max={db?.diskTotalBytes ?? null} detail={`${formatBytes(db?.diskFreeBytes)} free of ${formatBytes(db?.diskTotalBytes)}`} />
      <dl className="mt-3 space-y-1.5 text-sm">
        <Row k="Database size" v={formatBytes(db?.sizeBytes)} />
        <Row k="PostgreSQL" v={db?.serverVersion ?? "—"} />
        <Row k="Schema version" v={db?.schemaVersion ? `V${db.schemaVersion}` : "—"} />
        <Row k="Last migration" v={db?.schemaMigratedAt ? new Date(db.schemaMigratedAt).toLocaleString() : "—"} />
        <Row
          k="Failed migrations"
          v={fmt(db?.failedMigrations)}
          color={(db?.failedMigrations ?? 0) > 0 ? "var(--status-red)" : undefined}
        />
        <Row k="Round trip" v={formatMs(db?.roundTripMs)} />
        {db && !db.reachable && <Row k="Error" v={db.error ?? "unknown"} color="var(--status-red)" />}
      </dl>
    </Card>
  );
}

function Row({ k, v, color }: { k: string; v: string; color?: string }) {
  return (
    <div className="flex items-baseline justify-between gap-3">
      <dt className="shrink-0" style={MUTED}>{k}</dt>
      <dd className="font-num min-w-0 text-right break-words tabular-nums" style={color ? { color } : undefined}>{v}</dd>
    </div>
  );
}

function Facts({ title, state, rows, className }: { title: string; state: CardState; rows: [string, string][]; className: string }) {
  return (
    <Card title={title} state={state} className={className}>
      <dl className="space-y-1.5 text-sm">
        {rows.map(([k, v]) => <Row key={k} k={k} v={v} />)}
      </dl>
    </Card>
  );
}

function Components({ report, state }: { report?: SystemHealthReport; state: CardState }) {
  return (
    <Card
      title="Health checks"
      note="Spring Boot's health indicators, run on this read. Mail is deliberately not one: it degrades on its own."
      state={state}
      className="col-span-12"
    >
      <ul className="text-sm">
        {(report?.status.components ?? []).map((c) => (
          <li
            key={c.name}
            className="grid gap-x-4 gap-y-1 border-t py-2.5 first:border-t-0 lg:grid-cols-[10rem_8rem_1fr] lg:items-baseline"
            style={{ borderColor: "var(--border-default)" }}
          >
            <span className="flex items-center justify-between gap-2 font-medium lg:block">
              {c.name}
              <span className="lg:hidden"><StatusPill status={c.status} /></span>
            </span>
            <span className="hidden lg:block"><StatusPill status={c.status} /></span>
            <span className="font-num min-w-0 text-xs break-all tabular-nums" style={MUTED}>
              {Object.entries(c.details).map(([k, v]) => (
                <span key={k} className="mr-4 inline-block">
                  {k}: {typeof v === "number" && /total|free|threshold/i.test(k) ? formatBytes(v) : String(v)}
                </span>
              ))}
              {Object.keys(c.details).length === 0 && "No detail"}
            </span>
          </li>
        ))}
      </ul>
    </Card>
  );
}

function Routes({
  report,
  state,
  sortBy,
  onSort,
}: {
  report?: SystemHealthReport;
  state: CardState;
  sortBy: RouteSort;
  onSort: (by: RouteSort) => void;
}) {
  const routes = sortRoutes(report?.http.routes ?? [], sortBy);
  const slowest = Math.max(1, ...routes.map((r) => r.meanMs));
  return (
    <Card
      title="Busiest routes"
      note="The 25 busiest route templates since start. p95 is the worst of the route's status variants over roughly the last two minutes; blank when it has not been called lately."
      state={state.kind === "ok" && routes.length === 0 ? { kind: "empty", note: "No requests yet." } : state}
      className="col-span-12"
      action={
        <PillSelect label="Sort routes by" value={sortBy} onChange={(e) => onSort(e.target.value as RouteSort)}>
          <option value="count">Most calls</option>
          <option value="meanMs">Slowest mean</option>
          <option value="p95Ms">Slowest p95</option>
          <option value="maxMs">Slowest max</option>
          <option value="errors">Most errors</option>
        </PillSelect>
      }
    >
      {/* Phones and tablets get one card per route: seven columns need ~800 px, and a sideways-scrolling table hides the errors column. */}
      <ul className="grid gap-2 sm:grid-cols-2 lg:hidden">
        {routes.map((r) => (
          <li key={`${r.method} ${r.uri}`} className="rounded-xl p-3" style={{ background: "var(--bg-raised)" }}>
            <div className="flex min-w-0 items-start gap-2">
              <MethodBadge method={r.method} />
              <code className="min-w-0 text-xs break-all">{r.uri}</code>
            </div>
            <MeanBar meanMs={r.meanMs} slowest={slowest} className="mt-2" prefix="mean " />
            <dl className="font-num mt-2 grid grid-cols-3 gap-x-3 gap-y-1 text-xs tabular-nums">
              <MiniStat k="Calls" v={r.count.toLocaleString()} />
              <MiniStat k="p95" v={formatMs(r.p95Ms)} />
              <MiniStat k="Max" v={formatMs(r.maxMs)} />
              <MiniStat k="4xx" v={String(r.clientErrors)} color={r.clientErrors ? "var(--status-amber)" : undefined} />
              <MiniStat k="5xx" v={String(r.serverErrors)} color={r.serverErrors ? "var(--status-red)" : undefined} />
            </dl>
          </li>
        ))}
      </ul>
      <div className="hidden overflow-x-auto lg:block">
        <table className="w-full text-sm">
          <thead>
            <tr className="text-left text-xs" style={MUTED}>
              <th className="py-2 pr-3 font-medium">Route</th>
              <th className="py-2 pr-3 text-right font-medium">Calls</th>
              <th className="py-2 pr-3 font-medium">Mean</th>
              <th className="py-2 pr-3 text-right font-medium">p95</th>
              <th className="py-2 pr-3 text-right font-medium">Max</th>
              <th className="py-2 pr-3 text-right font-medium">4xx</th>
              <th className="py-2 text-right font-medium">5xx</th>
            </tr>
          </thead>
          <tbody className="font-num tabular-nums">
            {routes.map((r) => (
              <tr key={`${r.method} ${r.uri}`} className="border-t" style={{ borderColor: "var(--border-default)" }}>
                <td className="py-2 pr-3 font-sans">
                  <span className="flex items-center gap-2">
                    <MethodBadge method={r.method} />
                    <code className="text-xs">{r.uri}</code>
                  </span>
                </td>
                <td className="py-2 pr-3 text-right">{r.count.toLocaleString()}</td>
                <td className="py-2 pr-3">
                  <MeanBar meanMs={r.meanMs} slowest={slowest} />
                </td>
                <td className="py-2 pr-3 text-right text-xs">{formatMs(r.p95Ms)}</td>
                <td className="py-2 pr-3 text-right text-xs">{formatMs(r.maxMs)}</td>
                <td className="py-2 pr-3 text-right text-xs" style={r.clientErrors ? { color: "var(--status-amber)" } : MUTED}>
                  {r.clientErrors}
                </td>
                <td className="py-2 text-right text-xs" style={r.serverErrors ? { color: "var(--status-red)", fontWeight: 600 } : MUTED}>
                  {r.serverErrors}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </Card>
  );
}

function MethodBadge({ method }: { method: string }) {
  return (
    <span
      className="inline-block w-14 shrink-0 rounded px-1.5 py-0.5 text-center text-[0.6875rem] font-semibold"
      style={{ background: "var(--accent-soft)", color: "var(--accent-primary)" }}
    >
      {method}
    </span>
  );
}

function MeanBar({ meanMs, slowest, className = "", prefix = "" }: { meanMs: number; slowest: number; className?: string; prefix?: string }) {
  return (
    <div className={`flex items-center gap-2 ${className}`}>
      <div className="h-1.5 w-24 shrink-0 overflow-hidden rounded" style={{ background: "var(--bg-raised)" }} aria-hidden>
        <div className="h-full rounded" style={{ width: `${(meanMs / slowest) * 100}%`, background: TONE[routeTone(meanMs) ?? "good"] }} />
      </div>
      <span className="font-num text-xs tabular-nums">{prefix}{formatMs(meanMs)}</span>
    </div>
  );
}

function MiniStat({ k, v, color }: { k: string; v: string; color?: string }) {
  return (
    <div>
      <dt style={MUTED}>{k}</dt>
      <dd style={color ? { color, fontWeight: 600 } : undefined}>{v}</dd>
    </div>
  );
}

function Jobs({ report, state }: { report?: SystemHealthReport; state: CardState }) {
  const jobs = report?.jobs ?? [];
  return (
    <Card
      title="Background sweeps since start"
      note="Runs and failures counted by this server since it started. The run ledger with errors is on Background jobs."
      state={state.kind === "ok" && jobs.length === 0 ? { kind: "empty", note: "No sweep has run since this server started." } : state}
      className="col-span-12"
      action={
        <Link to="/admin/jobs" className="text-xs font-medium" style={{ color: "var(--accent-primary)" }}>
          Background jobs →
        </Link>
      }
    >
      <div className="grid gap-x-8 gap-y-1 md:grid-cols-2 xl:grid-cols-3">
        {jobs.map((j) => {
          const runs = j.ok + j.failed;
          return (
            <div key={j.job} className="py-1.5">
              <div className="flex items-baseline justify-between gap-2 text-sm">
                <span className="truncate">{j.job.replaceAll("_", " ").toLowerCase()}</span>
                <span className="font-num shrink-0 text-xs tabular-nums" style={MUTED}>
                  {j.ok} ok
                  {j.failed > 0 && <span className="ml-2 font-semibold" style={{ color: "var(--status-red)" }}>{j.failed} failed</span>}
                  {j.itemsFailed > 0 && <span className="ml-2" style={{ color: "var(--status-amber)" }}>{j.itemsFailed} items skipped</span>}
                </span>
              </div>
              <div className="mt-1 flex h-1.5 w-full overflow-hidden rounded-md" style={{ background: "var(--bg-raised)" }} aria-hidden>
                <div style={{ width: `${runs ? (j.ok / runs) * 100 : 0}%`, background: "var(--status-green)" }} />
                <div style={{ width: `${runs ? (j.failed / runs) * 100 : 0}%`, background: "var(--status-red)" }} />
              </div>
            </div>
          );
        })}
      </div>
    </Card>
  );
}
