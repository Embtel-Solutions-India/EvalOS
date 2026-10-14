import { describe, expect, it } from "vitest";
import {
  errorRateTone,
  formatBytes,
  formatDuration,
  formatMs,
  latencyTone,
  percent,
  pushSample,
  rate,
  routeTone,
  sortRoutes,
  toSample,
  utilisationTone,
  type Sample,
} from "./systemHealthRules";
import type { SystemHealthReport } from "./systemHealthApi";

function report(at: string, requests: number, errors5xx: number, allocated: number): SystemHealthReport {
  return {
    at,
    status: { overall: "UP", liveness: "CORRECT", readiness: "ACCEPTING_TRAFFIC", components: [] },
    runtime: {
      application: "evalos", version: null, builtAt: null, profiles: ["prod"], javaVersion: "21", javaVendor: "x",
      os: "Linux", processors: 4, pid: 1, startedAt: at, uptimeMs: 1000, zone: "UTC", loadedClasses: null,
    },
    database: {
      reachable: true, error: null, roundTripMs: 1.234, serverVersion: "16", sizeBytes: 1, serverConnections: {},
      schemaVersion: "89", schemaMigratedAt: null, failedMigrations: 0, diskFreeBytes: null, diskTotalBytes: null,
    },
    memory: { heapUsed: 104857600, heapCommitted: 209715200, heapMax: 419430400, nonHeapUsed: null, nonHeapCommitted: null, pools: [] },
    gc: { pauses: 0, pauseTotalMs: 0, pauseMaxMs: 0, allocatedBytes: allocated, promotedBytes: null, liveDataBytes: null, maxDataBytes: null, overhead: null },
    threads: { live: 40, daemon: 30, peak: 42, states: {} },
    cpu: { process: 0.125, system: null, processors: 4, loadAverage: null },
    pool: { active: 1, idle: 9, pending: 0, total: 10, max: 10, min: 10, timeouts: 0, acquireMeanMs: null, acquireMaxMs: null, usageMeanMs: null, usageMaxMs: null },
    web: { busyThreads: null, currentThreads: null, maxThreads: null },
    http: { requests, byStatusClass: { "2xx": requests - errors5xx, "5xx": errors5xx }, meanMs: 0, maxMs: 0, routes: [] },
    logs: { byLevel: {} },
    auth: { signInsOk: null, signInsRefused: null },
    jobs: [],
  };
}

describe("formatting", () => {
  it("scales bytes to the largest whole unit", () => {
    expect(formatBytes(512)).toBe("512 B");
    expect(formatBytes(1536)).toBe("1.5 KB");
    expect(formatBytes(419430400)).toBe("400 MB");
    expect(formatBytes(null)).toBe("—");
  });

  it("reads an uptime as days, hours and minutes", () => {
    expect(formatDuration(93_784_000)).toBe("1d 2h 3m");
    expect(formatDuration(3_720_000)).toBe("1h 2m");
    expect(formatDuration(42_000)).toBe("42s");
  });

  it("keeps a decimal only where it carries information", () => {
    expect(formatMs(1.234)).toBe("1.2 ms");
    expect(formatMs(87.6)).toBe("88 ms");
    expect(formatMs(2500)).toBe("2.50 s");
  });
});

describe("health bands", () => {
  it("uses the 70 / 90 capacity bands", () => {
    expect(utilisationTone(69.9)).toBe("good");
    expect(utilisationTone(70)).toBe("warn");
    expect(utilisationTone(90.1)).toBe("bad");
    expect(utilisationTone(null)).toBeUndefined();
  });

  it("flags any server error and calls 5% an incident", () => {
    expect(errorRateTone(0)).toBe("good");
    expect(errorRateTone(0.1)).toBe("warn");
    expect(errorRateTone(5.1)).toBe("bad");
  });

  it("expects a database round trip in single-digit milliseconds", () => {
    expect(latencyTone(3)).toBe("good");
    expect(latencyTone(80)).toBe("warn");
    expect(latencyTone(250)).toBe("bad");
  });

  it("calls a route slow past 300 ms and a complaint past a second", () => {
    expect(routeTone(120)).toBe("good");
    expect(routeTone(450)).toBe("warn");
    expect(routeTone(1500)).toBe("bad");
  });

  it("has no percentage of an unknown or zero denominator", () => {
    expect(percent(25, 100)).toBe(25);
    expect(percent(1, null)).toBeNull();
    expect(percent(1, 0)).toBeNull();
  });
});

describe("rates from since-start counters", () => {
  it("divides the change by the seconds between reads", () => {
    expect(rate(150, 100, 5)).toBe(10);
  });

  it("has no rate on the first read or across a restart", () => {
    expect(rate(150, null, 5)).toBeNull();
    expect(rate(3, 100, 5)).toBeNull();
    expect(rate(150, 100, 0)).toBeNull();
  });

  it("turns two reads into requests and errors per second", () => {
    const first = report("2026-10-10T10:00:00Z", 100, 1, 0);
    const second = report("2026-10-10T10:00:10Z", 300, 3, 104857600);

    expect(toSample(first, null).requestsPerSec).toBeNull();
    const sample = toSample(second, first);
    expect(sample.requestsPerSec).toBe(20);
    expect(sample.errorsPerSec).toBe(0.2);
    expect(sample.heapUsedMb).toBe(100);
    expect(sample.processCpu).toBe(12.5);
    expect(sample.dbRoundTripMs).toBe(1.2);
  });
});

describe("history", () => {
  const at = (t: number) => ({ t }) as Sample;

  it("keeps only the newest samples", () => {
    const history = [1, 2, 3].map(at);
    expect(pushSample(history, at(4), 3).map((s) => s.t)).toEqual([2, 3, 4]);
  });

  it("ignores a read that arrived out of order", () => {
    const history = [at(5)];
    expect(pushSample(history, at(4))).toBe(history);
  });
});

describe("route ordering", () => {
  const routes = [
    { uri: "a", count: 10, meanMs: 5, p95Ms: null, maxMs: 9, clientErrors: 0, serverErrors: 0 },
    { uri: "b", count: 2, meanMs: 50, p95Ms: 80, maxMs: 90, clientErrors: 3, serverErrors: 0 },
    { uri: "c", count: 5, meanMs: 1, p95Ms: 2, maxMs: 300, clientErrors: 0, serverErrors: 1 },
  ];

  it("sorts by the chosen column, server errors before client errors", () => {
    expect(sortRoutes(routes, "count").map((r) => r.uri)).toEqual(["a", "c", "b"]);
    expect(sortRoutes(routes, "p95Ms").map((r) => r.uri)).toEqual(["b", "c", "a"]);
    expect(sortRoutes(routes, "errors").map((r) => r.uri)).toEqual(["c", "b", "a"]);
  });
});
