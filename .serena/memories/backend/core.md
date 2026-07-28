# backend/ — Core

Spring Boot 3.5.16 / Java 21, base package `com.ie.evalos`, Maven Wrapper committed. Post-Unit-01
scaffold: `EvalOsApplication`, `common/ApiResponse`, `web/HealthController` are the **only**
main-source classes. No entities, repositories, services, or security config exist yet.

## Package boundaries (all under `com.ie.evalos`, created empty with `.gitkeep`)

`web` (thin controllers + DTOs) · `service` (all business logic + `@Transactional`) · `domain` (JPA
entities + enums) · `repository` (Spring Data + brand/team/assignee scoping) · `integration` (GHL,
Dropbox Sign clients) · `webhook` (inbound gateway: verify → resolve brand → dedupe → archive →
route) · `event` (domain events + outbound HMAC dispatcher) · `job` (`@Scheduled`/`@Async`) ·
`notification` (in-app staff center) · `security` · `common` (envelope, encryption converter, error
types) · `config`.

Put code in the package that matches the concern — controllers never hold logic, entities never
leave the service layer (map to DTOs).

## Response envelope — non-negotiable

`common/ApiResponse<T>` (`success`, `data`, `error{code,message}`, `@JsonInclude(NON_NULL)`) is
returned by **every** endpoint. Use `ApiResponse.ok(...)` / `ApiResponse.error(...)`; no endpoint
invents its own shape. The frontend's typed mirror lives in `frontend/src/lib/api.ts`.

## Config & schema

- `application.yml` + `application-local.yml` / `application-prod.yml`. Every value is env-backed
  (`DB_URL`, `DB_USER`, `DB_PASSWORD`, `SERVER_PORT`); **no secret is ever committed**. `local`
  supplies localhost fallbacks (`evalos`/`evalos`@5432), `prod` has none. `spring.profiles.default:
  local`, so a bare run picks `local`.
- `spring.jpa.hibernate.ddl-auto: validate` and `open-in-view: false` — do not relax either.
  Hibernate never creates or updates the schema.
- Flyway on, `classpath:db/migration`. `V1__baseline.sql` enables `pgcrypto` only (no domain tables;
  Unit 03 adds them). Never edit an applied migration.
- Actuator exposes `health` only, at `/actuator/health`.

## Running & endpoints

- **A reachable Postgres is required** to start; there is no Testcontainers/Docker setup any more.
  `spring-boot:run` against a local `evalos` database is the intended path (`mem:suggested_commands`).
- `GET /api/health` → envelope with `{status:"UP", service:"evalos", time:<ISO>}`. Map every
  controller under `/api` so the Vite dev proxy reaches it.
- Spring Security is **not** on the classpath yet, so endpoints are open — expect that to change in
  Unit 02, after which a new endpoint returns 401 until its chain permits it.
- Tests are slice tests (`@WebMvcTest`) needing no DB. A stale `target/` from the old Boot 4
  scaffold breaks surefire discovery — use `clean verify` if discovery fails oddly.

Java style, Boot 3 artifact naming, and the deliberate absence of Lombok: `mem:conventions`,
`mem:tech_stack`.
