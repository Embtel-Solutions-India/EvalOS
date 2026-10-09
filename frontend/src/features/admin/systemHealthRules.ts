import type { KpiTone } from "../../components/ui/card";
import type { SystemHealthReport } from "./systemHealthApi";

/**
 * The System health page's arithmetic, apart from its drawing so it can be tested: units, health bands,
 * and the rates and history that two reads of since-start counters make possible.
 */

/** How many reads the charts keep. At the default 5 s refresh that is the last ten minutes. */
export const HISTORY_LIMIT = 120;

export function formatBytes(bytes: number | null | undefined): string {
  if (bytes == null) return "—";
  const units = ["B", "KB", "MB", "GB", "TB"];
  let value = bytes;
  let unit = 0;
  while (Math.abs(value) >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit++;
  }
  return `${value >= 100 || unit === 0 ? Math.round(value) : value.toFixed(1)} ${units[unit]}`;
}

/** `93784000` → `1d 2h 3m`; under a minute, seconds. */
export function formatDuration(ms: number | null | undefined): string {
  if (ms == null) return "—";
  const s = Math.floor(ms / 1000);
  const d = Math.floor(s / 86400);
  const h = Math.floor((s % 86400) / 3600);
  const m = Math.floor((s % 3600) / 60);
  if (d > 0) return `${d}d ${h}h ${m}m`;
  if (h > 0) return `${h}h ${m}m`;
  if (m > 0) return `${m}m ${s % 60}s`;
  return `${s}s`;
}

export function formatMs(ms: number | null | undefined): string {
  if (ms == null) return "—";
  if (ms >= 1000) return `${(ms / 1000).toFixed(2)} s`;
  return `${ms >= 10 ? Math.round(ms) : ms.toFixed(1)} ms`;
}

/** A whole-number percentage of `of`, or null when either side is unknown or the denominator is zero. */
export function percent(part: number | null | undefined, of: number | null | undefined): number | null {
  if (part == null || of == null || of <= 0) return null;
  return Math.round((part / of) * 1000) / 10;
}

/** Green / amber / red for a utilisation figure — the same bands the capacity bars use (70 / 90). */
export function utilisationTone(pct: number | null): KpiTone | undefined {
  if (pct == null) return undefined;
  return pct > 90 ? "bad" : pct >= 70 ? "warn" : "good";
}

/** Server errors as a share of requests: any is worth a look, over 5% is an incident. */
export function errorRateTone(pct: number | null): KpiTone | undefined {
  if (pct == null) return undefined;
  return pct > 5 ? "bad" : pct > 0 ? "warn" : "good";
}

/** A database round trip on the same network should be a few milliseconds. */
export function latencyTone(ms: number | null): KpiTone | undefined {
  if (ms == null) return undefined;
  return ms > 200 ? "bad" : ms > 50 ? "warn" : "good";
}

/** A staff API route: under 300 ms feels instant, over a second is a complaint. */
export function routeTone(ms: number | null): KpiTone | undefined {
  if (ms == null) return undefined;
  return ms > 1000 ? "bad" : ms > 300 ? "warn" : "good";
}

/** One point on the live charts, derived from a read and the read before it. */
export type Sample = {
  t: number;
  label: string;
  heapUsedMb: number | null;
  heapCommittedMb: number | null;
  processCpu: number | null;
  systemCpu: number | null;
  /** Requests and 5xx per second since the previous read; null on the first read. */
  requestsPerSec: number | null;
  errorsPerSec: number | null;
  poolActive: number | null;
  poolIdle: number | null;
  poolPending: number | null;
  threadsLive: number | null;
  busyThreads: number | null;
  dbRoundTripMs: number | null;
};

const mb = (bytes: number | null) => (bytes == null ? null : Math.round((bytes / 1048576) * 10) / 10);
const pct = (ratio: number | null) => (ratio == null ? null : Math.round(ratio * 1000) / 10);

/**
 * A rate between two since-start counters. Null when there is no previous read, no time has passed, or
 * the counter went backwards — which means the process restarted between reads, not negative traffic.
 */
export function rate(current: number | null, previous: number | null, seconds: number): number | null {
  if (current == null || previous == null || seconds <= 0 || current < previous) return null;
  return Math.round(((current - previous) / seconds) * 100) / 100;
}

export function toSample(report: SystemHealthReport, previous: SystemHealthReport | null): Sample {
  const t = Date.parse(report.at);
  const seconds = previous ? (t - Date.parse(previous.at)) / 1000 : 0;
  const errors = (r: SystemHealthReport) => r.http.byStatusClass["5xx"] ?? 0;
  return {
    t,
    label: new Date(t).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", second: "2-digit" }),
    heapUsedMb: mb(report.memory.heapUsed),
    heapCommittedMb: mb(report.memory.heapCommitted),
    processCpu: pct(report.cpu.process),
    systemCpu: pct(report.cpu.system),
    requestsPerSec: previous ? rate(report.http.requests, previous.http.requests, seconds) : null,
    errorsPerSec: previous ? rate(errors(report), errors(previous), seconds) : null,
    poolActive: report.pool.active,
    poolIdle: report.pool.idle,
    poolPending: report.pool.pending,
    threadsLive: report.threads.live,
    busyThreads: report.web.busyThreads,
    dbRoundTripMs: report.database.roundTripMs == null ? null : Math.round(report.database.roundTripMs * 10) / 10,
  };
}

/** Appends a sample, dropping the oldest past the limit. A read older than the last one (a slow response) is ignored. */
export function pushSample(history: Sample[], sample: Sample, limit = HISTORY_LIMIT): Sample[] {
  if (history.length > 0 && sample.t <= history[history.length - 1].t) return history;
  const next = [...history, sample];
  return next.length > limit ? next.slice(next.length - limit) : next;
}

export type RouteSort = "count" | "meanMs" | "p95Ms" | "maxMs" | "errors";

export function sortRoutes<R extends { count: number; meanMs: number; p95Ms: number | null; maxMs: number; clientErrors: number; serverErrors: number }>(
  routes: R[],
  by: RouteSort,
): R[] {
  const key = (r: R) => (by === "errors" ? r.serverErrors * 1e6 + r.clientErrors : by === "p95Ms" ? (r.p95Ms ?? -1) : r[by]);
  return [...routes].sort((a, b) => key(b) - key(a));
}
