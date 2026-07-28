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
- `npm run lint` — oxlint. `npm run preview` — serve the build (no proxy; needs `VITE_API_BASE_URL`).

## backend/ (Maven Wrapper)

- `.\mvnw.cmd verify` — compile + slice/unit tests. **No Docker and no database needed**; the
  DB-dependent tests skip themselves. If surefire reports "TestEngine ... failed to discover tests",
  it is stale output from the old scaffold: run `.\mvnw.cmd clean verify` once.
- Database checks (migrations apply, `ddl-auto=validate` agrees with every entity, `payment_detail` is
  ciphertext, scoped finders separate two brands, audit rows cannot be edited) are one opt-in command:
  ```
  .\mvnw.cmd test -Devalos.db.test=true -Dtest=LocalPostgresIntegrationTest -DDB_URL=jdbc:postgresql://localhost:5432/evalos
  ```
  Point `-DDB_URL` at a throwaway database to prove `V1`–`V10` apply from scratch.
- `.\mvnw.cmd spring-boot:run` — starts on 8080 under the `local` profile. **Requires a reachable
  Postgres** (Flyway + `ddl-auto: validate` run at startup); with no DB the context fails to refresh.
- **This machine has PostgreSQL 18**, superuser `postgres`/`1234`, database `evalos` migrated to
  `V10` — which is exactly what the `local` profile defaults to, so no env vars are needed. `psql` is
  not on `PATH`; it lives at `C:\Program Files\PostgreSQL\18\bin`. Still no Docker daemon.
  Override with `DB_URL` / `DB_USER` / `DB_PASSWORD`; `SPRING_PROFILES_ACTIVE=prod` requires those
  plus `JWT_SECRET` and `EVALOS_FIELD_KEY`.
- Smoke check: `GET /api/health`, `/actuator/health`, then log in as `gm@evalos.local` /
  `DevPassw0rd!` and call `/api/me` and `/api/team-members` with the bearer token.
