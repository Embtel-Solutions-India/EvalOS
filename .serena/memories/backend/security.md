# backend/ — Security, auth & tenancy scoping

Built in Unit 02. Stateless, bearer-token-only staff chain; nothing here is session- or cookie-based.

## Chain

`security/SecurityConfig` — one `SecurityFilterChain`, `@EnableMethodSecurity`, `SessionCreationPolicy.STATELESS`,
CSRF disabled (no cookies ⇒ no CSRF surface), BCrypt `PasswordEncoder`. Public: `/api/auth/login`,
`/api/health`, `/actuator/health`; `anyRequest().authenticated()`. Client and expert portals get
their **own** chains (Units 14/15, link-based) — do not widen this one to cover them.
`authenticationEntryPoint` / `accessDeniedHandler` write the envelope via `common/ApiErrors`.

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

`@WebMvcTest` slices must `@Import` the security stack (`SecurityConfig`, `JwtService`, `ApiErrors`)
and set `evalos.security.jwt.secret`: `JwtFilter` is picked up as a `Filter` bean while `JwtService`
is not, which silently breaks the slice otherwise.
