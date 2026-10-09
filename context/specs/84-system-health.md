# 84 — System health page (D82)

**Status:** built 2026-10-10. **Audience:** the Administrator only.

## What it answers

"Is the server healthy right now, and if not, which part?" — without shell access to the host, and
without Actuator being reachable from the public site.

## Shape

- **Read:** `GET /api/system/health` → `SystemHealthService.Report`. `/api/system` is an Admin area
  (`AdminAllowlist.ADMIN_AREAS`), and the handler carries `@PreAuthorize("hasRole('ADMIN')")`.
  It travels through nginx like every `/api` call; `/actuator` stays compose-network only.
- **Sources:** Boot's health indicators (flattened), `ApplicationAvailability` (liveness/readiness),
  the Micrometer registry (the same meters `/actuator/metrics` serves), `BuildProperties`, and five
  reads against PostgreSQL: `select 1` timed, `server_version`, `pg_database_size` (cached 60 s —
  it walks the files, ~300 ms), `pg_stat_activity` by state, Flyway's latest version and failures.
- **No brand data.** Every figure is about the process and its database server, never a brand's rows,
  so there is no brand scope to apply. HTTP routes are templates (`/api/cases/{id}`), never ids.
- **Page:** the Administrator's `/dashboard` renders `SystemHealthPage` (2026-10-10; it was `/admin/system`
  with its own nav entry for one day). The previous Admin dashboard's cards moved to the screens they
  describe (`adminSummaries.tsx`): Staff, Pipelines, Sync health and Background jobs. Polls every 5 s (15 s / 60 s /
  paused selectable). Status strip; eight KPI tiles with health colour and sparklines; six live
  charts; four donuts (status classes, log levels, thread states, DB sessions); memory pools and
  disk meters; GC, pool and runtime facts; health-check table; 25 busiest routes, sortable; sweep
  runs since start.

## Phone and tablet

Fully responsive, checked at 390 px and 768 px with no horizontal scroll. Tiles, charts, donuts and
panels stack to one column on a phone and two on a tablet. The routes table and the health-check grid
are cards / stacked rows below 1024 px (seven columns need ~800 px of content), with the route sort as
a native select. **The staff shell gained a phone mode for this** (2026-10-10): below 768 px the nav is
a drawer opened from a menu button in the top bar (`AppShell` state, closes on navigation, Escape or
the backdrop), the content takes the full width, and Sign out is icon-only. 768 px and up are unchanged.

## Honesty rules

- Gauges are *now*; counters are *since the process started* (the footer names when).
- The charts are the page's own reads held in memory (up to 120), not stored history — EvalOS keeps
  none (D82). Closing the page discards them.
- A meter that does not exist on the platform is `null` and renders "—", never 0.
- Health bands: utilisation 70 / 90 (the capacity-bar bands); server error rate > 0 amber, > 5 % red;
  DB round trip > 50 ms amber, > 200 ms red; a route's mean > 300 ms amber, > 1 s red.

## Not in scope

Logs in the UI (they stay in the container output, found by request id), alerting, and history.
Each needs storage EvalOS does not have; each is its own decision.

## Evidence

`ActuatorEndpointsTest.theSystemHealthReportIsTheAdminsAndCarriesEverySection` (real Postgres:
Admin 200 with every section, GM/BM/CM 403, anonymous 401, no email in the body),
`AdminAllowlistTest`, `AdminAreasPreAuthorizeTest`, `systemHealthRules.test.ts`. Browser-checked
at 1536, 768 and 390 px against a local backend, including the phone drawer (open, close on
navigation, close on Escape).
