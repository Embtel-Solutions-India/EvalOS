import { api, unwrap } from "../../lib/api";

/**
 * `GET /api/system/health` (D82), Admin-only: the server's own state, never a brand's rows. Mirrors
 * `SystemHealthService.Report`. `null` means "not measured on this platform", never zero. Every counter
 * is since the process started; the page derives rates from two reads.
 */
export type SystemHealthReport = {
  at: string;
  status: {
    overall: "UP" | "DOWN";
    liveness: string;
    readiness: string;
    components: { name: string; status: string; details: Record<string, string | number | boolean> }[];
  };
  runtime: {
    application: string;
    version: string | null;
    builtAt: string | null;
    profiles: string[];
    javaVersion: string;
    javaVendor: string;
    os: string;
    processors: number;
    pid: number;
    startedAt: string;
    uptimeMs: number;
    zone: string;
    loadedClasses: number | null;
  };
  database: {
    reachable: boolean;
    error: string | null;
    roundTripMs: number | null;
    serverVersion: string | null;
    sizeBytes: number | null;
    serverConnections: Record<string, number>;
    schemaVersion: string | null;
    schemaMigratedAt: string | null;
    failedMigrations: number | null;
    diskFreeBytes: number | null;
    diskTotalBytes: number | null;
  };
  memory: {
    heapUsed: number | null;
    heapCommitted: number | null;
    heapMax: number | null;
    nonHeapUsed: number | null;
    nonHeapCommitted: number | null;
    pools: { name: string; area: string; used: number | null; max: number | null }[];
  };
  gc: {
    pauses: number;
    pauseTotalMs: number;
    pauseMaxMs: number;
    allocatedBytes: number | null;
    promotedBytes: number | null;
    liveDataBytes: number | null;
    maxDataBytes: number | null;
    overhead: number | null;
  };
  threads: { live: number | null; daemon: number | null; peak: number | null; states: Record<string, number> };
  cpu: { process: number | null; system: number | null; processors: number | null; loadAverage: number | null };
  pool: {
    active: number | null;
    idle: number | null;
    pending: number | null;
    total: number | null;
    max: number | null;
    min: number | null;
    timeouts: number | null;
    acquireMeanMs: number | null;
    acquireMaxMs: number | null;
    usageMeanMs: number | null;
    usageMaxMs: number | null;
  };
  web: { busyThreads: number | null; currentThreads: number | null; maxThreads: number | null };
  http: {
    requests: number;
    byStatusClass: Record<string, number>;
    meanMs: number;
    maxMs: number;
    routes: HttpRoute[];
  };
  logs: { byLevel: Record<string, number> };
  auth: { signInsOk: number | null; signInsRefused: number | null };
  jobs: { job: string; ok: number; failed: number; itemsFailed: number }[];
};

export type HttpRoute = {
  method: string;
  uri: string;
  count: number;
  meanMs: number;
  maxMs: number;
  /** Worst of the route's status variants over Micrometer's ~2-minute window; null when it has not been hit lately. */
  p95Ms: number | null;
  clientErrors: number;
  serverErrors: number;
};

export function fetchSystemHealth(signal?: AbortSignal): Promise<SystemHealthReport> {
  return unwrap(api.get("/system/health", { signal }));
}
