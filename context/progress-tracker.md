# Progress Tracker

Update this file after every meaningful implementation change.

## Current Phase

- Phase 1 — Structure the data (the spine). Units 01–02 built; Unit 03 next.

## Current Goal

- Unit 03 — the remaining domain entities on top of the Unit 02 tenancy spine.

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

## In Progress

- Nothing.

## Next Up

- Unit 03 — remaining domain entities. Open it with a Testcontainers
  `@SpringBootTest` to close the standing DB-verification gap for V1–V3.

## Open Questions

- **Full brand list** — International Evaluations and XpertsPortal confirmed;
  confirm any others before seeding brands / webhook endpoints.
- **Sales/Marketing dashboards** — GHL-native (assumed; EvalOS does not build
  them) vs EvalOS-built. Default is GHL; confirm before Unit 17.
- **StatCommand** — internal module or external BI, and the "six operating
  conditions" the dashboards feed. Undefined; do not build a StatCommand
  integration until specified.
- **GHL webhook/API contract** — (a) per-brand inbound `payment.confirmed`
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
- **Handoff A**: GHL fires the per-brand "payment confirmed" webhook (the webhook
  is the proof); EvalOS creates the case idempotently. No direct payment-processor
  integration.
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

- Reconciled from three source documents (`IE_CRM_Spec_v2`, the Hybrid Platform
  Architecture, and the Feature Inventory FRD) into the EvalOS Technical Design
  Document v1.1, which is the source of truth. Where the original context files
  conflicted with v1.1, v1.1 wins (multi-brand, 8-stage, no object storage, no
  mail, manual payouts, GM/Brand-Manager roles).
- CRM spec automation rules A05–A24 (production/expert/delivery/KPI) are in scope
  across Units 04–19; A01–A04/A06 (lead/sales/marketing) are GHL's job.
