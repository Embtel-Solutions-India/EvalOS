# backend/ — Core

Spring Boot 3.5.16 / Java 21, base package `com.ie.evalos`, Maven Wrapper committed. **Units 01–10 +
05a (all of Phase 1) and Units 11–12 are built**: config + response envelope, the tenancy/auth/RBAC
spine, the domain schema, the case state machine + SLA, the inbound webhook gateway with Handoff A,
the staff notification centre, the board/case-detail/checklist reads behind four frontend surfaces,
the expert database + sheet upload, and the assist-mode match scorer. **Unit 14 (the client draft-review
portal — a second filter chain, the first non-staff caller) is built too.** **Unit 13 (redacted expert
profile + the first outbound Google Drive client) is code-complete, with its manual live upload
still owed** — see `mem:core`. Unit 15 is next. See `mem:core` for counts and the phase map.

## Package boundaries (all under `com.ie.evalos`)

`web` (thin controllers + DTOs) · `service` (all business logic + `@Transactional`) · `domain` (JPA
entities + enums) · `repository` (Spring Data + brand/team/assignee scoping) · `integration` (GHL,
Google Drive clients) · `webhook` (inbound gateway: verify → resolve brand → dedupe → archive →
route) · `event` (domain events + outbound HMAC dispatcher) · `job` (`@Scheduled` sweeps) ·
`notification` (in-app staff center) · `security` · `common` (envelope, encryption converter, error
types) · `config`.

`web`/`service`/`domain`/`repository`/`security`/`common`/`webhook`/`event`/`notification` and — since
Unit 13 — `integration` are populated; **`job` is still an empty `.gitkeep` placeholder**. Put code in
the package that matches the concern — controllers never hold logic, entities never leave the service
layer (map to DTOs). `notification/NotificationListeners` is the only subscriber to `event` so far;
the outbound dispatcher (Unit 18) is the next.

`integration` holds `GoogleDriveClient` + `DriveUnavailableException` (Unit 13), the first outbound
client, and — since the signature provider was dropped — the only third-party client besides the GHL
one still to come. The pattern it sets: **one narrow
capability, not an SDK wrapper**; a bounded request with an explicit timeout, because these are called
from controller-triggered paths and invariant 6 forbids long-lived work there; and a failure that is a
**502 changing nothing in EvalOS** rather than a partially-applied state. If a call stops fitting in
one bounded request it moves to `job` (Unit 19), which is where that rule points.

## `job`, when it stops being empty (Unit 19)

Decisions already taken, so the package does not get invented from scratch:

- **Spring `@Scheduled` + `@EnableScheduling`.** No Quartz, no ShedLock — both are a dependency and a
  table for what is already on the classpath. Intervals bind from `evalos.jobs.*`;
  `evalos.jobs.enabled=false` in the test profile so the suite cannot race a sweep.
- **Every sweep claims `pg_try_advisory_lock(hashtext(:jobType))` first** and returns if it loses,
  releasing it in a `finally`. Not for scale-out — because **every rolling deploy runs two instances
  for a few seconds**, and two sweeps ticking together double-chase a client and double-alert staff
  with nothing in the logs to say why.
  **Session-scoped, not `pg_try_advisory_xact_lock`.** The xact variant releases on commit, and a sweep
  runs *one transaction per item* — so it would drop the lock after the first item and leave the rest of
  the run unprotected, which is the exact failure it was added to prevent. Claim it outside the per-item
  transactions.
- **`scheduled_job` records runs, not intentions.** No row-per-future-timer: a sweeper asking "what is
  overdue right now" is correct on the first run after any outage. Idempotency comes from the data the
  action already writes (`CHASED` audit rows for chases, notification rows for thresholds), never from
  an "already ran" marker — which is why `POST /api/jobs/{type}/run` is safe to press twice.
- **One transaction per item**, so one poisoned case cannot stop the sweep; the run is recorded
  `FAILED` with the error and the next tick retries.
- **A sweep prompts and publishes; it never transitions.** No sweep may fire `EXPERT_TIMED_OUT`.
- Unscoped reads are deliberate (no authenticated caller, so `ScopePredicate` does not apply); every
  side effect goes through `AuditService.recordSystemEvent` with the brand from the row.
- **Five sweeps, not six** — retention left the unit; GHL owns it.
- The **queue is the `webhook_delivery` outbox**, `FOR UPDATE SKIP LOCKED`. See `mem:backend/webhooks`.

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
bug; Unit 13), `NoResourceFoundException` (**404** — this advice is a plain `@RestControllerAdvice`
and does not inherit `ResponseEntityExceptionHandler`, so Spring's own `ErrorResponseException`s fall
to the catch-all: **every unmapped URL used to answer 500 and log at error level**. Same class of bug
as the enum one above; found in Unit 05b while asserting `/mark-paid` was gone. Body carries no
detail — whether a path exists is not information a caller is owed), catch-all (500). The
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
  `evalos_case.draft_link` · `V21` `portal_access` · `V22` `audit_event.actor_type` · `V23` the
  one-unrevoked-token index that turned "one live portal token" from a service check into a
  constraint (all in `mem:backend/persistence`).
  **Never edit an applied migration** — `V12`'s constraint was once renamed in place, which would
  have made `V13`'s `DROP CONSTRAINT` fail on a fresh database while breaking checksums on existing
  ones.
- The `local` profile additionally lists `classpath:db/seed-local` (`V900` seed: 2 brands, 5
  logins, password `DevPassw0rd!`; `V901` per-brand webhook secrets; `V902` the remaining roles;
  `V903` seed experts) and sets
  `flyway.out-of-order: true` — the seed deliberately outranks every real migration, so without that
  flag the next unit's `V-N` is refused on an already-seeded dev database. `prod` keeps the strict
  default and never sees the seed.
- **The seed tree is a sibling of `db/migration`, never a child, and that is load-bearing.** It sat
  at `db/migration/local` until 2026-08-06 in the belief that only the profile naming that path
  would apply it. Flyway scans a location *and every sub-directory*, so prod's plain
  `classpath:db/migration` reached it: a production boot would have inserted the two seed brands and
  six logins sharing one committed BCrypt hash, GM included, plus the throwaway webhook secrets.
  Flyway has no exclude filter, so directory separation is the entire mechanism, and
  `config/MigrationTreeTest` now fails the build if anything reappears below `db/migration`.
- Actuator exposes `health` only.

## Running & tests

- **A reachable Postgres is required to start.** This machine has PostgreSQL 18 with the `evalos`
  database: its `public` schema is at **`V22`** (+ the `V90x` local seeds) and its `evalos_test`
  schema at **`V23`**, because the gated suite runs migrations and a `spring-boot:run` has not
  happened since `V23` landed — **the next one applies it** — see `mem:suggested_commands`. Its `public`
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
