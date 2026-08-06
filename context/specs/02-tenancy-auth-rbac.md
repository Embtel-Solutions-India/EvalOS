# Unit 02 — Multi-tenancy + Auth & RBAC/ABAC (security foundation)

**Phase:** 1 — Structure the data (the spine)
**Depends on:** 01
**Unlocks:** 03, 04, 05, and every scoped read/write in the system
**Gating open questions:** staff SSO is out of scope for v1 (password login now).

## Goal

The guard rails, before any feature exists: staff authentication, the six roles
as authorities, the multi-brand tenancy mechanism (row-level `brand_id` scoping
by brand + team + assignee), and a reusable ownership/brand-scope helper. This
unit owns the two **auth-identity entities** — `Brand` and `TeamMember` — because
authentication and scoping depend on them; Unit 03 adds the remaining domain
entities and may extend these two via new migrations.

**Verifiable result:** a staff member logs in and gets a JWT; `GET /api/me`
reflects their role/brand; a brand-scoped demo endpoint returns only the caller's
brand data (all brands for the GM); role- and brand-violating requests are
rejected (401/403).

## In scope

- `Brand` and `TeamMember` entities + Flyway migrations.
- `Role` enum + Spring Security authorities.
- JWT-based stateless staff auth (login → token → filter → principal).
- `TenantContext` populated from the authenticated principal.
- The reusable **scoping mechanism** (brand + team + assignee predicates) and a
  service-layer **BrandScope / OwnershipGuard** helper, demonstrated over
  `TeamMember`.
- `@PreAuthorize` method security enabled.

## Out of scope

- Domain entities (Case, Expert, Payout, etc.) — Unit 03.
- Client and expert portal auth chains — Units 14 and 15 (link-based).
- SSO — later.
- Any business feature endpoints.

## Data / schema

### `brand` (migration `V2__brand.sql`)

| column                 | type                               | notes                       |
| ---------------------- | ---------------------------------- | --------------------------- |
| id                     | uuid PK                            | `gen_random_uuid()`         |
| name                   | text NOT NULL                      |                             |
| slug                   | text UNIQUE                        | url-safe brand key          |
| active                 | boolean NOT NULL default true      |                             |
| webhook_endpoint_token | text UNIQUE NOT NULL               | resolves brand at Handoff A |
| created_at             | timestamptz NOT NULL default now() |                             |

### `team_member` (migration `V3__team_member.sql`)

| column        | type                               | notes                                          |
| ------------- | ---------------------------------- | ---------------------------------------------- |
| id            | uuid PK                            |                                                |
| brand_id      | uuid FK→brand                      | **nullable** — NULL means all brands (GM only) |
| team_id       | uuid                               | nullable; groups a PM's team                   |
| role          | text NOT NULL                      | one of the `Role` enum values                  |
| email         | text UNIQUE NOT NULL               |                                                |
| password_hash | text NOT NULL                      | BCrypt                                         |
| display_name  | text NOT NULL                      |                                                |
| reports_to    | uuid FK→team_member                | nullable (hierarchy)                           |
| active        | boolean NOT NULL default true      |                                                |
| created_at    | timestamptz NOT NULL default now() |                                                |

Index: `(brand_id, role)`, `(email)`.

### `Role` enum

`GM · BRAND_MANAGER · PROJECT_MANAGER · PROJECT_COORDINATOR · CASE_MANAGER ·
EXPERT_NETWORK_MANAGER`. No Head-of-Evals, no interns.

### Scope tiers (ABAC)

| Role                   | Tier   | Reads                                       |
| ---------------------- | ------ | ------------------------------------------- |
| GM                     | All    | every brand                                 |
| BRAND_MANAGER          | Brand  | own brand only                              |
| PROJECT_MANAGER        | Team   | own brand + own team                        |
| PROJECT_COORDINATOR    | Self   | own brand + assigned                        |
| CASE_MANAGER           | Self   | own brand + assigned                        |
| EXPERT_NETWORK_MANAGER | Supply | own brand experts/roster (not case content) |

## Deliverables

1. **Entities + migrations** for `Brand` and `TeamMember` as above.
2. **Security config** (`security` package): `SecurityFilterChain` for the staff
   API, stateless sessions, BCrypt `PasswordEncoder`, a `UserDetailsService`
   backed by `TeamMember`, and a JWT filter (issue + verify; short-lived access
   token; signing key from env). Method security enabled (`@EnableMethodSecurity`).
3. **Auth endpoints** (`web`):
   - `POST /api/auth/login` `{ email, password }` → `{ token, role, brandId }`.
   - `GET /api/me` → the authenticated principal's `{ id, displayName, role,
brandId, teamId }`.
4. **`TenantContext`** — request-scoped holder of `{ memberId, role, brandId,
teamId }`, populated by the JWT filter from the principal. Never trusts a
   body/query field for brand.
5. **Scoping mechanism** (`repository` + `service`): a reusable
   `ScopePredicate`/Specification builder that, given the `TenantContext`,
   injects `brand_id = :brand` (skipped only for GM) plus, where the caller is
   Team/Self tier, `team_id`/`assigned_to` predicates. Plus a service-layer
   `OwnershipGuard.assertCanAct(entity)` helper for mutations.
6. **Scoping demo endpoint** proving the mechanism over `TeamMember`:
   - `GET /api/team-members` — brand-scoped list. GM sees all; Brand Manager sees
     only their brand; guarded so only GM/Brand-Manager may call it.
7. **Seed path** for local dev: a way to insert an initial Brand + GM (Flyway
   seed migration in `local` only, or a documented bootstrap) so login works.

## Endpoints summary

| Method | Path              | Auth          | Scope        |
| ------ | ----------------- | ------------- | ------------ |
| POST   | /api/auth/login   | public        | —            |
| GET    | /api/me           | any staff     | self         |
| GET    | /api/team-members | GM, Brand Mgr | brand-scoped |

## Acceptance criteria

- [x] A seeded GM logs in via `/api/auth/login` and receives a JWT.
      (`SecurityFlowTest.seededGmLogsInAndReceivesAToken`)
- [x] `/api/me` returns the correct role and `brandId` (null for GM).
- [x] A Brand Manager token on `/api/team-members` returns only that brand's
      members; a GM token returns all brands'. (Predicate proven per tier in
      `ScopePredicateTest`; HTTP path in `SecurityFlowTest`.)
- [x] A Case Manager token on `/api/team-members` is `403`.
- [x] No token → `401`. Tampered/expired token → `401`.
- [x] The scope predicate is applied in the repository/service, not the
      controller, and there is no code path to pass `brandId` from the request
      body.
- [x] `./mvnw verify` green (17 tests) and **verified end-to-end against a real
      local Postgres**: V1–V3 + the V900 seed all apply, `ddl-auto=validate`
      passes (the app boots), and the full acceptance flow above was run live —
      GM sees all 5 members, Brand-Manager IE sees only their 3, Case-Manager
      gets 403, no/garbage/flipped-signature tokens all 401, and a `brandId`
      planted in the login body is ignored (the token still carries the seeded
      brand).

## Invariants honored

- Brand isolation (invariant 1): every scoped read filters by `brand_id`; GM is
  the only cross-brand role. Role + brand + ownership enforced before mutations
  (invariant 3). Brand comes from the principal, never the request body.

## Files touched (created)

`.../domain/Brand.java`, `.../domain/TeamMember.java`, `.../domain/Role.java`,
`.../repository/BrandRepository.java`, `.../repository/TeamMemberRepository.java`,
`.../security/{SecurityConfig,JwtFilter,JwtService,EvalOsUserDetailsService,
TenantContext}.java`, `.../service/{AuthService,ScopePredicate,OwnershipGuard,
TeamMemberQueryService}.java`, `.../web/{AuthController,MeController,
TeamMemberController}.java` (+ DTOs),
`db/migration/V2__brand.sql`, `V3__team_member.sql`,
`db/seed-local/V900__seed_local.sql` (local-only seed; moved out of
`db/migration/local` on 2026-08-06 — Flyway recurses, so prod was applying it).
