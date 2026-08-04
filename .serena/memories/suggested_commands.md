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
- Database checks (migrations apply, `ddl-auto=validate` agrees with every entity, `payment_detail` is
  ciphertext, scoped finders separate two brands, audit rows cannot be edited) are one opt-in command:
  ```
  .\mvnw.cmd test "-Devalos.db.test=true" "-Dtest=LocalPostgresIntegrationTest"
  ```
  **Quote each `-D…` in PowerShell** — unquoted, `-Devalos.db.test=true` is split and Maven reports
  `Unknown lifecycle phase ".db.test=true"`. The suite runs in its own `evalos_test` schema off
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
  `/api/webhooks/ghl/local-ie-webhook-token` with `X-Evalos-Signature: sha256=<hex HMAC-SHA256 of the
  exact body>` keyed on `local-ie-webhook-secret` (`V901`), body:
  ```json
  {"event_type":"contact.created","event_id":"evt-<unique>",
   "contact":{"ghl_contact_id":"ghl-<unique>","full_name":"Anita Rao","email":"<unique>@example.test",
              "client_type":"INDIVIDUAL","source":"WEBSITE"},
   "service_type":"EXPERT_OPINION_LETTER","visa_category":"EB2_NIW","quote_amount":900,
   "drive_link":"https://drive.google.com/drive/folders/<anything>"}
  ```
  `event_type` and `event_id` are the **gateway's** fields and are easy to miss — without the first it
  is `400 MISSING_EVENT_TYPE`, without both it is `400 MISSING_EXTERNAL_ID`. `service_type` is
  top-level, not inside `contact`. Use a fresh email/GHL id each time: `V15`/`V16` refuse a second
  open case for the same contact and service.
