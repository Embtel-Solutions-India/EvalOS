# Progress Tracker

Update this file after every meaningful implementation change.

## Current Phase

- **Phase 1 — Structure the data (the spine) is complete.** Units 01–10, plus 05a. Per
  `context/specs/00-build-plan.md` the phase boundaries are 01–10 / 11–17 / 18–20, so Units 06
  (notification centre), 07 (app shell), 08 (production board), 09 (case detail) and 10 (doc
  checklist) are all Phase 1 — this tracker had been calling 06 onward "Phase 2" since Unit 06,
  which the build plan does not say. Corrected here rather than left to compound.
- **Phase 2 — Connect the seams has not started.** It is Units 11–17. The build plan's
  `## Phase 3` heading used to sit above Unit 17 and contradict its own roadmap line; the heading
  moved to Unit 18, so Dashboards is Phase 2 wherever you read it.
- **Phase 1 is now verified, not just written.** All 183 backend tests execute with none skipped —
  the 18 DB-backed ones included, for the first time since Unit 03 wrote them — and CI runs them
  against a real Postgres on every push. See the entry at the end of Completed.

## Current Goal

- Unit 11 — Expert database (ENM) + bulk sheet upload, the first unit of Phase 2. Nothing in
  Phase 1 is outstanding. Read **Phase 2 readiness** under Next Up first: the expert field-tag
  taxonomy is undefined and Unit 12's match scoring depends on it, so it wants deciding inside
  this unit rather than after it.

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
    (`payment.confirmed` live *as of this unit*; `refund.requested`/`contact.updated`
    recognized and logged no-ops), and parse-then-trust validation of the payload.
    **`GhlPaymentHandler` and the `payment.confirmed` route no longer exist** — Unit 05a
    replaced both. Nothing in the running system handles `payment.confirmed` today.
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
    `POST /api/cases` that creates a case breaks the build. (The test now names
    `GhlContactHandler`; the guarantee is unchanged.)
  - Verified: `./mvnw clean verify` BUILD SUCCESS, 87 tests (76 run — new:
    `InboundWebhookTest` 13, `CaseIntakeServiceTest` 7 — plus 11 DB-gated), the 11
    DB-gated checks green against local Postgres 18 (`V11`–`V13` + `V901` applied,
    `validate` passes, the brand-scoped unique key refuses a second archive per brand
    while allowing the same invoice ref from another brand, and two brand-less rows
    still deduplicate).
  - **The `payment.confirmed` live-run evidence that used to sit here has been removed, not
    re-dated.** It recorded a signed `payment.confirmed` creating `IE-2026-375863`, and that
    handler was deleted in Unit 05a — so it was evidence for code that no longer exists, which
    is worse than no evidence: it read as a current guarantee about the live intake path.
    The gateway behaviour it also demonstrated (replay → `duplicate`, wrong signature → 401,
    unknown token → 404) is re-proved against `contact.created` in the Unit 05/05a live-run
    entry below, which is the only live evidence for Handoff A that still describes the
    running system.

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

- **Unit 05/05a live end-to-end run — acceptance criterion 1 closed for the current
  handler.** The previous live evidence (`IE-2026-375863`) was a signed
  `payment.confirmed` recorded before the pivot, so nothing had exercised
  `contact.created` over real HTTP + HMAC + Postgres. Now it has, against the running
  app on the `local` profile:
  - A signed `contact.created` → `200 accepted`; the replay of the same `event_id` →
    `200 duplicate` and no second case. Wrong signature → `401 SIGNATURE_INVALID`;
    unknown token → `404 UNKNOWN_ENDPOINT`. **A payload carrying only a resource `id`
    → `400 MISSING_EXTERNAL_ID`**, which is the review fix behaving as intended: it
    fails loudly rather than deduplicating on a contact id.
  - The created case (`IE-2026-5DFC40`) is `DOC_COLLECTION` / `IN_POOL` / `ON_TRACK`,
    **`paid = false`**, `deal_value = 900.00` (the quote), `revenueRecognized = false`,
    with a 4-item `REQUIRED` checklist, a contact snapshot, and
    `NEW_LEAD` ×2 (GM + that brand's manager only).
  - `assign-pm` succeeds while unpaid — doc collection is deliberately allowed to
    proceed — and then **`docs-complete` answers `409 ILLEGAL_TRANSITION` "the case has
    not been paid"**. After `mark-paid` it answers `409 "not every checklist item is
    uploaded or approved"`, i.e. the paid guard clears and the next precondition takes
    over, in that order. `NEW_CASE_IN_POOL` ×2 is raised at payment, not creation.
  - `mark-paid` corrected `950.00 → 1600.00 → 1725.50` with `paid_at` unchanged across
    both corrections, and a `CASE_MANAGER` bearer got `403` from the service-layer
    guard.
  - `webhook_event` holds exactly one processed, verified row per valid delivery and
    **nothing at all** for the rejected attempts — an unverified body is logged, never
    archived. Audit shows `CREATED actor=SYSTEM brand=<IE>` for the webhook and a null
    brand for the GM's action, both as designed.
  - Gap noted, not a defect: **the seed has no `PROJECT_COORDINATOR` login**, so the
    four Coordinator-gated transitions can only be driven as GM locally. That is the
    same Coordinator-scope open question below, now visible in the seed as well as the
    schema.

- **Unit 06 — In-app notification centre.** The events Units 04/05 published to nobody
  now reach somebody. No migration: the `notification` table is Unit 03's.
  - `notification/NotificationListeners` — the spec's event → recipient table as a
    literal table (`EnumMap` of event → recipient function + heading + message), so a
    mis-wired row is a data diff rather than a buried branch. Synchronous, so it runs
    inside the transition's transaction: a rolled-back transition cannot leave an alert
    claiming it happened.
  - `notification/RecipientResolver` — the one place role → member lookup lives. Every
    lookup names the brand except the GM's, which is brand-less by definition. Returns
    **empty rather than a fallback** when no PM/CM is assigned: an alert addressed to
    "whoever" is how a queue nobody reads gets built.
  - `notification/NotificationService` — the only writer of the table, and the only
    reader the endpoints use. Writes join the caller's transaction.
  - `web/NotificationController` — the four spec routes. **No `@PreAuthorize` and no
    recipient parameter**, deliberately: every staff role has a bell and none may read
    another's, so identity narrows every route and a role gate would be the wrong tool.
  - `service/PoolNotifier` **deleted** — its two call sites are the `case.created` and
    `case.paid` listeners now, which is what the spec's "event-driven, no manual
    triggers" asks for. `CaseIntakeService` and `CaseLifecycleService` each lost a
    dependency.
  - Verified: `./mvnw verify` BUILD SUCCESS, **126 tests** (113 run — new
    `NotificationListenersTest` 14, `NotificationServiceTest` 10,
    `NotificationControllerTest` 6), and **13 DB-gated green against local Postgres 18**.

- **Unit 07 — App shell + role/brand-scoped routing.** The frontend stops being a
  health-check page. **First unit since 01 to touch `frontend/`.**
  - Backend: `web/BrandController` + `service/BrandQueryService` +
    `BrandRepository.findByActiveTrueOrderByNameAsc`. `GET /api/brands` is **GM-only,
    gated twice** (route and service) because it is the one deliberately cross-brand
    read in the app — knowing the shape of the business is itself cross-brand
    information. `BrandOption` projects `id`/`name`/`slug` only: the webhook token and
    signing secret live on the same entity and must never leave it.
  - `lib/session.ts` — token in a module variable mirrored to **sessionStorage** (dies
    with the tab). `lib/api.ts` gained a request interceptor that attaches the bearer
    and a response interceptor that drops the token on **401 only** — a 403 means
    "signed in, not allowed", which is a screen, not a logout.
  - `lib/auth.tsx` — `AuthProvider` with a three-state discriminated union
    (`loading`/`anonymous`/`authenticated`). **Role and brand come from `/api/me`, never
    from the login response**, so there is one source of identity rather than two that
    can disagree.
  - `features/shell/navigation.ts` — **the nav and the route allow-list are one table.**
    Two tables is how a screen ends up deep-linkable but unlisted, or listed and then
    403. `navFor(role)` filters it; `mayReach(role, path)` guards the router against the
    same field.
  - `features/shell/{AppShell, LeftNav, TopBar, BrandSwitcher, DateFilter,
    NotificationBell, PlaceholderPage, filters}` — the shell from `ui-context.md`.
    `filters.tsx` holds `activeBrandId` (null = all brands, GM only) and `dateRange`.
  - `features/auth/LoginPage`, `components/Forbidden`, `features/dashboards/RoleDashboard`.
    403 is a **screen, not a redirect**, so the refused URL stays visible.
  - Deleted `components/Layout.tsx` and `pages/Dashboard.tsx` — the shell supersedes
    both; the Unit 01 health-check page had no remaining caller.
  - `V902` local seed adds the **Project Coordinator and Expert Network Manager logins
    V900 never had** (`pc.ie@`, `enm.ie@`), because acceptance criterion 1 is "each of
    the six roles" and four of six is not that.
  - Verified: `npm run build` clean (tsc + vite), `npm run lint` no errors,
    `./mvnw verify` BUILD SUCCESS **126 tests** (new `BrandControllerTest` 3), and
    **live against the running stack**: all six roles authenticate, `/api/me` returns
    the right role and brand for each, `/api/brands` is 200 for the GM and **403 for
    all five other roles**, the brand payload carries only `id`/`name`/`slug`, the
    notification list/read-all/count round-trip works through the bell's endpoints, a
    garbage token is 401, and the Vite `/api` proxy plus SPA deep-links serve.

- **Contact identity — the two duplicate-case defects the Unit 06 review found, fixed.**
  Both let intake create a second contact and therefore a second case for one piece of
  work. Neither was in Unit 06's own code; the review just found them there.
  - **`V16__contact_identity.sql`.** `contact_snapshot` had **no unique key at all**, so
    `V15`'s `(brand_id, contact_id, service_type)` index was only unique once a snapshot
    existed — for a contact EvalOS had never seen, two concurrent deliveries each
    inserted their own snapshot, got different `contact_id`s, and both sailed through.
    `V16` adds partial unique indexes on `(brand_id, ghl_contact_id)` and
    `(brand_id, lower(email))`. `lower(...)` because the lookup is
    `findByBrandIdAndEmailIgnoreCase` — a capitalised address must not become a second
    person. Verified no duplicates existed in the dev database first.
    **`V15`'s comment claiming the race "cannot race" stays wrong on disk**: it is
    applied, and an applied migration is never edited (invariant 9). `V16`'s header
    corrects the record instead.
  - **`CaseIntakeService.existingContact` needed no race at all.** Its two lookups were
    exclusive `return`s, so a delivery carrying a GHL id that missed the id lookup never
    tried email — and since the payload has no `@NotBlank` on that id, a first delivery
    could store a snapshot without one. Second snapshot, second case, no concurrency
    required. Now falls through with `.or(...)`, and `ContactSnapshot.linkGhlContact`
    backfills the id onto an email-matched row so it stops depending on the email
    forever. **Write-once**: an id already present is never replaced, because two GHL
    contacts sharing an email would otherwise let the second take over the first's
    snapshot and every case pointing at it.
  - Verified: `./mvnw verify` BUILD SUCCESS **129 tests** (new: the sequential-duplicate
    case and the write-once guard), and **14 DB-gated green** — `V16` applied and
    `aContactIsUniquePerBrandByGhlIdAndByEmail` proves both indexes refuse a duplicate
    while the other brand keeps its own contact with the same id and email.
  - Decision worth confirming: **one email per contact per brand is now enforced**, not
    just assumed. An `ATTORNEY` contact may be a firm, so a shared office inbox across
    several applicants would now be refused rather than silently merged. Refusing is the
    safer half — a wrong merge attaches a case to the wrong person — but if it fires in
    practice the fix is a real contact key from GHL, not dropping the index.

- **Unit 08 — Production Kanban board.** The first screen with real data in it, and the
  first consumer of the shell's brand and date filters.
  - **The scope defect it had to fix first.** `ScopePredicate.Fields` carried one
    `assignee` attribute and `CaseRepository` declared `assignedCm`, so "assigned to me"
    only ever meant the Case Manager. A case is one pipeline worked by several people in
    different slots, so a Coordinator (also `Tier.SELF`) matched **no case at all**: empty
    board, and 403 on the four transitions the design makes them the actor for. The axis
    is now a **set** of attributes and a SELF caller matches when *any* of them names them
    (`assignedCm` OR `assignedCoordinator`). Fixed in the one place all callers route
    through, not per-query. `V17__case_assigned_coordinator.sql` adds the missing column +
    `(brand_id, assigned_coordinator, current_stage)`, mirroring the CM's board index.
    **Closed by giving the axis its column, not by widening the predicate** — an entity
    with no assignment column (expert, payout) is still deliberately brand-wide for a SELF
    caller, and that is now asserted rather than implied.
  - `POST /api/cases/{id}/assign-coordinator` (+ `ASSIGN_COORDINATOR` action and
    `case.coordinator_assigned`), because a column nothing populates is the same bug with
    an extra migration. GM / Brand Manager / PM — all three are staffing decisions.
    Declared on every active stage and **re-assignable**, unlike `assignPm`: coordination
    changes hands mid-pipeline and there is no pool to leave. `CaseSnapshot` gained the
    field too, or the audit row for the assignment would show a before/after that look
    identical.
  - `service/CaseBoardService` + `web/CaseBoardController` → `GET /api/cases/board`,
    grouped into the five stage columns and three exception lanes. **Calls
    `CaseLifecycleService.list` rather than building a second scoped query** — a board that
    filtered its own way could disagree with every other read about what the caller may
    see, and it inherits the SLA recompute for free. One batched query for the client
    names, not one per card.
  - **A case appears exactly once**: in its exception lane if it holds one, in its stage
    column otherwise — a case on hold is not also sitting in Doc Collection. `CLOSED` is
    not a column, so a settled refund drops out of the lane and the lane stays a queue of
    things still needing a decision.
  - **`brandId` is accepted on this one endpoint** (the GM's switcher, which Unit 07 note
    (d) deferred to here) and applied **after** the scoped read. It can only ever narrow:
    a Brand Manager naming another brand gets an empty board, not that brand's cases.
    `CaseBoardServiceTest.theBrandFilterOnlyEverNarrows` is what holds that.
  - `CaseController.SEES_DEAL_VALUE` went package-private so the board projects through
    the *same* list. Two copies is how a Case Manager ends up seeing the deal value on one
    screen; the board test asserts all six roles.
  - Frontend `features/board/*` (`BoardView`, `StageColumn`, `CaseCard`, `PoolLane`,
    `QuickActionDialog`, `boardApi`). `/board` for GM / Brand Manager / PM / Coordinator
    and `/my-cases` for the Case Manager are **the same component** — the spec's per-role
    wording describes scope, which the server applies.
  - **Defect caught by its own test, before it ever ran live:** `Map.of()` throws on a
    `null` key rather than answering null, and `contact_id` is nullable — so a board
    holding one contactless case NPE'd. The null check in `forCaller` is load-bearing.
  - **Assignment picks a person, it does not ask for a UUID.** The first pass left
    `assign-pm` / `assign-cm` / `assign-coordinator` / `reassign-expert` collecting ids by
    hand, because no roster read existed that a PM could call. Two narrow endpoints close it:
    `GET /api/team-members/assignable?role=` (GM / Brand Manager / **PM**) and
    `GET /api/experts` (GM / Brand Manager / PM / ENM, `AVAILABLE` only).
    - **Both are deliberately separate, narrower projections**, not widened versions of
      existing routes. `assignable` returns `{id, displayName}` and nothing else, so a PM can
      staff a case while still being refused the staff directory (`/api/team-members` stays
      GM/Brand-Manager, asserted in the same test). `/api/experts` returns `{id, fullName}`:
      the encrypted `payment_detail` is on that entity and must never leave it, and the
      quality/performance fields are Unit 11's with their own audience.
    - **The picker cannot offer what the write side would refuse.** `assignable` applies the
      same scope predicate, so a PM sees their team — which is the rule
      `assignCaseManager` enforces ("case manager is not on this case's team"). `/api/experts`
      filters to `AVAILABLE` because `availableExpert` rejects anything else. An empty list
      says why rather than rendering an empty dropdown.
    - **No `uuid` package.** Nothing in the frontend mints an id — Postgres does
      (`gen_random_uuid()`), and a generated one would just fail the `team_member` lookup.
      The blocker was a missing read, not a missing generator.
  - **`STAGE_ACCESS` — how much of each stage a role works.** `full` (drives it) / `status`
    (watches it) / `none` (not drawn). A `status` role keeps the stage-*preserving* actions —
    a Coordinator watching a draft can still put the case on hold — and loses only the ones
    declared *from* that stage. PM full through signing, status on delivery; Coordinator full
    on the two ends and status through the middle; Case Manager full on draft + signing only;
    ENM full on signing, status on assignment and delivery. GM and Brand Manager see all five.
    **Convenience, not enforcement** (principle 7) — the server still gates every transition
    and every read, and several `none` cells were already empty by scope alone (a case naming
    a CM has long left Doc Collection). Held as **one table** rather than a context file, for
    the reason `navigation.ts` gives: a second copy is a copy that drifts.
  - Verified: `./mvnw verify` BUILD SUCCESS **148 tests**, and **all 148 green with zero
    skipped against local Postgres 18** (`-Devalos.db.test=true`): `V17` applied on top of
    20 existing migrations, `ddl-auto=validate` passed. `npm test` **18 tests** green
    (`vitest run`, mutation-checked). And
    `aSelfCallerReadsCasesAssignedToThemInEitherSlot` proves in real SQL that a Coordinator
    reads the case naming them and not the CM's, the CM reads theirs and not the
    Coordinator's, neither sees the unassigned pool row, and another brand's case stays out
    even when it names the same Coordinator. `npm run build` clean, `npm run lint` clean.

- **Visual pass over the shell and the board.** No unit, no backend change, no new dependency —
  the frontend built across Units 07–09 read as a wireframe, and three of its stated design
  intentions were not actually reaching the screen.
  - **The fonts were never loaded.** `ui-context.md` asks for tabular figures on every column
    of dates, counts and case IDs; `tokens.css` declared Inter / IBM Plex Mono as font *stacks*
    with system fallbacks and Unit 01 note (b) recorded that the webfonts were not bundled. No
    system fallback has `tabular-nums`, so **every `tabular-nums` class in the app — 15 files —
    was a no-op for three units.** Now linked in `index.html` (`preconnect` + one `css2`
    request, `display=swap`), with a `.font-num`/`.font-mono`/`.tabular-nums` rule in
    `index.css` so the feature applies rather than being requested per element.
  - **`tokens.css` gained three derived tokens, no new colours**: `--shadow-card`,
    `--shadow-pop` (both the primary text colour at low alpha, so a surface never picks up a
    hue outside the system) and `--ring-focus` (the accent). `index.css` spends them on one
    app-wide `:focus-visible` ring — keyboard operation of a board is not optional — plus a
    `prefers-reduced-motion` block and `.scroll-slim` for the board's horizontal scroller.
  - **`/cases` is deleted from the nav, and no unit ever builds it.** It was a placeholder
    labelled "Case table (Unit 08)" — which is what Unit 08 *did* ship, as the board. So four
    roles had the app's one screen with live data listed *second*, under a page that could only
    ever say "not built yet". Unit 08 note (h) chose not to alias the two; the right fix was
    one entry, not two. `/board` is now labelled "Production board" and nothing links to
    `/cases` (`/cases/:id` is untouched — it is the detail route, not a nav item).
  - **The nav is grouped** — Overview / Pipeline / Records / Admin — via a `group` field on the
    same `NAV_ITEMS` table, built into sections by **consecutive runs** rather than by
    filtering per group, so the table's order is the screen's order and a heading cannot appear
    twice. This also fixes the Unit 07 browser pass's "cosmetic deviation": three roles had
    their primary screen listed last because `NAV_ITEMS` was one flat global order. Grouping
    gave the ordering a home without adding a per-role order field.
  - **`boardPathFor(role)` — the placeholder now offers the way out.** A dead end is a design
    failure, but a hardcoded escape link is a 403 with extra steps, so it walks `/board` then
    `/my-cases` **through `mayReach`** and falls back to the dashboard for the one role that can
    reach neither today (the ENM). Same table, same gate as the router.
  - **`SlaRail` — the board's one instrument.** Each column is capped by a 3px bar split by its
    cases' SLA mix, red-first, so the five columns side by side read as a single line: where the
    risk has collected, not just how much work there is. `slaMix` keeps **`unknown` as a fourth
    band** rather than folding it into `onTrack` — `SlaCalculator` returns null for a closed case
    and for one holding an exception state, and colouring those green would report a stalled
    column as healthy. Empty columns keep a hairline so the rail stays continuous, and the bar
    carries an `aria-label` naming the counts, since a colour-only instrument is not one.
  - **Columns are numbered by their place in the whole pipeline**, so a Case Manager's first
    column is 2 of 5. Numbering their subset from 1 would say the work starts with them. Lanes
    get no number: an exception is not a step. The lanes also moved under a "Off the pipeline"
    heading with a held count, and `readOnly` became a "watching" chip instead of the word
    "status" tucked beside a number.
  - **The board header states the risk, not the volume**: scope + owner filter as an eyebrow,
    then "N cases in view" with overdue / at-risk counts, or "all inside SLA" when there are
    none. It counts **only what is drawn** (this role's columns plus the lanes), so the number
    always matches what the reader can count on screen. `isMine` was extracted from the owner
    filter and is now also passed to every card, so "mine" is visible without filtering to it.
  - The read-failure panel says **"Nothing was changed"** and names the likely cause. A retry
    button with no reassurance about a *read* failure invites the user to wonder what it half-did.
  - Verified: `npm test` **29 tests** (3 new, and **mutation-checked** — folding `unknown` into
    `onTrack` fails the SLA-band test, and numbering the columns after the `none` cells are
    filtered fails the step test; each failed exactly one test and the file was restored
    byte-identically, confirmed by an unchanged bundle hash). `npm run build` clean,
    `npm run lint` clean. Backend untouched.
- **Visual-pass browser verification — confirmed, and it found four defects.** Driven through
  Chrome against the running stack (Postgres 18 + `mvnw spring-boot:run` on `local` + Vite),
  signed in as all six seeded roles.
  - **The webfonts load and the tabular figures are real, measured rather than assumed.**
    `document.fonts.check` is true for Inter and IBM Plex Mono (faces 400/500/600/700), and a
    probe span carrying the app's own classes measures `111111` and `000000` at **exactly the
    same width (54.475px)** with `font-variant-numeric: tabular-nums` computed. That is the
    Unit 01 gap closed with evidence, not a link tag that might be doing nothing.
  - **The SLA rail reads correctly per column** and carries the counts in its `aria-label`
    ("Doc Collection: 4 on track, 103 no clock running"), so the instrument is not colour-only.
  - **Defect 1, in this pass's own header: "all inside SLA" over a board that was mostly
    unknown.** The GM's board showed *150 cases in view · all inside SLA* while the rails
    directly beneath it reported **127 of the 150 with no clock running** — the headline branched
    on `overdue === 0 && atRisk === 0`, which is exactly the overstatement `slaMix` keeps a
    separate `unknown` band to prevent. The header and the instrument disagreed about the same
    data, on screen, at the same time. The predicate moved into `boardRules.allInsideSla` (a
    display branch that wrong is a display branch worth testing) and now also requires
    `unknown === 0` and `onTrack > 0`, so an empty board claims nothing. The board reads
    *150 cases in view · 127 with no clock running*.
  - **Defect 2: the case detail page's "Manage the checklist" link answered 403 for every role
    but one.** `/checklists` is the Coordinator's screen, and the client nav table has no
    superuser row the way the backend's `@PreAuthorize` does — so a **Project Manager clicking
    it landed on the 403 screen, and so would the GM**. Pre-existing from Unit 09 note (g),
    found by clicking it. Now gated on `mayReach`, the same table the router guards against, and
    pinned by a test asserting the Coordinator is the *only* role that may reach that path.
  - **Defect 3: the case detail failure state sent a Case Manager and an ENM to a 403.** It
    hardcoded `/board`; `/cases/:id` is open to every role, so the escape hatch on the error
    screen was itself refused for the two roles without `/board`. Now `boardPathFor`, verified
    live: the ENM gets "Back to your dashboard" and the Case Manager "Go to my cases".
    **This is the second and third instance of one bug** — a link offered without checking the
    reader's allow-list — which is why `boardPathFor` exists at all. Worth grepping for a fourth
    before adding any new cross-screen link. (`components/Forbidden` is fine: `/dashboard` is
    reachable by every role.)
  - **Defect 4: two sentences ran together on the placeholder** ("Document checklist tracking
    (Unit 10) Everything else in your scope is already live") because the nav table's `becomes`
    strings are labels with no trailing punctuation. Two elements now.
  - Per-role confirmations: the grouped nav renders the right set and headings for all six
    roles; the **Case Manager's columns are numbered 2, 3, 4** — whole-pipeline numbering working
    as designed rather than renumbering their subset from 1 — with the "watching" chip on Expert
    Assignment and a "Yours" badge on their cards; the Coordinator's placeholder offers "Go to
    production board" and the **ENM correctly falls back to the dashboard**, being the one role
    with no board; the ENM's Expert database now precedes Payouts, and a PM's board sits directly
    under Dashboard, which was the Unit 07 ordering deviation.
  - **The heading now comes from the nav table**, so `/my-cases` is headed "My cases" rather than
    "Production board" — and the eyebrow's owner half is drawn only when the filter narrows,
    because "everyone" is false for a Case Manager whose board the server has already scoped to
    them.
  - **The focus ring fires**, confirmed on a keyboard-focused nav link: `:focus-visible` matched
    and the computed `box-shadow` was exactly `--ring-focus`
    (`rgb(255,255,255) 0 0 0 2px, rgb(53,82,224) 0 0 0 4px`). **Partial:** I could not get the
    automation to land real Tab focus on a `<button>` — CDP `.focus()` never sets
    `:focus-visible`, and after a navigation the Tab keys went to the browser UI. The rule covers
    buttons by the same selector, but the button case is unobserved; worth one manual Tab when
    somebody is at the keyboard.
  - Console is clean on a fresh load of the board and of a case detail (Vite + React DevTools
    notices only). The exceptions seen mid-pass were HMR firing between two of my own sequential
    edits, where a symbol was used a moment before its import landed — not a live defect, but a
    reminder that in this app HMR runs the half-edited file.
  - Verified after the fixes: `npm test` **31**, `npm run build` clean, `npm run lint` clean.

- **Unit 10 — Document checklist board + Coordinator flow. Phase 1 is closed.** The screen
  `DocumentsPanel` has linked to since Unit 09, and the last piece of the intake→production
  handoff. **No migration** — the `document_checklist_item` table is Unit 03's and nothing
  needed a new column.
  - `service/ChecklistService` — the board, the two item writes, and the chase. **Nothing here
    moves a case**: `docs-complete` stays Unit 04's transition on `CaseController`, so this unit
    maintains the rows that guard reads rather than owning a second copy of the rule. Every read
    starts from `CaseLifecycleService.read`, so scope is decided where the rest of the system
    decides it.
  - **The board is built on `CaseBoardService.forCaller`, not a second scoped query** — the same
    reasoning that service gives for building on `CaseLifecycleService.list`. It inherits the
    scope, the SLA recompute, the batched client names, and the rule that `brandId` can only ever
    narrow. Filtered to `DOC_COLLECTION`; **a case holding an exception state stays listed**,
    which is the opposite of the production board on purpose — "on hold awaiting client" is
    exactly the case whose documents have not arrived, and dropping it would hide the queue this
    screen exists to show.
  - **"Last chased" is derived from the append-only trail, not a column on the case.** New
    `AuditAction.CHASED` (open vocabulary, no CHECK, no migration) plus one batched finder,
    `AuditEventRepository.findByObjectTypeAndActionAndObjectIdIn`. The chase had to be recorded
    regardless, so a second copy of the fact would only be a second thing that can disagree —
    and Unit 19's timers inherit the answer for free. The finder is a **read**: the whitelist in
    `DomainInvariantsTest.theAuditRepositoryCannotChangeHistory` was widened by one name, which is
    what that test is for, and nothing there can still change a row.
  - Checklist audit rows are written against the **case**, not the item, with the change stated
    in `CaseSnapshot.note` ("Passport: REQUIRED → UPLOADED"). The Coordinator's work therefore
    appears on the Unit 09 timeline with no change to `CaseTimelineService` — a trail is only
    useful if one screen shows all of it.
  - `web/ChecklistController` — five routes, no class-level `@RequestMapping` because the board
    is its own screen (`/api/checklists/board`) while the items belong to a case
    (`/api/cases/{id}/checklist…`). The per-case **read has no role gate**, like the timeline:
    every role that can open a case can see what it is waiting for, and the scoped load decides
    which cases those are. The four writes are gated to GM / Brand Manager / Coordinator.
  - **`ChecklistItemStatus.isComplete()` — one predicate where there were two, about to be three.**
    `markDocsComplete`, the case-detail summary chip and now the board all have to agree on "this
    document is in", and `CaseDetailService` was keeping its copy in step by comment. A chip
    reading "6 of 6" over a transition that then refuses is the failure; one enum method is the fix.
  - **`checklistSatisfied`, deliberately not `mayMarkComplete`.** The transition also requires the
    case to be paid and to have a PM. Restating those in the client would be the copy that goes
    stale, so the button is enabled on the checklist alone and the server answers 409 naming
    whichever precondition failed — which the panel shows. Same reason an empty checklist is
    **not** satisfied: `markDocsComplete` refuses one, so a full bar would say the opposite.
  - `event/CaseEvents` gained `checklist.reminder` (published by the chase) and
    `docs.escalation.day3`. **The second is declared and published by nothing** — Unit 19 owns the
    timer, Unit 10 owns the contract it fires against, which is what the spec's "SLA / reminder
    hooks" section asks for.
  - A chase outside `DOC_COLLECTION` is refused (409). Not a formality: it reaches a real client
    through GHL, so it is a mistake made *outwardly*. No cool-off between chases — a Coordinator
    sending two is answering a phone call, and the trail records both.
  - Frontend `features/checklist/*` (`ChecklistBoard`, `CaseChecklist`, `checklistApi`,
    `checklistRules` + its test). A **list, not a Kanban**: one column, and what varies between
    these cases is how complete and how old they are, which reads better in rows.
    **Aging is not the SLA** — `SlaCalculator` measures business hours against a stage budget;
    the spec's 24h/48h bands are wall-clock, which is what a client experiences, so
    `checklistRules` computes them client-side from `stageEnteredAt` and they stay live between
    reloads. An untimed case is `unknown`, never green, for the reason `slaMix` keeps that band.
  - **The pending-docs queue is a split, not a re-sort**, so the server's longest-wait-first order
    survives in both halves. A case is due a chase when the documents are short, the wait is past
    24h, **and** nothing was sent in the last 24h — the third condition is what makes the queue
    empty when the Coordinator works it rather than nagging about a client contacted an hour ago.
  - `markDocsComplete` on the panel goes through the board's own `performAction` and the
    `docs-complete` entry in `QUICK_ACTIONS`, not a second POST: pressing it on a board card and
    pressing it here have to be the same operation.
  - `App.tsx`'s `BOARD_ROUTES` set became a `SCREENS` map, so a unit landing its screen is one
    entry rather than another branch.
  - Verified: `./mvnw verify` BUILD SUCCESS **180 tests** (16 DB-gated skipped) — new
    `ChecklistServiceTest` 12, `ChecklistControllerTest` 8. `npm test` **44 tests** (13 new),
    `npm run build` clean.

- **Decision taken, closing the Unit 09 open question: the GM and Brand Manager reach
  `/checklists` and `/delivery`.** Both were `PROJECT_COORDINATOR`-only, so the GM — a superuser
  on every backend transition — could not open the screen that drives one. That is an
  inconsistency rather than a safeguard, and "the GM sees everything" is the rule everywhere else.
  All three roles get the **writes** as well as the read: a screen a Brand Manager can watch but
  not touch would need a second permission concept for no stated need, and every write names its
  actor in the trail. **The Project Manager is deliberately still out**, even though they may call
  `docs-complete` — they act on the outcome, not the chase, and the per-case read is open to them.
  The nav table and the backend `@PreAuthorize` now carry the same three roles, and
  `navigation.test.ts` says so explicitly, because a client offering a screen the server refuses
  is the exact failure that table exists to prevent.

### Unit 10 code review — four Important findings fixed, and the drift they exposed

A review of `a3d3770..74bcacb` (the visual pass plus Unit 10) found **no Critical issues**: brand
scoping and append-only both held under tracing, "no new migration" was correct, and "Phase 1
closes" was substantiated. What it did find was two defects sitting in the exact flow Unit 10 was
built to serve, and both were the same shape — a client offering something the server or the data
would not back.

- **A Brand Manager got an enabled "Mark docs complete" button the backend answered 403 on.**
  Unit 10 widened `/checklists` and its three writes to the Brand Manager but left
  `CaseController.docsComplete` on `GM · PROJECT_COORDINATOR · PROJECT_MANAGER`, and
  `CaseChecklist` gates that button on `checklistSatisfied` alone rather than on
  `QUICK_ACTIONS.roles`. **Resolved by widening the backend, not by hiding the button**
  (confirmed decision): a role that can add a required document, approve one, and chase the
  client, but not say the collection is finished, has the screen without its purpose. The gate is
  now `GM · BRAND_MANAGER · PROJECT_COORDINATOR · PROJECT_MANAGER`, and both halves are pinned —
  `CaseControllerTest.docsCompleteAdmitsEveryRoleThatWorksTheChecklistScreen` walks the four
  admitted roles and the two refused, and `boardRules.test.ts` asserts the client's role list
  equals it. This is the same assertion `navigation.test.ts` makes for the screen; the seam that
  leaked was one layer down, on the action.
- **The pending-docs queue did not empty when the Coordinator worked it.** `needsChase` reads
  `card.lastChasedAt`, but `CaseChecklist.onChase` only updated its own local state, so a chased
  row stayed under "Due a chase" and the "N due a chase" count stayed stale until a full reload —
  precisely the nagging the 24-hour condition exists to prevent. `ChecklistBoard` now patches the
  one card with the server's timestamp (patched, not reloaded: a reload would re-sort every row
  underneath somebody mid-triage).
- **The chase response contradicted its own comment.** `ChecklistController.chase` claimed it
  answers the refreshed checklist "instead of holding a value the trail would have to agree
  with", but `ChecklistView` had no `lastChasedAt`, so the panel stamped `new Date()` and
  displayed the browser's clock. `lastChasedAt` is now on both `ChecklistService.CaseChecklist`
  and `ChecklistView`, read through the same batched trail query the board uses; the panel's
  local `chasedAt` state and its `lastChasedAt` prop are gone. That also fixed the
  reset-on-collapse bug, where reopening a panel after a chase showed "Never chased" again.
- **The two deliberately-unscoped finders had no real-SQL brand-isolation test.**
  `DocumentChecklistItemRepository.findByCaseIdIn` and
  `AuditEventRepository.findByObjectTypeAndActionAndObjectIdIn` carry no brand predicate by
  design, protected by a javadoc convention ("do not call it with ids that came from a
  request"). Two tests added to `LocalPostgresIntegrationTest`: `findScoped` keeps two brands'
  checklist items apart while `findByCaseIdIn` answers for whatever ids it is handed, and the
  chase finder returns every chase, only chases, and only for the ids given. **Both are
  DB-gated and did not execute** — see the honesty note below.

Minors from the same review, applied: the unused `--shadow-pop` token deleted; `border-radius`
dropped from the global `:focus-visible` rule, which had been re-cornering every focused card
(`rounded-lg`) and modal (`rounded-xl`) to the badge radius; the header's "N ready for the PM"
relabelled **"N with all documents in"**, because it counted unpaid cases that the row two lines
down chips as "Unpaid" for exactly the reason docs-complete would refuse them — the same
header-contradicts-instrument class as the `allInsideSla` defect the visual pass fixed;
`aria-controls` added to the Open/Hide-checklist button; `ChecklistService.setStatus`/`addItem`
now return `void`, since every caller re-reads the whole checklist and discarded the row.

**Test state, stated plainly.** Backend **183 passed, 0 failed, 18 skipped**; frontend **45
passed**; `tsc -b`, `vite build`, and `oxlint` clean. All 18 skips are
`LocalPostgresIntegrationTest`, which now includes the two new brand-isolation tests — so the
finders they cover are still **unproven against real SQL on this machine**. "18 DB-gated skipped"
is not "18 passed", and the reviewer's recommendation stands: get a Postgres (or Testcontainers)
into the loop before Unit 11, which adds the expert roster and the encrypted `payment_detail`.
That is Unit 01 note (a), still open since the scaffold.

**One spec correction, one plan correction, one standards correction.** All three were drift
between a context file and the code, which `CLAUDE.md` requires closing rather than carrying:

- `specs/10-doc-checklist-coordinator.md` acceptance criterion 1 said "the Coordinator's **brand**
  cases in DOC_COLLECTION". `PROJECT_COORDINATOR` is `Tier.SELF`, so the scoped read matches on
  `assigned_coordinator` — the implementation is right and the sentence was wrong. Corrected, with
  the consequence stated: an intake case with no coordinator assigned appears on no Coordinator's
  board, is visible to the GM and Brand Manager, and is staffed from the production board.
- `specs/00-build-plan.md` still described Unit 05 as the GHL **`payment.confirmed`** handler with
  idempotency on the invoice id. Handoff A is `contact.created` deduped on the source event id,
  which is what `WebhookRouter.CONTACT_CREATED` and `architecture.md` both say. Corrected — the
  commit that moved this file and fixed the phase count missed it.
- `ui-context.md` mandated Lucide React and a shadcn/Radix set in `frontend/src/components/ui/`.
  Neither exists, neither is in `package.json`, and `LeftNav.tsx` explicitly declines both and
  draws inline SVG paths. The standard now records what the code does and when to revisit it:
  Radix stays the intended source for the first component with real focus-trapping or ARIA
  behaviour, and Lucide for when the glyph count outgrows inline paths. The "data tables" surface
  is marked as unused now that `/cases` is deleted.

### PR #7 review — two findings the Unit 10 review had missed

A second review pass over the same range, run on the open PR. It surfaced two real defects that
the first pass did not, and both are the same failure the first pass thought it had closed —
applied to one caller and not its siblings.

- **Only the chase told the board anything.** The Unit 10 review fix made a chase patch the
  board's copy of the case, but a status change and an item add still refreshed the open panel
  only. The board draws four things from its own copy — the completeness bar, the "all documents
  in" chip, the header counts, and `needsChase` — so marking the last document APPROVED enabled
  the panel's own complete button while the row sat under "Due a chase" with a stale fraction
  until the next full reload. Fixed at the root rather than per caller: all three writes already
  funnel through `CaseChecklist.run`, so the notification lives there and no future write can
  forget it. The patch itself is now a pure function, `applyChecklistToCard`, with three tests —
  including the exact scenario, that a finished case leaves the queue.
- **`/delivery`'s nav entry outran its own backend gate.** Unit 10 widened it to
  `GM · BRAND_MANAGER · PROJECT_COORDINATOR` under a comment claiming "the backend gate on these
  routes says the same three roles". True for `/checklists`; false for `/delivery`, whose only
  transitions (`CaseController.deliver`, `.close`) are `GM_OR + hasRole('PROJECT_COORDINATOR')`.
  Narrowed to match the gate. No Brand Manager ever hit a 403 because the route still renders a
  placeholder — but that is a reason it went unnoticed, not a reason it was safe.

**The test was part of the defect, not the safety net.** `navigation.test.ts` asserted
`['/checklists', '/delivery']` against one shared role list, so it read as though it had checked
both gates while pinning the wrong answer for one of them. It now asserts each path separately
against its own gate, and names why. Worth remembering when writing the next table-driven test:
looping two subjects against one expectation asserts the *intersection* of what you meant.

**And then the `/delivery` entry was deleted outright** — the open question it had been carrying
since Unit 07 is now closed by decision rather than narrowed again. The reasoning is the one that
deleted `/cases` during the visual pass: it promised a "final delivery queue (Unit 13)" that
Unit 13 is not (Unit 13 is *Redacted CV generation*), **no unit anywhere in the build plan builds
a final delivery queue**, and `deliver`/`close` are Unit 04 transitions the Coordinator already
drives from the production board. So it was a label over a placeholder that also spent a unit
claiming a gate it did not have. Nothing is lost: both transitions stay reachable exactly where
they were.

Routes are generated from `NAV_ITEMS`, so deleting the entry deleted the route with it and
`/delivery` now falls through to the not-found view. Its absence is asserted, not assumed —
re-adding it without a screen behind it fails `navigation.test.ts`. Whoever builds the real
screen sets the role list from what the screen does; the build plan is unchanged, because the
missing unit is the honest state of it.

Frontend 48 passed; `tsc -b`, `vite build`, `oxlint` clean. Backend untouched by any of this.

### Phase 1's last gap closed: the DB-backed suite actually runs, and CI runs it

**All 183 backend tests now execute. Zero skipped.**

`LocalPostgresIntegrationTest`'s 18 tests were the ones putting brand-scoping predicates,
`ddl-auto=validate`, the payment-detail ciphertext and the append-only trigger in front of real
SQL — and they had never run in the ten units since Unit 03 wrote them. They pass. That includes
the two brand-isolation tests added for Unit 10's unscoped-by-design finders, so those finders are
now proven rather than promised.

**A number this tracker kept getting wrong.** It has been reporting "183 passed, 18 skipped".
Surefire's `Tests run: 183 ... Skipped: 18` counts skips *inside* the 183, so the real figure was
**165 executing and 18 not**. "183 passed / 18 skipped" added up to more tests than exist and read
as though the skips were extra. Now it is genuinely 183 executing.

Three things were in the way, and all three are fixed:

- **The suite wrote into the dev database.** It now runs in its own `evalos_test` schema, pinned by
  `currentSchema`, so a misconfiguration fails outright instead of quietly writing next door. A
  schema rather than a second database because Flyway can create a schema and cannot create a
  database — no setup step a fresh checkout could skip. The URL comes from `DB_TEST_URL` and
  deliberately not `DB_URL`: a developer with `DB_URL` exported at their dev database would
  otherwise have these inserts follow it straight back into `public`.
- **Nothing ever set the gate.** The suite is gated on `-Devalos.db.test=true` so `./mvnw test`
  stays green on a machine with no Postgres, and for ten units nobody passed it. A flag nobody
  sets is the same as a test nobody wrote. **There was no CI in this repo at all** — no
  `.github/` directory. `.github/workflows/ci.yml` now runs the backend against a `postgres:16`
  service container with the flag on, plus a frontend job (`npm test`, `npm run build` which is
  also the typecheck, `oxlint`), on every push to `main`/`Development` and every PR.
- **`backend/mvnw` was mode 100644 in git.** Not executable, so `./mvnw` would have failed on any
  Linux or macOS checkout, CI included. Fixed with `git update-index --chmod=+x`.

Not Testcontainers: it needs a running Docker daemon (Docker Desktop is installed on this machine
but its daemon is down), and the point of the suite is to run against whatever Postgres is already
there. The CI service container gives the same isolation without the dependency.

The stale claim in the test's own javadoc — "this machine has no Docker" — is corrected too.

**Verified in CI, not just locally.** Run 30586327885: backend `Tests run: 183, Failures: 0,
Errors: 0, Skipped: 0` against a `postgres:16` service container, frontend 3 files / 48 tests
passed with a clean build and lint. Both jobs green.

**One known limitation, and it is CI's, not the suite's.** The frontend job runs `npm install`
rather than `npm ci`. `package-lock.json` is written on Windows, where npm records
`@rolldown/binding-wasm32-wasi` and `@tailwindcss/oxide-wasm32-wasi` but not their `@emnapi/*`
dependencies — this host never needs the wasm fallback, so it never resolves them — and `npm ci`
on Linux rejects the lockfile as out of sync. Neither `--package-lock-only` nor
`--os=linux --cpu=x64` materialises the entries from here, so the lockfile cannot be made
installable off-Windows by the machine that writes it. **The cost is that CI resolves within
semver ranges instead of pinning**, so a bad upstream patch release can reach it. Regenerating the
lockfile once on Linux restores `npm ci`; the reason and that exit condition are written into
`ci.yml` rather than left as folklore. CI caught this on its first run, which is most of the
argument for having it — `npm ci` would have failed the same way for any Linux or macOS
contributor.

## In Progress

- Nothing.

## Next Up

- Unit 11 — Expert database. `ExpertPickerController` already exists as the narrow
  `{id, fullName}` read Unit 08 needed; Unit 11 supersedes it with the real screen (search,
  taxonomy matching, quality scores, sheet upload) and can keep the endpoint as the picker's read.

### Phase 2 readiness — which open questions block which unit

Checked before starting Phase 2, so a unit is not begun against an assumption. **Nothing blocks
Unit 11's start**, but two items bite inside it, and each later unit has a named external
dependency that is not yet confirmed. Phase 2 is Units 11–17 (see the boundary note in the build
plan — the `## Phase 3` heading had been contradicting that).

**Unit 11 — Expert database.** Two decisions land inside this unit, not before it:
- **The field-tag taxonomy is undefined.** `Expert.primaryFields` / `secondaryFields` are
  `String[]` with no controlled vocabulary and no CHECK. Free text is fine for a roster a human
  reads, but **Unit 12's match scoring matches on these tags**, and "Mechanical Engineering" will
  not match "mechanical engg". Whoever fills the roster is choosing the vocabulary, so the sheet
  upload's column mapping and the tag list want deciding together, in this unit.
- **`payment_detail` needs a real `EVALOS_FIELD_KEY` outside local.** No default by design — an
  environment that forgets it fails to start rather than writing plaintext. Fine for dev; a
  deployment blocker whenever one happens, and this is the unit that first writes the field.

**Unit 13 — Redacted CV generation.** The plan says the output is "served on demand (**or written
to the case's Drive folder**)". **There is no Google Drive integration** — `Case.driveLink` is a
string and the architecture's "no object storage" rule means EvalOS holds links, never bytes.
Writing to Drive needs an API client, OAuth credentials and a service account that do not exist.
Serve-on-demand only needs none of that. Decide which before the unit, because one of the two
readings adds an external integration to the phase.

**Unit 14 — Client draft-review portal.** The portal link reaches the client "via GHL". That is
open question (b) below — whether GHL can send a client-facing transactional message on an EvalOS
event trigger. If it cannot, the portal is built and unreachable.

**Unit 15 — Expert portal + Handoff B.** The heaviest external dependency in the phase, and
nothing exists yet beyond the `DROPBOX_SIGN` enum value and staff-recorded stand-ins for the
signed/declined callbacks. Needs: a Dropbox Sign account, an API key, the **callback signing
secret** (already open below), and a signature-request template. The inbound gateway is ready for
it — `WebhookSource.DROPBOX_SIGN` and the brand-scoped idempotency key are in place.

**Unit 16 — Payout ledger.** Self-contained. `payout_ledger` exists from Unit 03 and the plan is
explicit that there is no disbursement rail. No blocking question.

**Unit 17 — Dashboards.** Two open questions attach directly, both listed below: whether
sales/marketing dashboards are GHL-native (default: yes, EvalOS does not build them), and
**StatCommand**, which is still undefined — the standing instruction is not to build an
integration for it until it is specified.

**Cross-cutting, not unit-specific.** The GHL contract (payload shape, signature header, HMAC
encoding, and which contact event actually fires) is the largest risk to code already shipped
rather than to Phase 2, since Handoff A runs on assumptions today. The full brand list matters
whenever a third brand is seeded. Staff SSO stays deferred.

## Open Questions

- ~~**`/delivery` is labelled "Final delivery queue (Unit 13)" and Unit 13 is not that.**~~ —
  **closed by decision: the nav entry is deleted.** See the PR #7 review entry above.
- **The dev `evalos` database still holds ~150 junk cases** in `public`, written by
  `LocalPostgresIntegrationTest` before it was moved to its own schema (`EV-<uuid>` case codes,
  "Unnamed contact", "SERVICE NOT SET") — the Unit 07 hygiene note grown into a board that is
  103/107 test rows in its first column. **The cause is fixed** (the suite writes to
  `evalos_test` now, so the pile cannot grow), but the existing rows are still there and the
  database needs a reset before any demo. Not cleaned up here: deleting rows from somebody's
  database is not a drive-by, and `public` also holds whatever real dev data exists. A targeted
  `DELETE FROM evalos_case WHERE case_code LIKE 'EV-%'` would do it — on request, not unasked.

- **GHL contract still unconfirmed** (was already open, now load-bearing): the
  `contact.created` payload shape, the signature header name, and the HMAC
  encoding are all assumptions. Everything else about Handoff A is verified; these
  three are what a real GHL sub-account has to agree with.
  **How far a correction reaches, honestly** — the earlier claim that the payload shape is
  "confined to one file" was too optimistic and only ever held for one of three cases:
  - a **renamed or re-typed** field is one file: `GhlContactHandler.ContactCreated` and its
    `@JsonProperty`, because the record is transport-only;
  - a **new field that has to reach the case** is at least three: the transport record, the
    mapper to `CaseIntakeService.NewCase`, and `NewCase`/`ContactDetails` themselves — that
    split is deliberate (Unit 05 note (h) kept an unconfirmed shape out of `service`), but it
    means the shape is *isolated*, not *confined*;
  - a **field that turns out not to exist** may also touch `CaseIntakeService` where it is
    applied to the entity, and the intake tests.

  The signature header is genuinely one knob (`evalos.webhook.signature-header`, config, no
  code change). **Also unconfirmed: which GHL contact event actually fires.**
  `contact.created` is the assumption; if the real trigger is a pipeline-stage or
  form-submission event, that one *is* a single constant in `WebhookRouter`.

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
  `@SpringBootTest` in Unit 03 when entities make it worth it. (b) ~~Inter /
  IBM Plex Mono are declared as font stacks with system fallbacks; the actual
  webfonts are not bundled.~~ — **closed by the visual pass above**, and it was not
  cosmetic: no system fallback carries `tabular-nums`, so every tabular-figure class
  added in Units 07–09 was inert until the faces loaded. (c) Boot 3.5.16 chosen over the Initializr's 4.1.0
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
  (a) **`PROJECT_COORDINATOR` stays `Tier.SELF`** — decided, and the runtime gap it left
  is **closed by Unit 08**, by the route this note called for: `V17` adds
  `assigned_coordinator` and the assignee axis became a *set* of columns, so a SELF caller
  matches a case naming them in any slot. Briefly moved to `Tier.TEAM` during the Unit 04
  build, then reverted on instruction — and reverting was right: the fix was the missing
  column, **not** a widened predicate, which would have failed open. The warning that lived
  on the enum constant is now a record of what was fixed.
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
  a code change — and it is the only claim in this note that survived. The **payload shape is
  also assumed**, and was isolated in `GhlPaymentHandler.PaymentConfirmed`. **Both halves of
  that are now stale**: `GhlPaymentHandler` was deleted in Unit 05a (the shape moved to
  `GhlContactHandler.ContactCreated`), and "a correction is one file" was never true for a
  field that has to reach the case — see the open question below for what a correction actually
  touches.
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
  (g) **`ChecklistTemplates` is a static map, and it is the source of truth only for a case
  that does not exist yet.** It is read exactly once — by `CaseIntakeService`, to create the
  `document_checklist_item` rows. **From that moment the rows are authoritative and the map is
  not.** Nothing re-reads it for an existing case, so editing a template can never change a
  case already in flight.
  **This is the fact Unit 10 rests on**: the checklist board edits rows, and there is no
  template to keep in step with them. `CaseDetailService.ChecklistSummary` counts rows for the
  same reason, and its "complete" test is `markDocsComplete`'s, not the template's.
  It moves into the database the first time a Brand Manager needs to edit a template without a
  deploy, and the seed for that table would be this map — but that would only change where
  *new* checklists come from. It would still not reach a case in flight.
  (h) **A `ponytail-review` pass found ~35 lines of cruft, now cut**: a truncation
  guard on an unbounded `text` column, a redundant `processed = false`, two unused
  `ContactSnapshot` getters, two `Ack` factory methods, a redundant `List.copyOf`
  around `toList()`, a speculative `"id"` idempotency-key candidate, and a
  `reduce("", String::concat)` that is `Collectors.joining()`. **Not** cut, by
  decision: the transport record and the intake command record declare the same 21
  fields with a mapper between them — that split is what keeps an unconfirmed payload
  shape out of `service`, and the payload shape is the thing most likely to change.

- **Unit 06 deviations / decisions to confirm.**
  (a) **The spec's `case.created` row is split in two.** The spec maps
  `case.created (pool arrival)` to GM + Brand Manager, but Unit 05a moved the ground
  under that: `case.created` is now a *lead*, and `case.paid` is the pool arrival. Both
  are mapped — `NEW_LEAD` ("somebody is asking") and `NEW_CASE_IN_POOL` ("assign a
  project manager") — to the same recipients the spec names. This is the spec's intent,
  not its letter.
  (b) **The pool arrival is announced once per case.** `apply(...)` publishes one event
  per transition *including* a `mark-paid` that only corrects the amount, so the listener
  checks `existsByCaseIdAndType` before raising `NEW_CASE_IN_POOL`. The guard lives here
  rather than in `markPaid` because "announce once" is a property of the notification,
  not of the transition — and it also holds if anything later re-publishes the event.
  (c) **The centre deliberately does not use `findScoped`.** That applies the caller's
  *tier*, and the GM's tier is ALL — a GM's scoped read would return every member's
  notifications in every brand. "My notifications" is an identity question, not a scope
  one, so every finder names `recipientId` explicitly and no tier can widen it. The
  `SCOPE` constant stays declared because `DomainInvariantsTest` requires one.
  (d) **Three mapped events are not implemented, because their event types do not
  exist yet.** `sla.breached` / `sla.escalation` (Unit 19) and `kpi.threshold_breached`
  (Unit 17/19) are in the spec's table; nothing publishes them, and inventing
  `CaseEvents.Type` entries with no publisher would be scaffolding. `SLA_AT_RISK`,
  `SLA_OVERDUE` and the recipient rule (assigned PM + Brand Manager / Brand Manager +
  GM) are the only parts still to add when those units land.
  (e) **`case.delivered_to_client` is `CASE_DELIVERED`** — the spec's third
  client-facing name; the catalog has had `case.delivered` since Unit 04. All three
  client-facing events are listed explicitly in `CLIENT_FACING` rather than left to fall
  through the unmapped default, so "no staff alert" reads as a decision.
  (f) **Notification bodies no longer name the brand or the contact.** `PoolNotifier`
  built "New International Evaluations lead IE-2026-0001 from Anita Rao"; the listener
  builds "New lead IE-2026-0001." The event payload carries neither name, the case code
  already encodes the brand, and the row is brand-tagged — so this avoids loading a
  `Brand` and a `ContactSnapshot` per alert to restate what the reader already has.
  (g) **`markAllReadFor` carries its own `@Transactional`.** A `@Modifying` bulk update
  throws `TransactionRequiredException` without one. Found by the DB test calling the
  repository directly; the annotation means a future caller who forgets cannot break it.
  (h) **Accessors added per the consumer-appeared rule** (Unit 03 note b):
  `Notification.getCaseId`/`isRead`/`markRead`. `markRead` is one-way — the centre has
  no unread button, so there is no setter to flip it back.
  (i) **Coordinator recipients resolve by role across the brand**, plural, because the
  spec's table names the role rather than a member — and because no `evalos_case` column
  names a coordinator (the open question below). This is the one route whose recipients
  are not derived from the case itself.
  (j) **Not verified live.** Unit 06 has no live-run acceptance criterion and all six of
  its criteria are covered by tests, but the four endpoints have not been exercised over
  real HTTP, and the listeners have not been observed firing end to end from a webhook.

- **Unit 07 deviations / decisions to confirm.**
  (a) **No `components/ui/` primitives were generated.** `ui-context.md` calls for a
  shadcn/Radix set there and `ai-workflow-rules.md` marks it protected, but the
  directory has never existed and nothing in this unit needed it: the brand switcher is
  a native `<select>` and the bell dropdown a native `<details>`. Both ship keyboard
  handling, focus and a11y semantics a hand-rolled version would have to reimplement.
  Generate the set when a table, dialog or tabs is actually required (Unit 08).
  (b) **Lucide is not installed; the one icon is inline SVG.** One bell does not earn a
  dependency. Add Lucide when a screen needs a dozen.
  (c) **`sessionStorage`, and there is no refresh strategy** — the spec asks for
  "in-memory + refresh strategy", but no refresh endpoint exists (the JWT is issued once
  for 8h). Token in memory, mirrored to sessionStorage so a reload does not bounce the
  user to login; the 401 interceptor is the whole expiry story. Revisit if a refresh
  route is ever added. Note this is XSS-exposed in a way an httpOnly cookie would not
  be — accepted for a staff-only internal tool, and worth reconsidering before any
  external surface (Units 14/15) reuses this code.
  (d) ~~The brand filter is state, not yet a parameter~~ — **superseded by Unit 08.**
  `GET /api/cases/board` takes `brandId`, applied after the scoped read so it can only ever
  narrow. Holding it in `filters.tsx` first is what let Unit 08 be purely additive.
  (e) **Six dashboards are one component plus a table**, not six files. The spec says
  "one page per role"; this is one page per role, driven by data.
  **The PRIMARY KPI names are slot labels, not agreed metrics** — Unit 17 owns the real
  ones. Every tile is a skeleton bar, never a number: a plausible fake figure on an
  operations dashboard is worse than a blank one.
  (f) ~~Three `oxlint` warnings accepted~~ — **superseded, and the note was wrong.** It
  dismissed `react/only-export-components` as a dev-ergonomics concern; the browser pass below
  found the consequence (HMR threw `useAuth must be used inside AuthProvider`). The providers
  were split into `lib/authContext.ts` and `features/shell/filtersContext.ts` and lint is
  completely clean. Recorded because the reasoning is the lesson: a lint rule dismissed as
  cosmetic was describing a real defect.
  (g) **`/cases` is shared by four roles** (GM, Brand Manager, PM, Coordinator) rather
  than being four routes, since the spec's per-role labels ("all brands" / "team" /
  "own") describe *scope*, which the server applies — not different screens.
  (h) ~~No frontend test suite~~ — **superseded by Unit 08** (Vitest) **and Unit 09**, which
  added `navigation.test.ts`. The prediction in this note held exactly: the gap was worth
  closing "before the nav table grows past one screen", and by Unit 09 the table had grown a
  parameterized route that needed its own gate. `navFor`/`mayReach` now have assertions
  instead of a browser pass.

- **Unit 07 browser pass — all six acceptance criteria confirmed, two defects found and
  fixed.** Driven through Chrome against the running stack.
  - **Criterion 1** — all six roles land on their own dashboard with exactly the spec's
    nav set: GM `Dashboard|Cases|Experts|Payouts|Brands`, Brand Manager the same minus
    Brands, PM `Dashboard|Cases|Experts|Board`, Coordinator
    `Dashboard|Cases|Doc Checklists|Delivery`, Case Manager `Dashboard|My Cases`, ENM
    `Dashboard|Payouts|Expert Database`. Each shows its own PRIMARY KPI tile.
  - **Criterion 2** — the GM's switcher lists "All brands" plus both brands from
    `/api/brands`, and selecting one flips the dashboard label from "all brands" to
    "one brand". Every other role gets the static "Your brand" label and **no
    `<select>` in the DOM at all**.
  - **Criterion 3** — the bell lists live rows, the empty state reads correctly for a
    recipient with none, and mark-all-read repaints the badge.
  - **Criterion 4** — deep-linking a Case Manager to `/brands` renders the 403 view
    inside the shell with the URL preserved.
  - **Criterion 5** — sign out returns to login and clears the session; navigating to
    `/login` while signed in redirects to the dashboard.
  - **Criterion 6** — `npm run build` clean, and after the two fixes below a hard reload
    plus a bell open produces **zero console output**.
  - **Defect 1, fixed: the dev API logger reported aborted requests as errors.**
    StrictMode double-invokes effects and the cleanup aborts the first request, so the
    console filled with `[api] GET /notifications/unread-count -> network error` for
    calls that were merely superseded. Now skipped via `axios.isCancel`.
  - **Defect 2, fixed: HMR crashed the app, and the lint warning was right.** Note (f)
    dismissed three `react/only-export-components` warnings as a Fast-Refresh
    ergonomics concern. The browser pass caught the consequence: editing any module in
    the auth import graph threw `useAuth must be used inside AuthProvider` from `App`
    via `performReactRefresh`, needing a manual reload. Split into `lib/authContext.ts`
    and `features/shell/filtersContext.ts` (context + hooks) with the providers left as
    the only export of their files. **`npm run lint` is now completely clean** and the
    three warnings in note (f) no longer apply.
  - ~~Cosmetic deviation left as-is: **nav item *order* differs from the spec's per-role
    prose** for three roles (the spec puts Board second for a PM and Expert Database
    before Payouts for an ENM; `NAV_ITEMS` is one globally-ordered table, so shared
    items come first). Every *set* is correct. Fixing it needs a per-role order field —
    worth doing if a role's primary screen being last actually bothers anyone.~~ —
    **closed by the visual pass above, and it did not need the per-role order field.**
    Grouping the one table (Overview / Pipeline / Records / Admin) puts each role's
    pipeline screen directly under the dashboard, which is what the spec's prose was
    describing. The deviation was real, not cosmetic: a PM's board was listed under a
    placeholder.
  - Hygiene note: `LocalPostgresIntegrationTest` writes rows into the **dev** `evalos`
    database and leaves them behind — the bell shows notifications with bodies `"old"`
    and `"fresh"` from `theNotificationCentreFindersRunAgainstRealSql`. Harmless, but
    the dev database now needs a reset before any demo.

- **Unit 08 deviations / decisions to confirm.**
  (a) **The move is not optimistic.** The spec asks for "optimistic move with rollback on
  error". The board posts the transition and re-reads instead. The server decides the target
  stage from its own table, so an optimistic move means *guessing* where the card lands and
  being wrong on every guard (unpaid, checklist incomplete, wrong exception state) — the
  refusal is the common case, not the exception. A refused action shows its reason on the
  card and nothing moves. Revisit if the ~200ms settle is ever felt.
  (b) ~~Member and expert ids are text inputs~~ — **closed in the same unit**: both are
  `<select>`s over the two scoped picker endpoints above. `GET /api/experts` is a **partial
  pre-empt of Unit 11** and named `ExpertPickerController` to say so: no search, no taxonomy
  matching, no quality scores, no sheet upload. Unit 11 supersedes the screen; the endpoint
  can stay as the picker's read.
  (c) **The client quick-action table duplicates Unit 04's transition table.** Unavoidable
  given the spec asks for legal-actions-per-card, and deliberately kept as *one* table in
  `boardApi.ts` with the server as the authority — every action surfaces its 409 inline
  rather than assuming success. If the two drift, the server wins and the user sees why.
  (d) **No drag-and-drop.** The spec puts free drag out of scope (moves are constrained to
  legal transitions), so actions are buttons. A drag that can only ever drop in one place is
  a button with extra steps.
  (e) **The pool is a lane over the stage data, not a separate query.** A case in the pool is
  in `DOC_COLLECTION` like any other and appears in that column too — the lane is the same
  work seen through "what has nobody picked up". GM / Brand Manager / PM only; a Case Manager
  has no pool because nothing in it is theirs yet.
  (f) **`GET /api/cases/board` has no `@PreAuthorize`.** Every staff role has a board and
  none can widen it; a role with nothing assigned gets empty columns, which is a screen, not
  a refusal. Only `dealValue` is role-dependent.
  (g) **Still no `components/ui/` primitives** (carried from Unit 07 note (a)). The dialog is
  a native `<dialog>` and the filters native `<select>`s — Escape, focus trapping, the
  backdrop and keyboard handling all come from the platform. Unit 09's case detail is the
  first screen likely to actually need the generated set (tabs, a real table).
  (h) **`/cases` is still a placeholder.** Spec 08's deliverables are all board; the dense
  sortable case *table* `ui-context.md` describes is not among them, so `/cases` was left
  pointing at the placeholder rather than quietly aliasing it to the board.
  (i) ~~Frontend has no test suite~~ — **closed** (the gap Unit 07 note (h) opened).
  **Vitest, one dev dependency, no jsdom.** The board's decision logic was split into
  `features/board/boardRules.ts` — types, `STAGE_COLUMNS`, `STAGE_ACCESS`, `QUICK_ACTIONS`,
  `actionsFor`, `columnsFor`, `dueBeforeFor` — which imports nothing but a type, so it tests
  in the node environment with no DOM and no server. `boardApi.ts` keeps the four HTTP calls.
  The split was worth doing on its own terms: `session.ts` reads `sessionStorage` at module
  load, so anything importing the old combined module needed a browser to be tested at all.
  `dueBeforeFor` moved out of `BoardView` and now takes an injectable `now`, because date
  window arithmetic is exactly what breaks silently.
  **17 tests, and they were mutation-checked** — flipping one `STAGE_ACCESS` cell from
  `status` to `full` failed two of them, so they are not vacuous. They cover: every
  role×stage cell is defined, every role can work at least one stage (a table typo would
  otherwise leave somebody a board they can only stare at), the Case Manager's two hidden
  columns, the Coordinator's watch-the-middle row, a watching role keeps hold/refund but
  loses the stage actions, **no action is ever offered to a role its route would refuse**
  (the whole point of the client table), one-exception-at-a-time, refund rulings GM-only not
  GM-also, and the date window widening monotonically.
  (j) **A Case Manager loses sight of a case at delivery — confirmed intended, no change.**
  `STAGE_ACCESS.CASE_MANAGER.FINAL_DELIVERY` is `none`, so a case they drafted leaves their
  board once QC passes, even though `assigned_cm` still names them. That is the matrix's `—`
  cell and the intended hand-off: delivery is the Coordinator's stage, and a CM's board is the
  work in front of them rather than everything they have ever touched.
  **It is not lost, only off the board** — the case stays in the CM's scope, so it still appears
  in an exception lane if one is raised, and the Unit 09 detail page opens by direct link. Worth
  keeping in mind if a CM ever needs a "delivered" view; that would be a filter, not this cell.
  (k) **`Head/Vert Mgr`'s KPI column is not modelled as a stage access.** GM and Brand
  Manager get `full` on all five columns instead. A KPI roll-up is a dashboard, not a board
  column — Unit 17 owns it.

- **Unit 07 note (g) is what Unit 08 leaned on.** "Scope, not different screens" is the reason
  `/board` and `/my-cases` are one component. Still current, unlike (d), (f) and (h), which are
  struck through above.

- **Unit 09 deviations / decisions to confirm.**
  (a) **The spec's deliverables 3 and 5 contradict each other on strategy notes** — 3 says
  "visible to PM + CM", 5 says "any PM-only note hidden from Case Manager / Coordinator". Read as:
  3 is the specific rule for this field and 5's wording is loose.
  **Confirmed, no change: the Brand Manager does not see strategy notes.** The rule as built and
  now agreed —
  - read (`SEES_STRATEGY_NOTES`): **GM, Project Manager, Case Manager**
  - write (`MAY_EDIT_STRATEGY_NOTES`): **GM, Project Manager**
  - no read, no write: **Brand Manager, Project Coordinator, Expert Network Manager**

  The reasoning that stands: these are working notes between the two named people on one case,
  not commercial information the brand's management needs. A Brand Manager keeps `deal_value`,
  which is the field their role actually turns on.
  (b) **`CaseDetailService` is a fourth backend file the spec's list does not name.** The spec has
  `GET /api/cases/{id}` returning a "full case DTO" but lists only the timeline service and
  controller; assembling client + expert + checklist is multi-repository work that does not belong
  in a controller.
  (c) **`CaseDetail` nests the summary** rather than flattening 21 fields into it, so the board
  and the detail page share one shape. Costs the client a `detail.summary.x` hop; the alternative
  is two definitions of the same case that can drift.
  (d) **The timeline shows the `note` to every role that can open the case.** It carries hold
  reasons, decline reasons, revision notes and — from `markPaid` — an invoice reference. Only
  `deal_value` is restricted by invariant 3, and an invoice ref is not it. Flagging because it is
  the one adjacent-to-money field a Case Manager can now see; say so and it becomes a projection
  like the others.
  (e) **`AuditAction` has no dedicated value for a notes edit** — it records `UPDATED`, matching
  Unit 05 note (b)'s object-type + action convention. The timeline reads "updated" for both a
  notes edit and a payment correction; the snapshot distinguishes them, the label does not.
  (f) **No expert *link* on the expert card.** Unit 11 owns the expert screen; a card that named
  a destination which does not exist would be worse than one that does not.
  (g) **`DocumentsPanel` links to `/checklists`, not to this case's checklist.** Unit 10 defines
  that route's shape; the link goes to the board it will own rather than inventing a URL now.
  (h) **The page reloads both reads after every action** instead of patching state. A transition
  writes an audit row, so the timeline is stale the moment the case changes — and a timeline that
  lags the case it describes is worse than a slightly slower page.

- **Unit 09 review pass — 1 reported defect and 2 scoping cleanups, all fixed.** A five-lens
  review of `773bf0a` produced six candidates; two were pre-existing, three scored below the
  reporting bar, and one was a real bug. Fixed all three that were worth fixing.
  (a) **Read access to the strategy notes was inferred from write access, and the Case Manager is
  the one role where that is wrong.** `StrategyNotes` computed
  `withheld = pmStrategyNotes === null && !mayEditStrategyNotes`. A CM reads without writing, and
  a null value means *either* "withheld" *or* "not written yet" — so on every case before the PM
  wrote anything, a Case Manager was shown "Visible to the project manager and case manager on
  this case", naming their own role while denying them the field. The DTO now states
  **`maySeeStrategyNotes`** alongside `mayEditStrategyNotes` and the client reads it directly;
  neither flag implies the other, and the value implies neither. Covered by
  `readAccessToStrategyNotesIsStatedSeparatelyFromWriteAccess`, which asserts the CM's two flags
  *disagree* — the case the old backend test missed by always supplying a non-null string.
  (b) **`CaseTimelineService` resolved actor names through the unscoped `findAllById`.**
  `TeamMemberRepository`'s javadoc forbids unscoped reads across brands and CLAUDE.md's first rule
  says a query without brand scoping is a bug. Now a `Specification` narrowing to the **case's**
  brand. **Deliberately not `ScopePredicate`** — that applies the *caller's* tier, and a CM is
  `Tier.SELF`, so a tier-scoped lookup would resolve only their own name and render every
  colleague as "System". Null `brand_id` is included because the GM is the one brand-less member
  and a GM who acted is a real actor. `aReadOnlyCallerStillSeesTheirColleaguesNames` pins exactly
  that.
  (c) **`CaseDetailService` read the contact through the inherited `findById`.** `ScopedRepository`
  calls a scoped read that skips `findScoped` a defect, and `ContactSnapshotRepository` grants no
  carve-out for `findById` the way the checklist finder does for itself. Nothing was reachable —
  the id comes off an already-scoped case — but `contact_id` has no brand in its foreign key, so
  the safety rested on provenance rather than on the query. Now `findScoped`, which is brand-only
  for every role (a Self caller with no assignment column is deliberately not narrowed), so it
  returns the same rows for anyone who could already open the case.
  - **Not fixed, and why**: the `apply()` "one place a case is written" javadoc was already false
    before this unit (`CaseIntakeService` writes too) — pre-existing, and worth its own pass over
    all four call sites rather than a drive-by. The `navigation.ts` "same table" wording is
    imprecise now that `PARAMETERIZED` is a second array; the design is right and the test pins
    it, so this is a comment to reword, not a defect to fix.
  - Verified: `./mvnw verify -Devalos.db.test=true` **160 tests, 0 skipped**; `npm test` 24;
    build and lint clean.

- **Unit 08 review pass — 8 findings, 7 fixed, 1 left as a product decision.** A medium-effort
  review of `026427e`. Two were reachable defects that hid or misreported real work:
  (a) **Every case with no deadline was invisible on the board, permanently.** The board always
  sends a window (`dueBeforeFor` has no "all" range) and the predicate was
  `deadline <= :dueBefore`, so SQL's `NULL <= x` being *unknown* dropped every undated row from
  every column and lane, with no setting that revealed it. Intake leaves the column null
  whenever GHL sends no date — there is no `@NotNull` on it — so this was the normal path.
  The rest of the stack was written as though undated cards arrived (`Comparator.nullsLast`
  "undated last", `Due —` on the card); both were unreachable. Fixed in the predicate, not the
  caller, so `GET /api/cases?dueBefore=` gets it too:
  `deadline IS NULL OR deadline <= :dueBefore`. Undated work is unbounded-risk work; it belongs
  in "what needs attention by then", never hidden by it.
  `aCaseWithNoDeadlineSurvivesTheDeadlineFilter` is DB-gated because only real SQL has NULL
  semantics to get wrong.
  (b) **No refusal reason ever reached the user.** `unwrap` reads the envelope only on a 2xx,
  and every deliberate refusal is a non-2xx — a 409 carries "the case has not been paid" in the
  body while axios sets `message` to "Request failed with status code 409". So the reason was
  fetched, parsed and thrown away, and `boardRules.ts`'s own comment claiming actions "surface
  the reason inline" was false. Fixed in the `api.ts` response interceptor, which lifts
  `error.error.message` onto the Error — one place, so **every** caller in the app gets it, not
  just the board.
  (c) **`<dialog open>` is not modal**, so none of the platform behaviour the comment claimed
  actually happened: Escape did nothing, `onCancel` never fired, `::backdrop` was never
  generated (the `backdrop:` class was inert) and focus was not trapped — cards behind the
  dialog stayed tabbable. Now opened with `showModal()` via a ref.
  (d) **The pool lane's "Assign PM" was inert for a PM**, and the lane was always empty for
  them anyway: `assign-pm` is what stamps `team_id`, so a pool case has no team and a PM's TEAM
  scope never matches it — and the route is gated to GM / Brand Manager regardless. `SEES_POOL`
  is now the two commercial roles. Deviates from the spec, which names the PM; the spec's
  version cannot work.
  (e) **`setMonth` overflow widened the window by up to 3 days** (31 Jan + 1 month = 3 March;
  29 Feb + 1 year = 1 March). The existing test asserted only "later than now", which 3 March
  satisfies, so it passed while the bug was live — now clamped, and pinned by two tests that
  name the month.
  (f) **An inactive member could still be assigned.** `member()` queried brand + role but not
  `active`, so a departed member was staffable by direct POST or by a dialog left open across a
  deactivation — while `assignable` filtered them out, making the picker's "cannot offer
  somebody the transition would refuse" guarantee one-directional. Fixed in the shared lookup,
  so assign-pm and assign-cm are covered too, not just the reviewed one.
  (g) **On-hold unassigned cases were missing from the pool count.** The server puts an
  exception-holding case in its lane *instead of* its stage column, and the lane read only
  `stages` — understating exactly the cases most likely to be both unassigned and held
  (awaiting client documents).
  - **Left as-is, deliberately:** the Case Manager losing sight of a case at `FINAL_DELIVERY`.
    That is the matrix's own `—` cell, already recorded as note (j) and raised with the user;
    changing it to `status` is a one-cell product decision, not a defect fix.
  - Verified: `./mvnw verify -Devalos.db.test=true` **148 tests, 0 skipped**, `npm test` 18,
    build and lint clean.

- **Unit 09 — Case detail page.** The first unit that reads the audit trail back out.
  - `service/CaseTimelineService` + `web/CaseTimelineController` → `GET /api/cases/{id}/timeline`,
    oldest first. **The scoped load runs before a single audit row is fetched**, so an
    out-of-scope case answers 403 rather than becoming a way to read another brand's history by
    guessing an id. No `@PreAuthorize`: every role that can open a case can read what happened
    to it, and opening it is what the scope decides.
  - **The restricted-field rule is satisfied structurally, not by filtering.** The spec asks the
    timeline not to surface fields the caller may not see (e.g. deal value to a CM). Each stored
    snapshot is parsed into the typed `CaseSnapshot` and only three components are projected, so
    a field added to the snapshot later cannot arrive by accident — and `CaseSnapshot` has never
    carried `deal_value`. `DomainInvariantsTest.theAuditSnapshotCarriesNoRoleRestrictedField`
    fails the build if `dealValue`, `invoiceRef` or `pmStrategyNotes` is ever added to it, because
    adding one would leak through a screen nobody would re-check.
  - **An unparseable snapshot still becomes a timeline entry.** Audit rows are permanent while the
    snapshot shape moves (`assignedCoordinator` was added in Unit 08, and a notes edit stores a
    different record entirely). Letting one bad row throw would take out the whole history —
    the opposite of what an append-only trail is for. Action, actor and timestamp live in real
    columns, so they survive regardless.
  - `service/CaseDetailService` joins the three things a single case needs that the row does not
    carry — client name, expert, checklist counts. The case itself comes from
    `CaseLifecycleService.read`, so **scope is decided in one place** and this service cannot
    disagree with the rest of the system about what the caller may see. The checklist's
    "complete" definition is deliberately the same one `markDocsComplete` gates on.
  - `PATCH /api/cases/{id}/strategy-notes` + `CaseLifecycleService.updateStrategyNotes`.
    **Deliberately not routed through `apply(...)`**: it is not a transition, and reusing `apply`
    would restamp `stage_entered_at` and so silently reset the SLA clock — editing a note would
    buy the case a fresh budget — and publish a lifecycle event for something that did not
    happen. It still writes an audit row, because invariant 13 is about every change, not every
    transition. A PATCH rather than a POST for the same reason.
  - **Two role gates on the detail DTO, both projections rather than client-side hiding**:
    `deal_value` keeps its GM/BM/PM rule, and `pm_strategy_notes` is narrower — GM, PM, CM only
    (the PM who writes them and the CM they are for). Writing is PM + GM. The DTO also answers
    `mayEditStrategyNotes` so the client does not re-derive the rule.
  - Frontend `features/case/*` (`CaseDetail`, `DocumentsPanel`, `DraftPanel`, `ExpertCard`,
    `Timeline`, `StageActions`, `StrategyNotes`, `caseApi`). **The stage-action header reuses
    `boardRules.actionsFor` and the board's dialog and POST** — which transitions are legal does
    not depend on which screen you are on, and two tables would be two answers.
  - `/cases/:id` is gated by the *same* nav table via a `PARAMETERIZED` list, even though it has
    no nav entry (you arrive from a board card). A gate declared elsewhere is how a screen ends
    up deep-linkable but unguarded. Board cards now link to it — a real `<Link>`, so middle-click
    and open-in-new-tab work.
  - Two of my own test-authoring bugs, caught by the suite: a `verify` with no call before it,
    and a mock stubbed *inside* a `willReturn` argument — the exact trap `CaseLifecycleServiceTest`
    already documents.
  - Verified: `./mvnw verify -Devalos.db.test=true` **160 tests, 0 skipped** against local
    Postgres 18; `npm test` **24** (new `navigation.test.ts` 6); build and lint clean.

- **`npm audit`: 2 high findings, assessed as not exposed, deliberately not "fixed".**
  `GHSA-qwww-vcr4-c8h2` — react-router **7.12.0 – 8.2.0**, an **RSC-mode** CSRF bypass
  (actions executing before a 400). Installed is react-router 7.18.1 via
  react-router-dom 7.18.1, so the version range matches.
  - **Not reachable here.** EvalOS uses react-router declaratively and only:
    `BrowserRouter`, `Routes`, `Route`, `Navigate`, `Link`, `NavLink`, `Outlet`,
    `useLocation`. No `createBrowserRouter`/`RouterProvider` (data mode), no route
    `loader`/`action`, no `useFetcher`/`useSubmit`, no react-router `<Form>`, no
    framework mode, no `react-router.config.ts`. RSC mode requires an RSC-capable server;
    this is a static Vite bundle talking to Spring Boot over `/api`. The vulnerable code
    path does not exist in the build.
  - **`npm audit fix --force` would make things worse.** It downgrades react-router-dom to
    **7.11.0** — backwards across seven minors of real fixes, and still a breaking change.
    Do not run it.
  - **The actual fix is react-router 8.3.0** (the first version above the range). That is a
    major bump, and in v8 `react-router-dom` is gone — imports move to `react-router`. For
    this app that is mostly an import-specifier change across 7 files, but it is a
    deliberate upgrade with its own browser pass, not a drive-by inside a feature unit.
  - Decision: **accept and revisit when a v8 bump is scheduled.** Re-assess immediately if
    EvalOS ever adopts data mode, framework mode, or RSC — at that point the finding
    becomes live rather than theoretical.

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
