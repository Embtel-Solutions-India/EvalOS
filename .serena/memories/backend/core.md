# backend/ — Core

Spring Boot 3.5.16 / Java 21, base package `com.ie.evalos`, Maven Wrapper committed. **Units 01–10 +
05a (all of Phase 1) and Units 11–12 are built**: config + response envelope, the tenancy/auth/RBAC
spine, the domain schema, the case state machine + SLA, the inbound webhook gateway with Handoff A,
the staff notification centre, the board/case-detail/checklist reads behind four frontend surfaces,
the expert database + sheet upload, and the assist-mode match scorer. **Unit 13 (redacted expert
profile + the first outbound Google Drive client) is code-complete, with its manual live upload
still owed** — see `mem:core`. Unit 14 is next. See `mem:core` for counts and the phase map.

## Package boundaries (all under `com.ie.evalos`)

`web` (thin controllers + DTOs) · `service` (all business logic + `@Transactional`) · `domain` (JPA
entities + enums) · `repository` (Spring Data + brand/team/assignee scoping) · `integration` (GHL,
Dropbox Sign clients) · `webhook` (inbound gateway: verify → resolve brand → dedupe → archive →
route) · `event` (domain events + outbound HMAC dispatcher) · `job` (`@Scheduled`/`@Async`) ·
`notification` (in-app staff center) · `security` · `common` (envelope, encryption converter, error
types) · `config`.

`web`/`service`/`domain`/`repository`/`security`/`common`/`webhook`/`event`/`notification` and — since
Unit 13 — `integration` are populated; **`job` is still an empty `.gitkeep` placeholder**. Put code in
the package that matches the concern — controllers never hold logic, entities never leave the service
layer (map to DTOs). `notification/NotificationListeners` is the only subscriber to `event` so far;
the outbound dispatcher (Unit 18) is the next.

`integration` holds `GoogleDriveClient` + `DriveUnavailableException` (Unit 13), the first outbound
client. The pattern it sets for the GHL and Dropbox Sign clients still to come: **one narrow
capability, not an SDK wrapper**; a bounded request with an explicit timeout, because these are called
from controller-triggered paths and invariant 6 forbids long-lived work there; and a failure that is a
**502 changing nothing in EvalOS** rather than a partially-applied state. If a call stops fitting in
one bounded request it moves to `job` (Unit 19), which is where that rule points.

## Response envelope — non-negotiable

`common/ApiResponse<T>` (`success`, `data`, `error{code,message}`, `@JsonInclude(NON_NULL)`) is
returned by **every** endpoint; `ApiResponse.ok(...)` / `.error(...)`. `common/ApiExceptionHandler`
(`@RestControllerAdvice`) maps exceptions to it — but failures raised **inside the security filter
chain never reach the advice**, so `common/ApiErrors` writes the envelope for those 401/403s.
Handled: validation (400), `HttpMessageNotReadableException` (400 — an unknown enum value in a body
used to fall through to the catch-all and answer **500**; the message names the offending value but
never echoes Jackson's, which quotes the payload and lists every legal value),
`InvalidRequestException` (400, message returned — same "may not be an existence oracle" rule as
`IllegalTransitionException`), `MaxUploadSizeExceededException` (400), auth (401), forbidden (403),
`IllegalTransitionException` (409), webhook rejection (its own status),
`DriveUnavailableException` (**502** — an upstream fault, so the caller retries rather than reports a
bug; Unit 13), catch-all (500). The
frontend's typed mirror lives in `frontend/src/lib/api.ts`.

## Config & schema

- `application.yml` + `application-local.yml` / `application-prod.yml`, every value env-backed:
  `DB_URL`, `DB_USER`, `DB_PASSWORD`, `SERVER_PORT`, `JWT_SECRET`, `JWT_TTL` (PT8H),
  `EVALOS_FIELD_KEY`. **No secret is ever committed.** `prod` has no defaults at all; `local`
  supplies localhost fallbacks (`postgres`/`1234`@5432, db `evalos`) plus dev-only fallbacks for the
  two keys. `spring.profiles.default: local`.
- **`evalos.drive.*` (Unit 13).** `key-json` (`GOOGLE_DRIVE_KEY_JSON`, inline JSON) and
  `credentials-path` (`GOOGLE_APPLICATION_CREDENTIALS`) both default to **empty**, and
  `required` — true in `application.yml`, restated in `prod`, **false only in `local`** — is what
  makes a missing key fatal. Deliberately not an unresolvable placeholder like `EVALOS_FIELD_KEY`:
  that could only ever demand one specific variable, so setting the other would fail the boot.
  `GoogleDriveConfig`'s **constructor** throws, so the context does not come up, and the key is read
  at startup so an unreadable path also fails there rather than at the first upload. Also
  `scope` (defaults to `drive.file`; the `.../auth/drive` fallback is a property, not a code change)
  and `timeout` (20s). **`local` is the only profile that runs without a key** — every route works
  and only the Drive write answers 502.
  A `@WebMvcTest` slice never loads `GoogleDriveConfig`, so **only the gated DB run proves these keys
  bind**; a typo here is invisible to `verify` alone.
- `ddl-auto: validate`, `open-in-view: false` — do not relax either. Hibernate never touches schema.
- Flyway `classpath:db/migration`: `V1` pgcrypto · `V2` brand · `V3` team_member · `V4`
  contact_snapshot · `V5` evalos_case · `V6` document_checklist_item · `V7` expert (+ the deferred
  `evalos_case.expert_id` FK) · `V8` payout_ledger · `V9` notification · `V10` audit_event · `V11`
  brand GHL secret · `V12` webhook_event · `V13` brand-scoped webhook idempotency key · `V14`
  `evalos_case.paid`/`paid_at` · `V15` partial unique index for one open case per contact+service ·
  `V16` contact identity · `V17` `evalos_case.assigned_coordinator` · `V18` expert contact columns +
  the closed-vocabulary CHECKs + the per-brand email index · `V19` `expert_case_offer` · `V20`
  `evalos_case.draft_link` · `V21` `portal_access` · `V22` `audit_event.actor_type` (all in
  `mem:backend/persistence`).
  **Never edit an applied migration** — `V12`'s constraint was once renamed in place, which would
  have made `V13`'s `DROP CONSTRAINT` fail on a fresh database while breaking checksums on existing
  ones.
- The `local` profile additionally lists `classpath:db/migration/local` (`V900` seed: 2 brands, 5
  logins, password `DevPassw0rd!`; `V901` per-brand webhook secrets; `V902` the remaining roles;
  `V903` seed experts) and sets
  `flyway.out-of-order: true` — the seed deliberately outranks every real migration, so without that
  flag the next unit's `V-N` is refused on an already-seeded dev database. `prod` keeps the strict
  default and never sees the seed.
- Actuator exposes `health` only.

## Running & tests

- **A reachable Postgres is required to start.** This machine has PostgreSQL 18 with the `evalos`
  database migrated to **`V22`** (+ the `V90x` local seeds), matching the repo — see
  `mem:suggested_commands`. Its `public`
  schema also holds ~46 junk experts and ~150 junk cases from integration-test runs that predate the
  `evalos_test` schema; they are dev noise, not data, and they show up on the roster screen.
- Map every controller under `/api` (the Vite dev proxy). Endpoints are **secured by default**: a new
  one answers 401 until `SecurityConfig` permits it or the caller bears a token — and a route under
  `/api/portal/**` lands on the *other* chain (`PortalSecurityConfig`), which accepts no JWT at all.
- Tests are slice (`@WebMvcTest`) or plain unit tests needing no DB, so `verify` is green anywhere.
  Everything that needs a real schema lives in one gated `@SpringBootTest`
  (`LocalPostgresIntegrationTest`, `-Devalos.db.test=true`). No Testcontainers, no Docker.

Deeper: `mem:backend/security` for the auth chain, JWT, tenant context and the scoping/ownership
mechanism; `mem:backend/persistence` for entity, repository, audit and field-encryption patterns;
`mem:backend/lifecycle` before touching any case transition, the paid guard, SLA or refund logic;
`mem:backend/webhooks` before touching the inbound gateway, idempotency or Handoff A.
Java style and the deliberate absence of Lombok: `mem:conventions`, `mem:tech_stack`.
