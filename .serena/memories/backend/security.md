# backend/ — Security, auth & tenancy scoping

Built in Unit 02; the link-based portal chain added in Unit 14. Two chains, stateless and
token-only in both cases; nothing here is session- or cookie-based.

## Chains — two, and neither accepts the other's credential

`security/SecurityConfig` — the **staff** chain, `@Order(2)` (matches everything not matched first),
`@EnableMethodSecurity`, `SessionCreationPolicy.STATELESS`, CSRF disabled (no cookies ⇒ no CSRF
surface), BCrypt `PasswordEncoder`. Public: `/api/auth/login`, `/api/health`, `/actuator/health`,
`/api/webhooks/**`; `anyRequest().authenticated()`. `authenticationEntryPoint` /
`accessDeniedHandler` write the envelope via `common/ApiErrors`.

`security/PortalSecurityConfig` — the **portal** chain, `@Order(1)`,
`securityMatcher("/api/portal/**")`, `authenticated()` with no role rule. Its own file rather than a
second bean beside the staff chain for two reasons: the surfaces are separate, and a dozen
`@WebMvcTest` slices import `SecurityConfig` and must not need a portal service to start.

- `security/PortalTokenFilter` reads `X-Portal-Token` (a header, not a query parameter — a query
  parameter lands in access logs and `Referer` headers) and is **constructed in the config, not a
  `@Component`**: a `Filter` bean is auto-registered globally by Boot, which would let a portal token
  be read on a staff route. That is the load-bearing detail — do not annotate it.
- It also holds the chain's **rate limit** (`evalos.portal.rate-limit-per-minute`, default 60): a
  per-caller fixed window in memory, cleared on the roll, refused before the token is looked at.
  Per-instance; move it to Redis or a gateway if EvalOS is ever run multi-instance.
- No JWT filter is in that chain, so a staff bearer on `/api/portal/**` is `401 PORTAL_LINK_INVALID`
  — the same answer as unknown, expired, revoked and absent, so nothing is learnable from a refusal.
- `service/PortalAccessService` mints / revokes / resolves. Token = 256 bits `SecureRandom`,
  base64url, returned **once**, stored as a SHA-256 hash; `PortalAccess.matches` does the comparison
  with `MessageDigest.isEqual`. Re-minting revokes the previous token in the same transaction (that
  is the "one live token per case per audience" rule — it cannot be an index, `now()` is not
  immutable). Resolving stamps `last_seen_at`.

## The portal principal — why it is NOT a TenantContext

`security/PortalPrincipal` (`portalAccessId`, `brandId`, `caseId`, `audience`). The token **is** the
scope: it names one case, so no predicate is built, nothing can fail open, and `ScopePredicate` is
not involved. Manufacturing a synthetic `TenantContext` would put a non-staff caller into the staff
scoping path, where a later widening of a role tier silently widens what a client can read.
`TenantContext.find()` matches on `StaffPrincipal` and so returns **empty** on a portal request —
that is what keeps the surfaces apart, and it means any staff-path code reached from a portal request
throws rather than attributing the act to whoever was last in the context.

The audience is checked in exactly one place, `PortalPrincipal.current(expected)`; Unit 15's expert
routes inherit it by asking for `EXPERT`. No authorities are granted, deliberately — a role name in
the filter would be a second statement of the same rule.

`service/PortalCaseService` is the client's own narrow read: a **whitelist**, not a widened
`CaseDetailService`, and it loads by the token's `case_id` with `findById` (the one deliberate
exception to the `findScoped` rule — there is nothing to scope *by*) plus an explicit
token-brand-equals-case-brand check.

## Identity

- `JwtService` — HS256, secret from `evalos.security.jwt.secret` (constructor refuses <32 bytes, so a
  weak key fails startup), TTL from `evalos.security.jwt.ttl`. Claims carry member/role/brand/team so
  **scoping needs no DB hit**; the trade-off is that a role or brand change only takes effect on the
  next login (bounded by the 8h TTL). Revisit only if instant revocation is required.
- `JwtFilter` (before `UsernamePasswordAuthenticationFilter`) — a missing, expired or tampered token
  leaves the context anonymous and lets the chain decide; never logs the token itself.
- `StaffPrincipal` — record implementing `UserDetails`; built from the DB at login by
  `EvalOsUserDetailsService` (active members only, error message deliberately vague so an unknown
  address is indistinguishable from a wrong password), rebuilt from JWT claims on later requests.
- `service/AuthService` is the only place a token is minted; credential checking stays in Spring's
  `AuthenticationManager`.

## Tenancy (the invariant that overrides convenience)

- `security/TenantContext` (`find()` / `current()`) reads the `SecurityContext`. Brand comes from the
  authenticated principal — **never** from a body field, query param, or header.
- `domain/Role` carries its own ABAC `Tier`: `GM`=ALL, `BRAND_MANAGER`=BRAND, `PROJECT_MANAGER`=TEAM,
  `PROJECT_COORDINATOR`/`CASE_MANAGER`=SELF, `EXPERT_NETWORK_MANAGER`=SUPPLY. Nothing re-derives
  scope from the role name. GM is the only cross-brand reader.
- **Reads** — `service/ScopePredicate` is the one place brand/team/assignee predicates are built, and
  it **fails closed**: a brand-locked role with no brand matches nothing, not everything. Consumed
  through `repository/ScopedRepository` (`mem:backend/persistence`).
- **Writes** — `service/OwnershipGuard.assertCanAct(entityBrandId[, assigneeId])` before every
  mutation on a scoped row: a mutation targets one known row, so it is checked, not filtered.
- Role gates go on the controller (`@PreAuthorize("hasAnyRole(...)")`); the brand/team/assignee filter
  goes in the service. No request field may name a brand.

## Endpoints

`POST /api/auth/login` → `{token, role, brandId}` · `GET /api/me` → the principal ·
`GET /api/team-members` (`@PreAuthorize` GM/BRAND_MANAGER, scoped in `TeamMemberQueryService`).

Portal (no role gate, no case id on any route — the token names the case):
`GET /api/portal/client/case` (whitelisted view; stamps `client_portal_read_at` once) ·
`POST /api/portal/client/approve` (Handoff B) · `POST /api/portal/client/request-revisions`.
Staff-side: `GET`/`POST /api/cases/{id}/portal-link` — status and mint, GM · Brand Manager · PM ·
CM. **No route returns an existing link's URL**; losing it means minting a new one.

`@WebMvcTest` slices must `@Import` the security stack (`SecurityConfig`, `JwtService`, `ApiErrors`)
and set `evalos.security.jwt.secret`: `JwtFilter` is picked up as a `Filter` bean while `JwtService`
is not, which silently breaks the slice otherwise. A slice that also needs the portal chain imports
`PortalSecurityConfig` and sets `evalos.portal.rate-limit-per-minute` (see `web/ClientPortalTest`,
which imports **both** — asserting one chain alone proves nothing about the direction that leaks).
