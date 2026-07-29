# Progress Tracker

Update this file after every meaningful implementation change.

## Current Phase

- Phase 1 — Structure the data (the spine) is complete: Units 01–05 built. Phase 2
  begins at Unit 06.

## Current Goal

- Unit 06 — the in-app staff notification centre, the first subscriber to the
  domain events Units 04 and 05 publish.

## Completed

- Six context files + `CLAUDE.md` entry point, aligned to the EvalOS Technical
  Design Document v1.1 (multi-brand, 8-stage, no object storage, no mail, manual
  payout ledger, roles GM/Brand-Mgr/PM/Coordinator/CM/ENM).
- `context/specs/00-build-plan.md` (20 units across 3 phases).
- **Unit 01 — Project scaffold & config.** Monorepo skeleton on the ground both
  halves stand on:
  - `backend/` re-based from the Initializr default (`com.evalos.server`, Boot
    4.1.0, Security + Lombok + Testcontainers) to the spec's `com.ie.evalos`,
    Spring Boot 3.5.16, Java 21. Deps: web, data-jpa, validation, actuator,
    flyway-core + flyway-database-postgresql, postgresql (runtime),
    spring-boot-starter-test. Empty package skeleton for the 12 boundaries.
  - `application.yml` + `local`/`prod` profiles, all env-backed
    (`DB_URL`/`DB_USER`/`DB_PASSWORD`), `ddl-auto: validate`,
    `open-in-view: false`, Flyway on, actuator exposing `health` only.
    `spring.profiles.default: local`.
  - `V1__baseline.sql` — `pgcrypto` only, no domain tables.
  - `common/ApiResponse` envelope (`success`/`data`/`error`) + thin
    `web/HealthController` → `GET /api/health`.
  - `frontend/`: tokens from `ui-context.md` as CSS custom properties in
    `src/styles/tokens.css` (imported by `index.css`), fonts + radius scale via
    Tailwind v4 `@theme`, API client moved to `src/lib/api.ts` with the typed
    envelope, dashboard page renders backend health with a RAG dot.
  - Root `README.md` with run + verify steps.
  - Verified: `./mvnw clean verify` BUILD SUCCESS (1 test), `npm run build`
    clean.
- **Unit 02 — Multi-tenancy + Auth & RBAC/ABAC.** The guard rails everything
  scoped now depends on:
  - `Brand` + `TeamMember` entities and `V2`/`V3` migrations. `team_member`
    carries a CHECK that only a GM row may have a NULL `brand_id`, so a
    mis-seeded row cannot silently become cross-brand.
  - `Role` enum carries its own ABAC `Tier` (`ALL/BRAND/TEAM/SELF/SUPPLY`), so
    no query re-derives scope from the role name.
  - `security/`: stateless bearer-only `SecurityFilterChain`, BCrypt,
    `EvalOsUserDetailsService`, `JwtService` (HS256, claims carry
    member/role/brand/team so scoping needs no DB hit), `JwtFilter`,
    `StaffPrincipal`, `TenantContext` (read off the security context — brand
    never comes from a body, query, or header).
  - `ScopePredicate` — the one place brand/team/assignee predicates are built;
    **fails closed** (a brand-locked role with no brand matches nothing, not
    everything). `OwnershipGuard` is its write-side counterpart.
  - Endpoints: `POST /api/auth/login`, `GET /api/me`,
    `GET /api/team-members` (`@PreAuthorize` GM/Brand-Manager, scoped in the
    service). `ApiErrors` writes the envelope for filter-chain 401/403s, which
    never reach `@RestControllerAdvice`.
  - Local-only seed `db/migration/local/V900__seed_local.sql` (2 brands,
    5 logins, password `DevPassw0rd!`), reachable only because the `local`
    profile is the only one listing that Flyway location.
  - Verified: `./mvnw clean verify` BUILD SUCCESS, 17 tests
    (`SecurityFlowTest` 10, `ScopePredicateTest` 6, `HealthControllerTest` 1),
    **plus a live run against local Postgres** — V1–V3 + V900 seed applied,
    `ddl-auto=validate` passed, and the acceptance flow verified end-to-end
    (GM all 5, Brand-Mgr IE only 3, Case-Mgr 403, no/garbage/flipped-sig token
    401, login-body `brandId` ignored). The DB half is no longer a gap.
- **Unit 03 — Domain model & migrations.** The system-of-record schema:
  - Entities + `V4`–`V10`: `ContactSnapshot`, `Case` (table `evalos_case`),
    `DocumentChecklistItem`, `Expert`, `PayoutLedger`, `Notification`,
    `AuditEvent` — every foreign key a raw UUID rather than an association, as in
    Unit 02, so scoping stays a plain column predicate.
  - The 21 vocabulary enums in `domain/`. `NotificationType` and `AuditAction`
    are open: their columns carry no CHECK, so later units add values without a
    migration.
  - `domain/ScopedEntity` (`@MappedSuperclass`) — the `id`/`brand_id`/`created_at`
    every scoped row shares, with a `@PrePersist` hook that stamps `created_at`
    and **refuses a row with no brand**. `brand_id` is `updatable = false`: a row
    never changes brand.
  - `common/PaymentDetailConverter` — AES-256-GCM, fresh 12-byte IV per write,
    stored as `base64(iv || ciphertext||tag)`, key from `EVALOS_FIELD_KEY` via
    `evalos.security.field-key` (no prod default, local dev default only). GCM is
    authenticated, so an edited column fails to decrypt rather than returning
    plausible plaintext. The cost is that the column is not searchable.
  - `repository/ScopedRepository` — `findScoped(ctx)` and `findScoped(ctx, id)`
    built on the Unit 02 `ScopePredicate`; each repository declares only which of
    its columns carry brand / team / assignee. `Case` is the one type using all
    three axes (`brandId`, `teamId`, `assignedCm`); `Notification` scopes by
    recipient; the rest are brand-only.
  - Append-only audit, enforced three times over: `AuditEventRepository` extends
    the bare `Repository` marker (so no `delete*` exists to call), every
    `AuditEvent` column is mapped `updatable = false`, and a
    `BEFORE UPDATE OR DELETE` trigger raises. `AuditService.recordEvent(...)`
    joins the caller's transaction, so the trail commits with the change it
    describes or not at all.
  - Verified: `./mvnw clean verify` BUILD SUCCESS, 37 tests (31 run —
    `SecurityFlowTest` 10, `DomainInvariantsTest` 9, `ScopePredicateTest` 6,
    `PaymentDetailConverterTest` 5, `HealthControllerTest` 1 — plus 6 DB-gated),
    **and live against local Postgres 18**: on a fresh `evalos_unit03` database
    all 11 migrations applied in order, the next boot was a no-op,
    `ddl-auto=validate` passed (so `text[]` and `jsonb` map correctly),
    `payment_detail` is base64 ciphertext in raw SQL and plaintext through the
    entity, a Brand Manager's `findScoped` returned only their brand's expert
    while the GM saw both, a brand-less row was refused before insert, and raw
    `UPDATE`/`DELETE` on `audit_event` both raised. The dev `evalos` database was
    then migrated forward and re-verified.
- **Unit 04 — Case lifecycle service (state machine).** The spine now moves:
  - `service/CaseTransitions` — the declared table as a whitelist: `(from, action)
    → to`, plus the four actions that are legal *only* while a case holds a
    specific exception state. An exception state is not an extra stage: the case
    keeps its stage and accepts nothing but its way out, which is how "exception ↔
    prior stage" works with no column remembering the prior stage — it never left
    it. Each `Action` carries its own event type and audit action, so a transition
    cannot be logged as one thing and published as another.
  - `service/CaseLifecycleService` — 18 transition methods, all funnelling through
    one `apply(...)`: set stage → restamp the clock → refresh SLA → save → one
    `AuditService.recordEvent` → one `CaseEvent`, inside the caller's transaction.
    Reads go through `findScoped`, so another brand's case (or another CM's) is
    simply absent.
  - `service/BusinessCalendar` — 09:00–17:00 America/Los_Angeles, weekends and the
    eleven US federal holidays, including the Sat→Fri / Sun→Mon observance shift
    and the New-Year shift that falls back into the previous December.
    `elapsedBusinessTime` and `plusBusinessTime` (negative amount subtracts).
  - `service/SlaCalculator` — per-stage business-hour budgets (doc collection 24h =
    3 days, expert assignment 4h, first draft 48h, PM review 12h, client review
    48h, expert sign 24h, QC 2h), `AT_RISK` at 75% spent, null when no clock runs
    (closed, or in an exception state).
  - `service/RefundService` — GM-only, checked at the endpoint *and* in the service
    because it is the one path that touches money. Voids every `PENDING` payout,
    closes the case flagged refunded, publishes `case.refunded`.
  - `event/CaseEvents` — 19 event types with their wire names, and one `CaseEvent`
    payload carrying brand/case/contact/attribution/stage and nothing else.
  - `web/CaseController` — 20 endpoints, one per transition, `@PreAuthorize` per the
    spec's actor table; `deal_value` projected only for GM / Brand Mgr / PM.
    `domain/IllegalTransitionException` → 409 `ILLEGAL_TRANSITION`.
  - Verified: `./mvnw clean verify` BUILD SUCCESS, 60 tests (52 run —
    `CaseLifecycleServiceTest` 11, `SecurityFlowTest` 10, `DomainInvariantsTest`
    10, `ScopePredicateTest` 6, `CaseControllerTest` 6, `PaymentDetailConverterTest`
    5, `BusinessCalendarTest` 3, `HealthControllerTest` 1 — plus 8 DB-gated), **and
    the 8 DB-gated checks green against local Postgres 18**: the context boots with
    the new derived finders, `ddl-auto=validate` still passes after the accessors
    were added, and the board filters generate real SQL on top of the scope
    predicate (a Criteria attribute name that no mocked repository would ever
    catch).

- **Unit 05 — Inbound webhook gateway + GHL payment handler (Handoff A).** The
  door the business actually comes through. *(The payment-handler half is
  superseded by Unit 05a below — the trigger is now `contact.created`. Everything
  about the gateway itself still stands.)*
  - `webhook/WebhookGateway` — resolve brand → verify → dedupe → archive → route →
    ack. Deliberately **not** `@Transactional`: each step commits on its own, which
    is what lets the archive row outlive a failed handler and record why. Brand
    resolution runs before verification (the spec lists it second) because the HMAC
    secret belongs to the brand — a lookup is not a side effect, so the rule it
    protects still holds.
  - `webhook/WebhookVerifier` — HMAC-SHA256 over the exact bytes received, compared
    with `MessageDigest.isEqual`. No secret, no header, bad hex and a wrong digest
    all fail identically, so nothing is learnable from the response.
  - `webhook/{InboundWebhookController, WebhookRouter, GhlPaymentHandler,
    WebhookRejected}` — one public endpoint per brand, the event-type vocabulary
    (`payment.confirmed` live; `refund.requested`/`contact.updated` recognized and
    logged no-ops), and parse-then-trust validation of the payload.
  - `service/CaseIntakeService` — the one thing that creates a case: contact sync,
    case in the pool, checklist from `ChecklistTemplates`, GM + Brand-Manager pool
    notification, audit row, `case.created` + `checklist.requested`. All in one
    transaction, so a failed delivery leaves nothing behind.
  - `domain/WebhookEvent` + `V12`, narrowed by `V13` to
    `UNIQUE NULLS NOT DISTINCT (source, brand_id, external_id)`; `V11` for the
    per-brand secret, `V901` local seed. `AuditService.recordSystemEvent` so a
    webhook's audit row carries the brand it resolved rather than a null.
  - `DomainInvariantsTest` now enforces invariant 8 structurally: only
    `GhlPaymentHandler` may take `CaseIntakeService`, so adding a
    `POST /api/cases` that creates a case breaks the build.
  - Verified: `./mvnw clean verify` BUILD SUCCESS, 87 tests (76 run — new:
    `InboundWebhookTest` 13, `CaseIntakeServiceTest` 7 — plus 11 DB-gated), the 11
    DB-gated checks green against local Postgres 18 (`V11`–`V13` + `V901` applied,
    `validate` passes, the brand-scoped unique key refuses a second archive per brand
    while allowing the same invoice ref from another brand, and two brand-less rows
    still deduplicate), **and a live end-to-end run**: a signed `payment.confirmed` created exactly one case
    (`IE-2026-375863`, DOC_COLLECTION / IN_POOL / ON_TRACK / 1450.00) with a
    6-item `REQUIRED` checklist, a contact snapshot, two `NEW_CASE_IN_POOL`
    notifications (GM + that brand's manager only), an audit row carrying the
    resolved brand and a null actor, and a processed `webhook_event`; a replay
    returned `duplicate` and created nothing; a wrong signature 401; an unknown
    token 404.

- **Unit 05a — Handoff A re-pointed from payment to contact.** A design correction,
  not a new unit: the business does not want to wait for money to start a case, so
  the trigger moved and payment became a fact recorded on the case.
  - `webhook/GhlContactHandler` replaces `GhlPaymentHandler`; the router's live
    event type is `contact.created`. `contact.updated` deliberately stays a
    recognized no-op — intake is create-or-update so routing it there would
    technically work, but an edit in GHL is not a reason to open a case.
  - The gateway's idempotency-key candidates are now `event_id`, `webhook_id`,
    `id` — a contact has no invoice, and keying on the contact id would make a
    returning client's second order look like a duplicate.
  - `V14__case_paid.sql` — `paid boolean NOT NULL DEFAULT false` + `paid_at`, and
    `(brand_id, paid)`. Defaulting false is the safe direction. No `paid_by`: the
    audit trail already records who, and a second record of one fact can disagree.
  - `CaseLifecycleService.markPaid` + `POST /api/cases/{id}/mark-paid`, GM or Brand
    Manager (same gate as assigning a PM — both are the brand's commercial call).
    Declared on every active stage, because payment clearing late is bookkeeping
    reality, not an illegal state.
  - **The guard that matters is one line, in one place:** `markDocsComplete`
    refuses an unpaid case. Every later stage is only reachable through that
    transition, so guarding there covers all of them. Doc collection against an
    unpaid case is deliberately allowed — it costs EvalOS nothing.
  - `RefundService.isRevenueRecognized` is now `paid && delivered && !refunded`
    (invariant 5 restated). Delivery alone no longer implies earned.
  - `CaseIntakeService.intake` became create-**or-update**: one open case per
    contact per service. A refresh only fills blanks and can never move the case —
    a re-firing GHL workflow must not reset a stage, drop an assignment, or un-pay
    a case. It publishes no lifecycle event, because nothing in the lifecycle
    happened. `NewCase.paid` lets intake skip straight to paid when GHL already
    knows.
  - `service/PoolNotifier` — the recipient rule (GM + that brand's Brand Managers)
    extracted from intake because two callers now need it: `NEW_LEAD` on creation,
    `NEW_CASE_IN_POOL` on payment. Unit 06 replaces it with event listeners.
  - Verified: `./mvnw verify` BUILD SUCCESS, 92 tests (81 run — new
    `anUnpaidCaseGetsNoFurtherThanDocCollection`, `/mark-paid` added to
    `CaseControllerTest`'s route table so the GM-superuser guarantee still covers
    every route), **and the 11 DB-gated checks green against local Postgres 18** —
    `V14` applied and `ddl-auto=validate` passed, so `paid`/`paid_at` match the
    entity.

- **Unit 05a review pass — six findings, all fixed.** A five-lens review of `b28b0f5`
  (CLAUDE.md/invariants, bug scan, git history, prior review feedback, comment
  contracts). Two were real defects on the money path:
  (a) **`"id"` had come back into the webhook idempotency-key fallback**, having been
  deliberately cut in `f65b2f1`. In most envelopes `id` is the *resource's* id, so a
  returning client's second order would carry the first one's key and be answered
  `duplicate` — the very failure moving off `invoice_ref` was meant to avoid. The list
  is now `{ event_id, webhook_id }` and a payload with neither is refused. If GHL
  turns out to send only a resource id, the answer is a delivery-id header, not this
  list.
  (b) **A case GHL reported as already paid could never have its amount corrected.**
  Intake set `deal_value` from `quote_amount` — a quote is all the contact webhook
  knows — and `markPaid` refused an already-paid case, so the quote became the
  permanent revenue figure. `markPaid` is now callable on a paid case: the amount and
  invoice ref are correctable, while `paid` / `paid_at` stay write-once (the moment the
  money landed does not change) and the pool alert fires only on the first payment.
  Only ever one value, never a running total, so correcting it cannot double-count.
  (c) **`markPaid` had no service-layer role check.** Unit 04 note (g) established that
  a money path re-checks in the service, not only at the endpoint —
  `RefundService.requireGm` does. `markPaid` now has its own GM-or-Brand-Manager guard,
  with `onlyTheGmOrABrandManagerMayRecordAPayment` covering it.
  (d) **`V15__one_open_case_per_contact_service.sql`** — "one open case per contact per
  service" was a check-then-act with nothing behind it: two `contact.created`
  deliveries with different event ids are not deduplicated by the gateway (they are
  genuinely different deliveries), so both could pass the lookup and both create a
  case. A partial unique index on `(brand_id, contact_id, service_type)
  WHERE current_stage <> 'CLOSED'` cannot race; the loser's transaction rolls back, the
  gateway answers a retriable 5xx, and the redelivery refreshes the committed row —
  which is what intake wanted anyway. Partial because a contact returning after their
  first case closed is new business, not a duplicate.
  (e) **Two "sole revenue-recognition" javadocs were left false** by 05a's change to
  invariant 5 — `deliverToClient` and the new `CASE_PAID`. Both now say paid *and*
  delivered, and point at `isRevenueRecognized` as the only reader of the pair.
  (f) **The `NEW_CASE_IN_POOL` comment contract was false.** Intake's comment said
  `markPaid` raises that alert; intake raises it eight lines later, for a
  contact that arrived paid. `PoolNotifier`'s javadoc claimed two callers where there
  are three. Both corrected — the paid-at-intake double alert is intended behaviour,
  only the comments were wrong.
  - Also cut: a dead `java.util.stream.Stream` import left behind when `PoolNotifier`
    was extracted.
  - Verified: `./mvnw verify` BUILD SUCCESS, 95 tests (83 run), and **12 DB-gated green
    against local Postgres 18** — `V15` applied out-of-order on the dev database
    without conflict, and `oneOpenCasePerContactPerServiceIsEnforcedByTheDatabase`
    proves the index refuses the second open case while still allowing another service
    and a repeat purchase after close.

## In Progress

- Nothing.

## Next Up

- Unit 06 — in-app staff notification centre (subscribes to the Unit 04/05 domain
  events that are currently published to nobody).

## Open Questions

- **GHL contract still unconfirmed** (was already open, now load-bearing): the
  `contact.created` payload shape, the signature header name, and the HMAC
  encoding are all assumptions. Everything else about Handoff A is verified; these
  three are what a real GHL sub-account has to agree with. The payload shape is
  confined to `GhlContactHandler.ContactCreated` so a correction is one file.
  **Also unconfirmed: which GHL contact event actually fires.** `contact.created`
  is the assumption; if the real trigger is a pipeline-stage or form-submission
  event, only `WebhookRouter`'s one constant changes.

- **Coordinator case scope (blocks four Unit 04 endpoints at runtime).**
  `PROJECT_COORDINATOR` is `Tier.SELF`, but no `evalos_case` column names a
  coordinator, so their scoped read matches nothing and `docs-complete`,
  `draft/send-to-client`, `deliver` and `close` will 403 live. Decide between:
  (a) add `assigned_coordinator` to `evalos_case` in a new migration and make it the
  Coordinator's Self axis (assignment happens alongside `assign-pm`/`assign-cm`);
  (b) make the Coordinator `Tier.TEAM`, sharing the PM's team scope; or (c) accept
  that Coordinators act through a PM until a later unit. Needed before Unit 08/09
  put the board in front of a Coordinator.

- **Full brand list** — International Evaluations and XpertsPortal confirmed;
  confirm any others before seeding brands / webhook endpoints.
- **Sales/Marketing dashboards** — GHL-native (assumed; EvalOS does not build
  them) vs EvalOS-built. Default is GHL; confirm before Unit 17.
- **StatCommand** — internal module or external BI, and the "six operating
  conditions" the dashboards feed. Undefined; do not build a StatCommand
  integration until specified.
- **GHL webhook/API contract** — (a) per-brand inbound `contact.created`
  payload + signing secret (Unit 05); (b) outbound subscriber URL + secret for
  `case.delivered` and the ability to send client-facing transactional messages
  on EvalOS event triggers (Unit 18); (c) which extra inbound GHL events to
  handle now vs later (`refund.requested`, `contact.updated`).
- **Dropbox Sign callback secret** — signing secret for signed/declined/viewed
  callbacks (Unit 15).
- **Staff SSO** — optional/later; JWT password login for v1.
- **FO-2026-CRM-01** — full credential-handling rules (disclaimer text captured
  per brand; remaining rules assumed satisfied by encryption + RBAC + audit).

## Architecture Decisions

- **Scope**: EvalOS is back-of-house only. GHL owns marketing, sales, invoicing,
  and review/retention delivery.
- **Multi-brand tenancy**: shared PostgreSQL, row-level tenancy by `brand_id`,
  brand + team + assignee scoping enforced at the query layer. GM is the only
  cross-brand role. Brand resolved at Handoff A by per-brand webhook endpoint
  (each brand is a separate GHL sub-account).
- **Stack**: Java 21 + Spring Boot + PostgreSQL (Spring Data JPA), Flyway, Spring
  Security + JWT; React + Vite + Tailwind; monorepo, base package `com.ie.evalos`.
  (Overrides the original Node/Express/MongoDB reuse idea.)
- **Roles**: GM, Brand Manager, Project Manager, Project Coordinator, Case
  Manager, Expert Network Manager. No Head-of-Evals, no interns. Sales/marketing
  roles stay in GHL. Client and expert portals are separate scoped, link-based
  auth surfaces.
- **Pipeline**: 8-stage canonical model; EvalOS owns stages 3–7 via the internal
  state machine `DOC_COLLECTION → EXPERT_ASSIGNMENT → DRAFT_GENERATION →
  EXPERT_SIGNING → FINAL_DELIVERY → CLOSED` + exception states. Draft/PM/client
  loops live inside `DRAFT_GENERATION`. Pool → PM → CM assignment.
- **Refund**: GM-only approval; reverses revenue recognition, voids the pending
  payout, signals GHL.
- **No object storage**: documents are Google Drive links; signed letters in
  Dropbox Sign; redacted CV generated on demand.
- **No mail server**: staff in-app notification center; client messages via GHL;
  expert notifications via Dropbox Sign (portal-only nudges).
- **Payouts**: manual ledger form, no payment-platform/disbursement rail. Single
  optional encrypted `payment_detail` field.
- **Handoff A**: GHL fires the per-brand "contact created" webhook; EvalOS creates
  the case idempotently, **unpaid**. Payment is a separate fact recorded on the
  case by a GM or Brand Manager (`paid` / `paid_at`, `POST /mark-paid`), and no
  unpaid case may leave `DOC_COLLECTION`. Revenue recognition is paid **and**
  delivered. No direct payment-processor integration.
- **E-signature**: Dropbox Sign.
- **Contacts**: GHL is the owner; EvalOS keeps a read-only, brand-tagged snapshot.
- **NFR**: 50–100 cases/brand/month; ~99% availability, single region; nightly
  backups; SLA calendar America/Los_Angeles (9–5 PT, US federal holidays); UTC
  storage. GDPR/CCPA-specific handling out of scope for v1.

## Session Notes

- **Unit 01 deviations / gaps to close.** (a) The spec's `./mvnw verify` covers
  compile + the health-endpoint test only — a full context-load test needs a
  Postgres, and this machine has neither Docker nor a local Postgres, so
  "app starts, Flyway applies V1 once" is **unverified**; add a Testcontainers
  `@SpringBootTest` in Unit 03 when entities make it worth it. (b) Inter /
  IBM Plex Mono are declared as font stacks with system fallbacks; the actual
  webfonts are not bundled. (c) Boot 3.5.16 chosen over the Initializr's 4.1.0
  to match the spec's "Spring Boot 3.x". (d) Stale `backend/target/` from the
  old scaffold breaks surefire discovery — run `./mvnw clean verify` once.

- **Unit 02 deviations / gaps to close.** (a) ~~DB half unverified~~ —
  **closed**: verified live against local Postgres (postgres/1234, db `evalos`
  created this session). Unit 03 should still add a Testcontainers
  `@SpringBootTest` so the DB path is covered in CI, not just by a manual local
  run (and to re-add the testcontainers deps dropped when the scaffold was
  re-based). (b) `/api/me`
  lives in `AuthController`, not the spec's separate `MeController` — same
  concern (staff identity), one fewer file. (c) `@WebMvcTest` slices must
  `@Import` the security stack and set `evalos.security.jwt.secret`, because
  `JwtFilter` is picked up as a `Filter` bean while `JwtService` is not — that
  is what broke Unit 01's `HealthControllerTest`; it now imports the real
  `SecurityConfig` and so also asserts health stays public. (d) The IDE flags
  `evalos.*` as an unknown property — expected, those values are read with
  `@Value`, not `@ConfigurationProperties`. (e) The JWT carries role/brand/team,
  so a role or brand change only takes effect on the next login; the 8h TTL
  bounds it. Revisit if instant revocation is ever required.

- **Unit 03 deviations / gaps to close.**
  (a) **Flyway out-of-order, local only.** The `V900` local seed sits above every
  real migration, so on a dev database that had already run it, `V4`–`V10` looked
  out of order and Flyway refused them — a latent Unit 02 defect that Unit 03's
  first new migration surfaced. Fixed with `spring.flyway.out-of-order: true` in
  the `local` profile, the only profile that applies the seed; `prod` keeps the
  strict default. Fresh databases were never affected.
  (b) **Accessors are added when a consumer appears.** Entities carry their mapped
  fields, a creation constructor for the required columns, and nothing else;
  Hibernate uses field access, so getters are not needed to persist or validate.
  `ScopedEntity` exposes `id`/`brandId`/`createdAt`, `Expert` exposes
  `payment_detail`, and `AuditEvent` has full getters because its finders return
  rows to be read. Unit 04 adds the stage/SLA accessors the state machine needs
  rather than 400 lines of speculative boilerplate now.
  (c) **`created_at` added to three tables** the spec's per-table lists omitted
  (`contact_snapshot`, `document_checklist_item`, `expert`) — the spec's blanket
  "every table has `brand_id` and timestamps" plus deliverable 6's `created_at`
  stamp both call for it.
  (d) **Columns with a spec default are NOT NULL** (`draft_version_count`,
  `google_review_requested`, `total_cases_completed`, `current_active_count`,
  `total_payments_pending`) because the Java fields are primitives / never null.
  (e) **`evalos_case.expert_id`'s foreign key is added in `V7`**, not `V5`: the
  `expert` table does not exist yet at `V5`. The column and its
  `(brand_id, expert_id)` index are in `V5` as specified.
  (f) **Audit brand is derived, not passed.** `recordEvent(...)` has no `brandId`
  parameter, so the brand comes from `TenantContext` — never from an argument a
  caller could get wrong. A system action outside a request records a null brand,
  which the nullable column allows; so does a GM action, since a GM has no brand.
  (g) **`object_type` stays a `String`**, matching the spec's signature. No
  `AuditObjectType` enum is defined anywhere in the design; add one if Unit 04
  finds the loose strings drifting.
  (h) **`text[]` columns map as `String[]`**, not enum arrays — `PerformanceFlag`
  is the vocabulary, applied at the service layer. Enum-array mapping buys
  nothing here and risks `ddl-auto=validate` mismatches.
  (i) **No CHECK constraints on the enum columns.** The spec does not ask for
  them; `V3`'s `role` CHECK was Unit 02's own call. Cheap to add later if a
  hand-written row ever needs guarding.
  (j) `ScopePredicate` still lives in `service` (Unit 02 put it there) and
  `repository` now imports it — inverted layering, but it is a static helper with
  no dependencies, so there is no cycle and moving it would touch Unit 02 code for
  no behavioural gain.
  (k) **Contact snapshot columns stay updatable.** Invariant 7 means EvalOS
  business rules never mutate them, not that the column is physically read-only —
  `architecture.md` has GHL's `contact.updated` refreshing the snapshot, and
  `updatable = false` would block that writer too. The rule is documented on the
  entity instead.
  (l) **Testcontainers gap still open.** The DB checks live in
  `LocalPostgresIntegrationTest`, gated on `-Devalos.db.test=true`, because this
  machine has no Docker; `./mvnw verify` therefore stays green anywhere and skips
  those 6. Convert it to Testcontainers when CI (or Docker) exists — the test
  bodies will not need to change, only how the database is provided.
  (m) Fixed in passing: `README.md` said a Brand Manager sees "four" seeded team
  members; the seed gives them three.

- **Unit 03 review pass — three data-contract ambiguities closed** (all verified
  by `verify` + the DB suite; no schema change, so no new migration):
  (a) **One clock for every timestamp.** `AuditEvent.created_at` was
  database-stamped (`insertable = false`) while every `ScopedEntity` stamps in
  `@PrePersist`. Two clocks in one schema means a timeline interleaving a row's
  `created_at` with its audit rows can order wrongly once app and DB sit on
  different hosts. Audit now stamps in Java like everything else; the column keeps
  `DEFAULT now()` as the raw-SQL backstop.
  (b) **No column name is derived.** Roughly 20 single-word columns (plus both
  `id`s) relied on `CamelCaseToUnderscoresNamingStrategy` to guess their name.
  Every column is now spelled out with `@Column(name = ...)`, matching Unit 02's
  `TeamMember`. This is deliberately *more* code: the column name is a contract
  shared with the migrations, and the strategy does not always agree with it —
  `retention30SentAt` derives to `retention30_sent_at`, not the real
  `retention_30_sent_at`.
  (c) **The scope axis can no longer be forgotten.** `scopeFields()` stays
  abstract (a brand-only default would fail *open*), and `DomainInvariantsTest`
  now also asserts that an entity declaring `teamId` scopes by it, and that every
  `ScopedEntity` subclass on the classpath appears in the repository scope table —
  so adding an entity without declaring its scope breaks the build.
  Also cut in the same pass: 44 lines of accessors and creation constructors with
  no caller (4 entity constructors, `Notification.isRead`/`markRead`,
  `AuditEvent.getObjectType`/`getObjectId`).

- **Unit 04 deviations / decisions to confirm.**
  (a) **`PROJECT_COORDINATOR` stays `Tier.SELF`** — decided, and it leaves a known
  runtime gap tracked as an open question below. The spec's transition table makes
  the Coordinator the actor for docs-complete, send-to-client, deliver and close,
  but `evalos_case`'s assignee axis is `assigned_cm`, which only ever holds a Case
  Manager. So a Coordinator's `findScoped` matches no case, and those four
  endpoints answer 403 in a live system even though their role gate passes. Briefly
  moved to `Tier.TEAM` during the build, then reverted on instruction. Closing the
  gap needs an `assigned_coordinator` column and a migration — **not** a widened
  predicate, which would fail open. The warning lives on the enum constant.
  (b) **`assignPm` stamps `team_id` from the PM's row.** Nothing else populated it,
  so PM/Coordinator team scoping would never have matched either. Pool → PM is the
  moment a case acquires a team.
  (c) **`stage_entered_at` means "when the current wait began"**, restamped by every
  transition rather than only the ones that change stage. Without it a second PM
  review round would inherit the first round's spent clock, and there is no column
  for a sub-loop timestamp (Unit 04 adds no migration). The stage timeline is still
  reconstructable from the audit trail.
  (d) **"Flagged refunded" is `CLOSED` + `exception_state = REFUND_REQUESTED`** —
  confirmed as the reading of the requirement. No
  refunded column exists and no migration was in scope. `RefundService.isRefunded`
  / `isRevenueRecognized` are the single reading of that pair — Unit 17's dashboards
  must sum through them, not through `delivery_date` alone. A *requested* refund is
  deliberately not a reversal.
  (e) **An out-of-scope case answers 403, not 404**, because it reuses
  `ForbiddenException`: whether a case id exists is itself another brand's
  information, and this needed no new exception type or handler branch.
  (f) **Four event types added to the catalog** beyond the spec's list —
  `case.pm_assigned`, `expert.declined`, `case.resumed`, `case.refund_denied` —
  because the acceptance criterion is exactly one event per transition and those
  four had none. `checklist.requested` and `case.delivered_to_client` are *not*
  defined yet: Units 05/06 add them when something publishes them.
  (g) **The GM is a superuser on every transition** — decided. Each gate is the
  spec's actor column *plus* the GM, applied as one `GM_OR` constant prefixed onto
  every `@PreAuthorize` rather than eighteen hand-maintained role lists, so a new
  route cannot forget it. The two refund rulings stay GM-**only** (GM-also would be
  meaningless there), and `RefundService` re-checks the role in the service because
  it is the one path that touches money. The route table in `CaseControllerTest`
  asserts the GM gets through all twenty.
  (h) **One exception state at a time.** A case on hold must be resumed before a
  refund can be requested. Simplest correct reading of the schema (there is one
  `exception_state` column); revisit if a client on hold asking for a refund turns
  out to be common.
  (i) **Client/expert transitions are staff-recorded for now.**
  `clientApproveDraft`, `clientRequestRevisions`, `expertSigned` and
  `expertDeclined` have staff endpoints so a case is not stuck before Units 14/15
  exist. Those units call the same service methods behind their own filter chains —
  and will need a principal, since `apply(...)` reads the actor from
  `TenantContext.current()`.
  (j) **Testcontainers gap still open** (carried from Unit 03). The two new DB
  checks live in the same `-Devalos.db.test=true` gated class.

- **Unit 05 deviations / decisions to confirm.**
  (a) **A rejected signature is logged, not archived.** `webhook_event` only ever
  holds deliveries that verified, so `signature_verified` is always true today. An
  unverified body is not evidence of anything, and archiving it would let anyone who
  can reach the URL fill the table — and the unique `(source, external_id)` would
  collide on the second attempt anyway. The spec's step 1 says "log", which this is.
  (b) **Audit records `CASE` + `AuditAction.CREATED`**, not a literal `CASE_CREATED`
  action, matching Unit 04's object-type + action convention. The pair reads the
  same and needs no new enum value.
  (c) **`AuditService.recordSystemEvent` takes the brand explicitly.** Unit 03 note
  (f) refused a `brandId` parameter on `recordEvent`; a webhook has no authenticated
  caller, so without this every case creation would audit against a null brand and
  drop out of that brand's trail. Separately named so no request-scoped caller can
  reach it, and the argument is only trustworthy because the endpoint token is the
  most authoritative brand signal there is (invariant 8).
  (d) **`case_code` is `<initials>-<year>-<6 hex>`** (`IE-2026-375863`). Random
  rather than a per-brand sequence, which would need a counter table and a lock; a
  collision hits the unique constraint and returns a retriable 5xx.
  (e) **The signature header name is configuration**
  (`evalos.webhook.signature-header`, default `X-Evalos-Signature`) because GHL's
  real header is unconfirmed. The one knob this unit needs to be re-pointed without
  a code change. The **payload shape is also assumed**, and is deliberately confined
  to `GhlPaymentHandler.PaymentConfirmed` so a correction is one file.
  (f) **The idempotency key is scoped by brand (`V13`), replacing the spec's
  `UNIQUE (source, external_id)`.** Closed, was an open question. The spec's key is
  brand-agnostic while each brand is a separate GHL sub-account numbering its own
  invoices; reached live, XpertsPortal posting its own `INV-LIVE-0001` was swallowed
  as International Evaluations' duplicate, created no case for a paid deal, and
  handed the caller the *other brand's* event id. `V13` makes it
  `UNIQUE NULLS NOT DISTINCT (source, brand_id, external_id)` and the lookup became
  `findBySourceAndBrandIdAndExternalId`, so each brand's key is its own; the interim
  409 guard is deleted. `NULLS NOT DISTINCT` because `brand_id` is nullable and
  Postgres would otherwise treat two brand-less rows as distinct, losing exactly the
  deduplication the constraint exists for.
  (g0) **Defect found by the post-commit spec audit and fixed: a failed delivery
  could never be retried.** The dedupe check short-circuited on *any* archived row,
  so after a handler failure (which archives the row unprocessed and returns a
  retriable 5xx) the redelivery was answered `duplicate` and the handler never ran
  again — the paid case was lost for good. "Already seen" is not "already done": the
  gateway now only treats a row as a duplicate when `processed` is true, and reuses
  the unprocessed row as the retry, so a redelivery succeeds without creating a
  second case. This was acceptance criterion 7's second clause, and it survived
  because the original test asserted only the 5xx and the recorded error, never the
  recovery. `InboundWebhookTest.aRedeliveryAfterAFailureRetriesInsteadOfLooking\
  LikeADuplicate` now covers it (written failing first, to prove the defect).
  (g) **`ChecklistTemplates` is a static map, not a table.** It moves into the
  database the first time a Brand Manager needs to edit a checklist without a
  deploy; the seed for that table is this map.
  (h) **A `ponytail-review` pass found ~35 lines of cruft, now cut**: a truncation
  guard on an unbounded `text` column, a redundant `processed = false`, two unused
  `ContactSnapshot` getters, two `Ack` factory methods, a redundant `List.copyOf`
  around `toList()`, a speculative `"id"` idempotency-key candidate, and a
  `reduce("", String::concat)` that is `Collectors.joining()`. **Not** cut, by
  decision: the transport record and the intake command record declare the same 21
  fields with a mapper between them — that split is what keeps an unconfirmed payload
  shape out of `service`, and the payload shape is the thing most likely to change.

- **Unit 02 latent test bug, surfaced and fixed.**
  `SecurityFlowTest.tamperedTokenIsUnauthenticated` flipped the **last** character
  of the JWT signature. base64url of a 32-byte HMAC is 43 characters, so the final
  one carries only four meaningful bits — flipping it can decode to the same
  signature, and the tampered token then verifies (the test returned 200, not 401).
  It had been passing on the luck of what the signature ended with. It now flips
  the first, fully significant, signature character.

- Reconciled from three source documents (`IE_CRM_Spec_v2`, the Hybrid Platform
  Architecture, and the Feature Inventory FRD) into the EvalOS Technical Design
  Document v1.1, which is the source of truth. Where the original context files
  conflicted with v1.1, v1.1 wins (multi-brand, 8-stage, no object storage, no
  mail, manual payouts, GM/Brand-Manager roles).
- CRM spec automation rules A05–A24 (production/expert/delivery/KPI) are in scope
  across Units 04–19; A01–A04/A06 (lead/sales/marketing) are GHL's job.
