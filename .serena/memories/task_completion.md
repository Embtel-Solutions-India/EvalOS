# Task Completion

Run the checks for the part(s) touched, from inside that directory. **Three apps, no combined
root command, no formatter.** See `mem:suggested_commands` for the PowerShell forms.

## frontend/ changes

1. `npm run lint` (oxlint)
2. `npm run build` (`tsc -b && vite build` — doubles as the typecheck; there is no standalone
   typecheck script)
3. `npm run test` (vitest, one run) — **rules modules only**. Components are not render-tested
   (no jsdom, no Testing Library), so a `.tsx` change catches nothing here: if behavior matters,
   verify by running `npm run dev` alongside the backend.

## client-expert/ changes (the portal frontends)

All commands run from `client-expert/`, which holds the one `package.json` for both apps.

1. `npm run lint` (oxlint)
2. `npm run build` — **both apps** (`build:client` then `build:expert`, each `tsc -b && vite
   build` in its own folder; the only typecheck). Build just one with `npm run build:client` /
   `npm run build:expert`, but **run both before calling a change done**: they share
   `shared/src`, so a change there can break the app you did not build.
3. `npm run test` (vitest, one run, all three folders) — **pure rules modules only**
   (`shared/src/lib/portal`), same discipline as `frontend/`: no jsdom, no Testing Library, so a
   `.tsx` change catches nothing here. Verify a component by running `npm run dev:client` (5174)
   or `npm run dev:expert` (5175) against the backend.

## backend/ changes

1. `.\mvnw.cmd verify` — compile + slice/unit tests, no Docker or database required. Use
   `clean verify` if surefire fails to discover tests (stale `target/`).
2. Touched an entity, migration, repository, converter or anything else persistence-shaped? **Check
   the DB suite actually RAN rather than skipped.** Since 2026-08-26 `LocalPostgresIntegrationTest`
   runs automatically whenever Postgres is reachable, so `verify` does load a `ddl-auto=validate`
   context — but if the probe cannot connect it skips, and a mapping/schema mismatch then passes
   silently exactly as before. `Skipped: 4` is the healthy number (the opt-in live-GHL tests);
   anything higher means the DB tests did not run. Force them with `-Devalos.db.test=true` to be
   certain, and prove a new migration on a throwaway database as well as the dev one.
3. If a check could not be run, **say so explicitly** rather than reporting it as passing.

## Every unit (from `context/ai-workflow-rules.md`)

Before moving to the next unit:
1. The unit works end to end within its defined scope.
2. No invariant in `context/architecture.md` is violated — especially brand scoping on every query,
   role+ownership before every mutation, `payment_detail` never exposed, an audit entry on every
   transition, thin handlers, GHL-webhook-only case creation, no files, no email.
3. `context/progress-tracker.md` reflects the completed work (mark in-progress when starting, and
   record deviations/unverified acceptance criteria when finishing).
4. Every verify command above, for the halves touched, is green.
5. The memories this unit invalidated are updated — see `mem:memory_maintenance` for the
   add/update threshold and the routing rules.

Full-stack **staff** slices: run both suites, then `npm run dev` (5173) + `.\mvnw.cmd
spring-boot:run` (8080) and exercise the flow through the Vite `/api` proxy.

Full-stack **portal** slices: `client-expert/` `npm run dev:client` (5174) or `npm run
dev:expert` (5175) + the backend on 8080. **There is no proxy here — the call is genuinely
cross-origin**, so `evalos.portal.allowed-origins` must name that origin (`local` defaults to
both) and a failure shows up as a
preflight rejection in the browser while the same call passes a curl test. Check the Network
tab's OPTIONS request before blaming the token.
