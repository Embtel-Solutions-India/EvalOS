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
  Two ceilings, both named in the filter. It is **per-instance** (move to Redis or a gateway if EvalOS
  is ever run multi-instance), and it keys on `getRemoteAddr()`, so **a proxied deployment must set
  `server.forward-headers-strategy=framework`** (`FORWARD_HEADERS_STRATEGY`) or every client resolves
  to the proxy and shares one budget. That property defaults to `none` deliberately: with no proxy in
  front, trusting `X-Forwarded-For` would let a caller spoof a fresh address per request and bypass the
  limit outright.
- No JWT filter is in that chain, so a staff bearer on `/api/portal/**` is `401 PORTAL_LINK_INVALID`
  — the same answer as unknown, expired, revoked and absent, so nothing is learnable from a refusal.
- `service/PortalAccessService` mints / revokes / resolves. Token = 256 bits `SecureRandom`,
  base64url, returned **once**, stored as a SHA-256 hash; `PortalAccess.matches` does the comparison
  with `MessageDigest.isEqual`. Re-minting retires **every** unrevoked row for that case and audience
  in the same transaction, and "one live token per case per audience" is enforced by `V23`'s partial
  unique index rather than by that loop — the loop is what keeps the winner legal. Do not narrow it
  back to only the *live* rows: an unrevoked expired row would sit in the index and block the next
  mint. See `mem:backend/persistence`. Resolving stamps `last_seen_at`.

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

## The upload trust boundary (Unit 21)

The portal's document upload is **the first place an outside party sends EvalOS bytes**, and it is the
highest-risk surface in the system. The rules are requirements, not preferences:

- **On the portal chain**, `audience = CLIENT` via `PortalPrincipal.current(CLIENT)`. Brand and case
  come from the token's row; **the request names neither**.
- **The item must belong to the token's case.** A valid token for case A writing to case B's item id
  is the obvious attack — check the parent and answer **404**, because whether another case's item
  exists is not this caller's information.
- **Allowlist by sniffed content**, never by `Content-Type` or extension: both are attacker-controlled.
  PDF / JPEG / PNG.
- **Size cap, and reject empty files.** A zero-byte upload that flips an item to `UPLOADED` silently
  satisfies the docs-complete gate — worse than no upload at all.
- **The client's filename is untrusted data.** EvalOS generates the stored name; no separators, no
  traversal, never echoed into HTML.
- **Per-token rate limit, which is new work.** `PortalTokenFilter` already rate-limits, but on
  **`getRemoteAddr()`** — and behind a proxy without `forward-headers-strategy=framework` every caller
  shares one budget. An upload limit must key on the `portal_access` id, because what is being
  protected is one case's Drive folder. Extend that limiter with a second key; do not assume the IP one
  covers it, and do not add a parallel limiter.
- **The token is the `X-Portal-Token` header — never a path segment or query parameter.** The filter
  refuses a query parameter because it lands in access logs, `Referer` headers and browser history, and
  a path segment is worse on all three. It is also why `PortalSecurityConfig` can disable CSRF: the
  credential is a header a browser does not attach on its own. An upload route that took the token in
  its path would quietly undo both.
- **Refusals are 401, not 403.** The portal entry point answers `401 PORTAL_LINK_INVALID` identically
  for missing, unknown, expired and revoked tokens, so a caller learns nothing. **403 is only** the
  audience mismatch from `PortalPrincipal.current`.
- **Nothing is stored**: `multipart.file-size-threshold` = `max-file-size` so the container cannot spool
  to a temp file, and `InputStreamContent` into Drive so it never lands on the heap. See `mem:core`.
- **One audit row per upload**, `actor_type = CLIENT`.
- Open, and deliberately not hand-waved: **antivirus.** Drive scans on ingest; that is not the same as
  EvalOS having a posture on files accepted from a public link.

**The same boundary carries the signed letter (Unit 15).** There is **no e-signature provider**: the
expert downloads the letter and uploads the signed PDF back through their own `EXPERT`-audience token,
reusing every rule above. Two differences: the allowlist narrows to **PDF only**, and the
**attestation is required by the API**, not just by the UI — refuse `400` without it, because that
tick is the evidence.

Since nothing issues a signing certificate, provenance is three things and they must all be written:
a hash of the letter as **sent**, a hash of the file as **received**, and an audit row with
`actor_type = 'EXPERT'`. Note the difference from the staff stand-in endpoints, which still exist: a
portal upload audits as the **expert** acting; `POST /api/cases/{id}/expert/signed` audits as **staff**
recording a claim about the expert. Both are legitimate, only one is first-hand — do not let the
stand-in become the normal path.

`/api/jobs/*` (Unit 19) is **GM-only**, at the route and in the service. A manual sweep run must never
become a way for a non-GM to trigger client-facing messages.

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
