# Unit 01 — Project scaffold & config

**Phase:** 1 — Structure the data (the spine)
**Depends on:** nothing
**Unlocks:** every later unit
**Gating open questions:** none

## Goal

A running monorepo skeleton: a Spring Boot (Java 21) backend that connects to
PostgreSQL, has Flyway wired for migrations, and exposes a health endpoint; and
a React/Vite + Tailwind frontend carrying the design tokens from
`ui-context.md`. No domain, no auth, no features — just the ground both halves
stand on. **Verifiable result:** `./mvnw verify` and `npm run build` are both
green, the app starts, `GET /api/health` returns `UP`, and the frontend dev page
renders that status.

## In scope

- Monorepo layout: `backend/` (Spring Boot, Maven) + `frontend/` (React/Vite).
- Backend: Spring Web, Spring Data JPA, PostgreSQL driver, Flyway, Bean
  Validation, Actuator. Base package `com.ie.evalos` with empty package
  skeleton (`web`, `service`, `domain`, `repository`, `integration`, `webhook`,
  `event`, `job`, `notification`, `security`, `common`, `config`).
- Config profiles (`local`, `prod`) with all secrets/URLs externalized to env.
- Flyway enabled with a minimal baseline migration.
- Frontend: React + TypeScript + Vite + Tailwind, design tokens as CSS custom
  properties, a single page that fetches and shows backend health.

## Out of scope

- Auth / security (Unit 02), any entity or table beyond the Flyway baseline
  (Unit 03), any feature endpoint or UI.

## Deliverables

1. **Monorepo tree**
   ```
   /backend        Spring Boot (Maven), Java 21, base pkg com.ie.evalos
   /frontend       React + TS + Vite + Tailwind
   /README.md      run instructions
   ```
2. **Backend dependencies** (Maven): `spring-boot-starter-web`,
   `spring-boot-starter-data-jpa`, `spring-boot-starter-validation`,
   `spring-boot-starter-actuator`, `flyway-core`, `flyway-database-postgresql`,
   `postgresql` (runtime), `spring-boot-starter-test` (test). Java 21 toolchain,
   Spring Boot 3.x.
3. **`application.yml` + profiles.** Keys (all env-backed, no secrets in the
   file):
   - `server.port` (default 8080)
   - `spring.datasource.url` = `${DB_URL}`, `.username` = `${DB_USER}`,
     `.password` = `${DB_PASSWORD}`
   - `spring.jpa.hibernate.ddl-auto` = **`validate`** (Flyway owns the schema;
     Hibernate never creates/updates it)
   - `spring.jpa.open-in-view` = `false`
   - `spring.flyway.enabled` = `true`, `.locations` =
     `classpath:db/migration`
   - `management.endpoints.web.exposure.include` = `health`
   - profiles: `local` (sane localhost defaults) and `prod` (all env).
4. **Flyway baseline** at `backend/src/main/resources/db/migration/`:
   `V1__baseline.sql` — enable `pgcrypto` (for later UUID/crypto helpers) and
   create the `flyway_schema_history` via Flyway's own bootstrap. No domain
   tables yet.
5. **Health endpoint.** A thin `HealthController` in `web` exposing
   `GET /api/health` → `{ "status": "UP", "service": "evalos", "time": <ISO> }`,
   in the standard response envelope (defined in `common`). Actuator
   `/actuator/health` also available.
6. **Standard response envelope** in `common` (success/error shape) so no
   endpoint invents its own — used by every later unit.
7. **Frontend scaffold.** Vite React-TS app, Tailwind configured, the
   `ui-context.md` tokens declared as CSS custom properties in the global
   stylesheet (colors, `--font-sans/num/mono`, radius scale). A `lib/api.ts`
   client with the backend base URL from `import.meta.env`. A single page that
   calls `/api/health` and renders the status with a RAG dot.
8. **README** with local run steps (start Postgres, set env, `./mvnw spring-boot:run`,
   `npm run dev`) and the two verify commands.

## Acceptance criteria

- [ ] `cd backend && ./mvnw verify` passes (compile + context loads + tests).
- [ ] App starts against a local Postgres; Flyway applies `V1__baseline` once.
- [ ] `GET /api/health` returns `200` with `status: UP` in the envelope.
- [ ] Starting with `ddl-auto=validate` does not fail (schema matches — trivially,
      no entities yet).
- [ ] `cd frontend && npm run build` passes with no TS or console errors.
- [ ] `npm run dev` shows a page that reads and displays backend health.
- [ ] No secret values are committed; all come from env.

## Invariants honored

- Schema is owned by Flyway; `ddl-auto` is `validate`, never `create`/`update`
  (architecture invariant 9). No object storage, no mail dependency added
  (invariant 14).

## Files touched (created)

`backend/pom.xml`, `backend/src/main/java/com/ie/evalos/EvalOsApplication.java`,
`.../web/HealthController.java`, `.../common/ApiResponse.java` (envelope),
`backend/src/main/resources/application.yml` (+ `application-local.yml`,
`application-prod.yml`), `backend/src/main/resources/db/migration/V1__baseline.sql`,
`frontend/` (Vite scaffold), `frontend/src/styles/tokens.css`,
`frontend/src/lib/api.ts`, `frontend/src/App.tsx`, `README.md`.
