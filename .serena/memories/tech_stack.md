# Tech Stack

Versions are pinned in `frontend/package.json` / `backend/pom.xml`; both halves sit on recent
majors, so training-data-era API habits are frequently wrong. Verify against current docs before
using older idioms.

**Locked by `context/ai-workflow-rules.md`:** Java 21 + Spring Boot + PostgreSQL (Spring Data JPA) +
Flyway + Spring Security/JWT on the backend, React/Vite + Tailwind on the frontend. Do not introduce
a Node backend, another database, an object store, a mail server, or a different auth model. Install
a dependency only in the unit where it first unlocks real behavior.

## frontend/

- React 19 + react-dom 19, react-router-dom 7 (`Routes`/`Route` element API, not v5 `Switch`).
- TypeScript ~6.0, Vite 8, `@vitejs/plugin-react`.
- Tailwind v4 via the `@tailwindcss/vite` plugin — CSS-first config (`@import 'tailwindcss'` +
  `@theme`). There is deliberately **no `tailwind.config.js`**; extend the `@theme` block in
  `src/styles/tokens.css` instead.
- oxlint (not ESLint) — config `frontend/.oxlintrc.json`, plugins react/typescript/oxc.
- axios for HTTP. No state-management, data-fetching, or test library installed; **no test runner at
  all** — frontend changes are verified by typecheck + lint + manual run.
- Planned but not installed: shadcn/ui-style Radix primitives, Lucide icons.

## backend/

- Spring Boot **3.5.16** parent, Java 21 (`java.version` property; the toolchain JDK may be newer —
  compilation targets 21). Boot 3 artifact naming: `spring-boot-starter-web` and a single
  `spring-boot-starter-test`.
- Starters: `web`, `data-jpa`, `validation`, `actuator`, `security`. `flyway-core` +
  `flyway-database-postgresql`. `postgresql` driver at `runtime` scope. `spring-security-test` at test
  scope.
- JWT: **jjwt 0.13.0** (`jjwt-api` compile, `jjwt-impl` + `jjwt-jackson` runtime) — the 0.11 builder
  API is wrong here; use `Jwts.builder().subject(...).signWith(key)` and
  `Jwts.parser().verifyWith(key).build().parseSignedClaims(...)`.
- **No Lombok and no Testcontainers** — both dropped from the Initializr default in Unit 01 (records +
  constructor injection instead of Lombok). There is no Docker on this machine, so DB-dependent tests
  are gated rather than containerised. Boot 4 was deliberately downgraded to 3.x per the unit spec.
- Maven Wrapper is committed — use it rather than a system `mvn`.
