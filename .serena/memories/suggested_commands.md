# Suggested Commands

Dev machine is **Windows / PowerShell**. There is no root-level runner: every command runs from
inside `frontend/` or `backend/`.

PowerShell notes that bite here:
- `&&` is unavailable in Windows PowerShell 5.1 — use `cd frontend; if ($?) { npm run build }`.
- Use `.\mvnw.cmd`, never `./mvnw` (the shell script is for unix; README text still says `./mvnw`).

## frontend/ (npm)

- `npm run dev` — Vite dev server on **port 5173, fixed** in `vite.config.ts`; proxies `/api` to
  `localhost:8080`.
- `npm run build` — `tsc -b && vite build`. Also the only typecheck entrypoint (no separate
  `typecheck` script); `tsc -b` uses project references so it checks app + node configs.
- `npm run lint` — oxlint. `npm run test` — vitest, one run (rules modules only). `npm run preview` —
  serve the build (no proxy; needs `VITE_API_BASE_URL`).

## backend/ (Maven Wrapper)

- `.\mvnw.cmd verify` — compile + slice/unit tests. **No Docker and no database needed**; the
  DB-dependent tests skip themselves. If surefire reports "TestEngine ... failed to discover tests",
  it is stale output from the old scaffold: run `.\mvnw.cmd clean verify` once.
- **Database checks now run in the plain `test`/`verify` run** whenever a Postgres is reachable
  (changed 2026-08-26) — migrations apply, `ddl-auto=validate` agrees with every entity,
  `payment_detail` is ciphertext, scoped finders separate two brands, audit rows cannot be edited, and
  the funnel cache's unique key and optimistic lock hold. If they report as skipped, the probe could
  not connect and prints the reason as `[db] ... skipped`.
- To force the suite on (CI does) or off, and to run it alone:
  ```
  .\mvnw.cmd test "-Devalos.db.test=true" "-Dtest=LocalPostgresIntegrationTest"
  ```
  **Quote each `-D…` in PowerShell** — unquoted, `-Devalos.db.test=true` is split and Maven reports
  `Unknown lifecycle phase ".db.test=true"`. `-Devalos.db.test=false` forces it off.
  The suite runs in its own `evalos_test` schema off
  `DB_TEST_URL` (default localhost/evalos), so it never writes next to dev data; point `DB_TEST_URL`
  at a throwaway database to prove every migration applies from scratch.
- `.\mvnw.cmd spring-boot:run` — starts on 8080 under the `local` profile. **Requires a reachable
  Postgres** (Flyway + `ddl-auto: validate` run at startup); with no DB the context fails to refresh.
- **This machine has PostgreSQL 18**, superuser `postgres`/`1234`, database `evalos` — `public` at
  `V22` + the `V90x` seeds, `evalos_test` at `V23` (the gated suite migrates its own schema, so the
  two drift apart until the app is next run). That is exactly what the `local` profile defaults to, so
  no env vars are needed. `psql` is
  not on `PATH`; it lives at `C:\Program Files\PostgreSQL\18\bin` — and it is **not on `PATH` for the
  agent either**, so to read rows directly the cheapest route is a single-file JDBC script:
  `java -cp <~/.m2/.../postgresql-*.jar> Peek.java` (watch for a UTF-8 BOM if PowerShell wrote the
  file — `javac` rejects it). Still no Docker daemon.
  Override with `DB_URL` / `DB_USER` / `DB_PASSWORD`; `SPRING_PROFILES_ACTIVE=prod` requires those
  plus `JWT_SECRET` and `EVALOS_FIELD_KEY`.
- Smoke check: `GET /api/health`, `/actuator/health`, then log in as `gm@evalos.local` /
  `DevPassw0rd!` and call `/api/me` and `/api/team-members` with the bearer token.
- **Driving a real case in from Handoff A** (the only way to get a case with a client name, a
  checklist and a contact snapshot — everything downstream needs one). POST to
  `/api/webhooks/ghl/local-ie-webhook-token` — **no signature header and no bearer token**, just
  `Content-Type: application/json` and the body (the inbound HMAC was removed 2026-08-27 because GHL's
  Custom Webhook action cannot compute one):
  ```json
  {"contact_id":"ghl-<unique>","first_name":"Anita","last_name":"Rao","full_name":"Anita Rao",
   "email":"<unique>@example.test","phone":"+1 555 0100",
   "customData":{"event_type":"opportunity.won","service_type":"EXPERT_OPINION_LETTER",
                 "opportunity_id":"opp-<unique>","amount":900}}
  ```
  **This is the shape GHL really sends** (confirmed 2026-09-02), not the nested
  `opportunity`/`contact` envelope the design assumed. The Custom Webhook is wired to GHL's
  **Contact lookup**, so GHL writes a contact record: the person **flat at the top level**. A
  top-level `contact` key in a real GHL body holds *attribution data*, not the contact; nothing
  reads it. **GHL writes no deal at all** — the `customData` block is the workflow author's, set in
  the GHL UI, and is the only place `event_type`, the service and the money can come from.
  `contact_id` is the client id and the only field that cannot be missing. Omitting `event_type`
  from both places is `400 MISSING_EVENT_TYPE`; omitting `event_id` is fine — the key falls back to
  a digest of the body, so **reuse the same body and you get `duplicate`**, change one character and
  you get a new event. `service_type` defaults to `CREDENTIAL_EVALUATION` but **send it** — it is
  half of `V15`'s key, so on the default a contact can only hold one open case at a time. `amount`
  must be positive where present. Use a fresh email/contact id and opportunity id each time:
  `V15`/`V16` refuse a second open case for the same contact and service, `V24` for the same
  opportunity.

  **Do not fire `contact.created`** — it is a recognized no-op since Case Creation v2.0 (spec `05b`)
  and creates nothing. It answers `200 accepted`, so it looks like it worked; the case never appears.
  The case it does create arrives **paid**, and there is no `mark-paid` call to follow up with.
- **Firing a background sweep by hand** — once Unit 19 exists, and only then; the `job` package is an
  empty placeholder today. `POST /api/jobs/{jobType}/run` with a **GM** bearer token, and
  `GET /api/jobs/runs` for the ledger (last run, duration, items seen/acted, failures). **Safe to press
  twice** by design: each sweep's idempotency comes from the data it reads, not from having-not-run-yet,
  which is the point of having the button — after an outage somebody can catch up deliberately instead
  of waiting for the next tick. Note the sweeps are **disabled in the test profile**
  (`evalos.jobs.enabled=false`), so an integration test that wants one runs it through this route.
