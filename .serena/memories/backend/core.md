# backend/ — Core

Spring Boot 3.5.16 / Java 21, base package `com.ie.evalos`, Maven Wrapper committed. Units 01–05 +
05a built: config + response envelope, the tenancy/auth/RBAC spine, the domain schema, the case state
machine + SLA, and the inbound webhook gateway with Handoff A. Unit 06 (staff notification centre) is
next.

## Package boundaries (all under `com.ie.evalos`)

`web` (thin controllers + DTOs) · `service` (all business logic + `@Transactional`) · `domain` (JPA
entities + enums) · `repository` (Spring Data + brand/team/assignee scoping) · `integration` (GHL,
Dropbox Sign clients) · `webhook` (inbound gateway: verify → resolve brand → dedupe → archive →
route) · `event` (domain events + outbound HMAC dispatcher) · `job` (`@Scheduled`/`@Async`) ·
`notification` (in-app staff center) · `security` · `common` (envelope, encryption converter, error
types) · `config`.

`web`/`service`/`domain`/`repository`/`security`/`common`/`webhook`/`event` are populated;
`integration`, `job`, `notification` are still empty `.gitkeep` placeholders. Put code in the package
that matches the concern — controllers never hold logic, entities never leave the service layer (map
to DTOs). `event` currently only *publishes*: nothing subscribes until Unit 06.

## Response envelope — non-negotiable

`common/ApiResponse<T>` (`success`, `data`, `error{code,message}`, `@JsonInclude(NON_NULL)`) is
returned by **every** endpoint; `ApiResponse.ok(...)` / `.error(...)`. `common/ApiExceptionHandler`
(`@RestControllerAdvice`) maps exceptions to it — but failures raised **inside the security filter
chain never reach the advice**, so `common/ApiErrors` writes the envelope for those 401/403s. The
frontend's typed mirror lives in `frontend/src/lib/api.ts`.

## Config & schema

- `application.yml` + `application-local.yml` / `application-prod.yml`, every value env-backed:
  `DB_URL`, `DB_USER`, `DB_PASSWORD`, `SERVER_PORT`, `JWT_SECRET`, `JWT_TTL` (PT8H),
  `EVALOS_FIELD_KEY`. **No secret is ever committed.** `prod` has no defaults at all; `local`
  supplies localhost fallbacks (`postgres`/`1234`@5432, db `evalos`) plus dev-only fallbacks for the
  two keys. `spring.profiles.default: local`.
- `ddl-auto: validate`, `open-in-view: false` — do not relax either. Hibernate never touches schema.
- Flyway `classpath:db/migration`: `V1` pgcrypto · `V2` brand · `V3` team_member · `V4`
  contact_snapshot · `V5` evalos_case · `V6` document_checklist_item · `V7` expert (+ the deferred
  `evalos_case.expert_id` FK) · `V8` payout_ledger · `V9` notification · `V10` audit_event · `V11`
  brand GHL secret · `V12` webhook_event · `V13` brand-scoped webhook idempotency key · `V14`
  `evalos_case.paid`/`paid_at` · `V15` partial unique index for one open case per contact+service.
  **Never edit an applied migration** — `V12`'s constraint was once renamed in place, which would
  have made `V13`'s `DROP CONSTRAINT` fail on a fresh database while breaking checksums on existing
  ones.
- The `local` profile additionally lists `classpath:db/migration/local` (`V900` seed: 2 brands, 5
  logins, password `DevPassw0rd!`; `V901` per-brand webhook secrets) and sets
  `flyway.out-of-order: true` — the seed deliberately outranks every real migration, so without that
  flag the next unit's `V-N` is refused on an already-seeded dev database. `prod` keeps the strict
  default and never sees the seed.
- Actuator exposes `health` only.

## Running & tests

- **A reachable Postgres is required to start.** This machine has PostgreSQL 18 with the `evalos`
  database migrated to `V15` — see `mem:suggested_commands`.
- Map every controller under `/api` (the Vite dev proxy). Endpoints are **secured by default**: a new
  one answers 401 until `SecurityConfig` permits it or the caller bears a token.
- Tests are slice (`@WebMvcTest`) or plain unit tests needing no DB, so `verify` is green anywhere.
  Everything that needs a real schema lives in one gated `@SpringBootTest`
  (`LocalPostgresIntegrationTest`, `-Devalos.db.test=true`). No Testcontainers, no Docker.

Deeper: `mem:backend/security` for the auth chain, JWT, tenant context and the scoping/ownership
mechanism; `mem:backend/persistence` for entity, repository, audit and field-encryption patterns;
`mem:backend/lifecycle` before touching any case transition, the paid guard, SLA or refund logic;
`mem:backend/webhooks` before touching the inbound gateway, idempotency or Handoff A.
Java style and the deliberate absence of Lombok: `mem:conventions`, `mem:tech_stack`.
