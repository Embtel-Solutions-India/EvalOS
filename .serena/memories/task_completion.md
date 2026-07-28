# Task Completion

Run the checks for the half/halves touched, from inside that directory. There is no combined root
command and no formatter to run. See `mem:suggested_commands` for the PowerShell forms.

## frontend/ changes

1. `npm run lint` (oxlint)
2. `npm run build` (`tsc -b && vite build` — doubles as the typecheck; no standalone typecheck or
   test script)

No frontend test suite exists — if behavior matters, verify by running `npm run dev` alongside the
backend, since nothing else catches a runtime regression.

## backend/ changes

1. `.\mvnw.cmd verify` — compile + slice/unit tests, no Docker or database required. Use
   `clean verify` if surefire fails to discover tests (stale `target/`).
2. Touched an entity, migration, repository, converter or anything else persistence-shaped? Also run
   the gated DB test (`-Devalos.db.test=true`, see `mem:suggested_commands`) — `verify` alone never
   loads a `ddl-auto=validate` context, so a mapping/schema mismatch passes it silently. Prove a new
   migration on a throwaway database as well as the dev one.
3. If a check could not be run, **say so explicitly** rather than reporting it as passing.

## Every unit (from `context/ai-workflow-rules.md`)

Before moving to the next unit:
1. The unit works end to end within its defined scope.
2. No invariant in `context/architecture.md` is violated — especially brand scoping on every query,
   role+ownership before every mutation, `payment_detail` never exposed, an audit entry on every
   transition, thin handlers, GHL-only payment path, no files, no email.
3. `context/progress-tracker.md` reflects the completed work (mark in-progress when starting, and
   record deviations/unverified acceptance criteria when finishing).
4. Both verify commands above are green.

Full-stack slices: run both suites, then `npm run dev` (5173) + `.\mvnw.cmd spring-boot:run` (8080)
and exercise the flow through the Vite `/api` proxy.
