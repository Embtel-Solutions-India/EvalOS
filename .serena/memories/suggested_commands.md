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

- `.\mvnw.cmd verify` — compile + slice tests. **No Docker needed** (Testcontainers was removed).
  If surefire reports "TestEngine ... failed to discover tests", it is stale output from the old
  scaffold: run `.\mvnw.cmd clean verify` once.
- `.\mvnw.cmd spring-boot:run` — starts on 8080 under the `local` profile. **Requires a reachable
  Postgres** (Flyway + `ddl-auto: validate` run at startup); with no DB the context fails to refresh.
  There is no Docker daemon and no local `psql` on this machine as of Unit 01, so a real end-to-end
  start is unverified — say so rather than claiming it works.
  ```sql
  CREATE ROLE evalos LOGIN PASSWORD 'evalos';
  CREATE DATABASE evalos OWNER evalos;
  ```
  Or override with `DB_URL` / `DB_USER` / `DB_PASSWORD`; `SPRING_PROFILES_ACTIVE=prod` requires them.
- Smoke check: `GET http://localhost:8080/api/health` and `/actuator/health`.
