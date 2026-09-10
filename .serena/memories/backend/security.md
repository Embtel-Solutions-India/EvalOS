# backend/ — Security, auth & tenancy scoping

> ## ⚠ The portals are an EXTERNAL FRONTEND (confirmed 2026-09-02) — and CORS is missing
>
> One external frontend deployment hosts **both** the client portal (document upload, draft
> review) and the **expert portal** (download the approved letter, sign it, upload it back). Both
> call this backend. Expert functionality beyond the sign step is not yet specified.
>
> **The auth model already covers it and must not be rebuilt**: `portal_access.audience` is
> `CLIENT` / `EXPERT` with a CHECK (V21), `PortalAudience` maps each to its `ActorType`, and one
> filter chain matches `/api/portal/**`. A second frontend on that chain is a deployment fact.
>
> **There is NO CORS configuration in this codebase.** Correct while every caller was same-origin;
> wrong now. Without it every portal call fails at **preflight** — and passes a curl test, which
> is how it would otherwise be found late. Allow the one portal origin **per environment**, scoped
> to `/api/portal/**`, with **`X-Portal-Token` in the allowed headers**; **never `*`**, because
> the chain is credentialed. Filed in `context/specs/30-s3-document-store.md`, open question (h).

> ## Google Drive → S3 document store (Unit 30, BUILT)
>
> **The Drive prose further down this file is history, not the code.** It is left because it
> records why the decision went the way it did; nothing it describes still exists. `pom.xml` has
> no Google dependency, there is no `config/` package, and `V34__drop_drive_link.sql` dropped the
> column. Spec: `context/specs/30-s3-document-store.md`.
>
> - **`integration/DocumentStore` is the one door**, and it has exactly two capabilities: `put` an
>   object and `presignedUrl` a read. **No delete, no list** — deliberately, so the store cannot be
>   used as a mutable filesystem.
> - **A separate Client Portal application writes client uploads**; EvalOS's credential is
>   **read-only** on `client/{clientId}/`. EvalOS writes only under `case/{caseId}/`.
> - `{clientId}` is **GHL's contact id** — one client across GHL, the Client Portal and
>   EvalOS, no mapping table. **Email stays a fallback key (V27), never the identity.**
> - Reads are **5-minute presigned URLs** (`DocumentStore.READ_WINDOW`), minted after the scope
>   check and **never stored** — a presigned URL in a column is a credential in a column. Every one
>   is minted `Content-Disposition: attachment`, which closes the *path* rather than the file: an
>   HTML or SVG that beat the sniffer has no browser origin to execute in.
> - **Uploads are sniffed, not trusted.** `common/UploadedFileType` reads magic bytes on both
>   surfaces (the client's document and the signed letter). Its ceiling is stated where it lives:
>   `.docx` is a ZIP and `.doc` an OLE2, so this proves the container, not the document —
>   **scanning is the bucket's job**, and that is infra work still owed.
> - **Configuration fails loud but late, and that is the one real change from Drive.**
>   `evalos.s3.bucket` / `evalos.s3.region` (`EVALOS_S3_BUCKET` / `EVALOS_S3_REGION`) have **no
>   defaults**; absent, document routes answer **502** and the boot log names the missing variable.
>   Drive's `evalos.drive.required` made the same omission a **boot failure** — S3 does not, so a
>   misconfigured deploy starts and serves every non-document screen.
> - **The credential is not a property at all.** The AWS SDK's default provider chain reads the
>   environment, the shared profile or the instance role, so no key can reach a committed yaml —
>   `ConfigSecretsTest` fails the build if one does.
> - **Invariant 14 is amended and "No object storage" is deleted** from `architecture.md`. What
>   survives is "EvalOS holds keys, never bytes".
> - **Unit 30 also closed the PDF question by removal**: the redacted profile was the only document
>   EvalOS generated, and Drive's HTML → Doc → PDF export was the only reason a PDF library was ever
>   considered. Nothing generates documents now — they arrive as uploads. **Do not add PDFBox or
>   openhtmltopdf.**

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

## What a portal credential will name (decided 2026-09-04, NOT yet built)

**`portal_access` is becoming party-scoped** (`D1`, built in
`context/specs/35-party-scoped-portal-access.md`): a `CLIENT` row will name a `ghl_contact_id`, an
`EXPERT` row its `expert_id` (already there since `V37`), and `case_id` becomes **nullable**. A
case-scoped row stays legal and keeps exactly today's behaviour — including `V37`'s expert check —
so nothing below regresses. What the widening must not touch: the 256-bit token, the
SHA-256-at-rest, one live token per scope, the absolute expiry, re-mint revoking the previous, and
one identical 401 for unknown/expired/revoked.

Two properties, because a party token is the wider credential: `evalos.portal.link-ttl` stays
`P30D` and `evalos.portal.party-link-ttl` is **`P7D`**. On a party token, the single-case routes
take the case id as a path variable **matched against the party** — which is safe for the reason
Unit 34c's kind filter is safe: the id may come from the request precisely because it is checked
against the credential first. With several cases they answer **409**, never a guess.

**There are no accounts and that was refused, not deferred.** No password store, rotation, lockout
or session — a reset needs a mail channel invariant 14 says does not exist.

## The portal principal — why it is NOT a TenantContext

`security/PortalPrincipal` (`portalAccessId`, `brandId`, `caseId`, `audience`, `expertId`).

**`expertId` is `V37` and it is the second half of the scope on the expert surface** — null for a
client, whose identity is the case's own contact. A case-scoped token said *which case* and not
*which person*, so one expert's token was indistinguishable from another's on the same case: after a
rematch the previous expert's month-long link still admitted them, up to uploading the deliverable
in their own name. `ExpertPortalService.authorized` compares it with the case's expert and refuses a
mismatch, and **fails closed on a null** — a pre-V37 token is refused, not waved through. Do not
weaken that to a fallback: "the column is not set yet" and "this is the wrong expert" are
indistinguishable from the row, and only one of them is safe to allow. The token **is** the
scope: it names one case, so no predicate is built, nothing can fail open, and `ScopePredicate` is
not involved. Manufacturing a synthetic `TenantContext` would put a non-staff caller into the staff
scoping path, where a later widening of a role tier silently widens what a client can read.
`TenantContext.find()` matches on `StaffPrincipal` and so returns **empty** on a portal request —
that is what keeps the surfaces apart, and it means any staff-path code reached from a portal request
throws rather than attributing the act to whoever was last in the context.

The audience is checked in exactly one place, `PortalPrincipal.current(expected)`; Unit 15's expert
routes (built 2026-09-03) inherit it by asking for `EXPERT`, and `ExpertPortalTest` asserts the refusal
in **both** directions — a `CLIENT` token on `/api/portal/expert/**` and an `EXPERT` token on
`/api/portal/client/**`. No authorities are granted, deliberately — a role name in
the filter would be a second statement of the same rule.

`service/PortalCaseService` is the client's own narrow read: a **whitelist**, not a widened
`CaseDetailService`, and it loads by the token's `case_id` with `findById` (the one deliberate
exception to the `findScoped` rule — there is nothing to scope *by*) plus an explicit
token-brand-equals-case-brand check.

`service/ExpertPortalService` (Unit 15) is the expert's, **beside it and never merged with it**. The
two whitelists differ in both directions — the expert sees the goal, the applicant and the supplied
evidence; the client sees the approval state — and one record serving both audiences is one record
somebody widens for one of them. Same `findById`-plus-brand-check shape, same reason.

**The signed-letter upload is the second outside-party write surface, and it is narrower than the
client's**: PDF **by content sniffing** (the first five bytes, in `ExpertPortalController` — a
`.pdf`-named JPEG is refused), and the attestation is required by the API and must equal the wording
the server composed, because the wording *is* the evidence and a sentence the uploader wrote about
themselves proves nothing.

**The name on the attestation comes off the case, never off the request** (review fix, 2026-09-03).
The first version compared the sentence against a caller-supplied `attestedName`, which any
consistent pair satisfied — so the evidence row could name somebody who was never on the case. The
parameter is gone; `expert.full_name` for the case's own expert is the signer, and a case with no
expert cannot be signed at all. **The hash is its own streaming pass** over the part rather than a
digest wrapped around the store's read: the S3 SDK re-reads a mark-supporting stream on a retry, and
a reset resets neither a digest nor a counter, so a retry hashed the file twice over. The transition is checked before the object is written, so a case that
cannot legally be signed leaves no orphan behind.

**One mint route, two audiences**: `POST /api/cases/{id}/portal-link?audience=EXPERT` (GM · Brand
Manager · PM · CM), refused when the case has no expert — since Unit 15 the link is the only way an
expert is reached at all. **The two audiences' links point at different origins** (34e):
`evalos.portal.base-url` + `/portal/client#…` and `evalos.portal.expert-base-url` + `/case#…`,
because the portals are two deployments. A blank expert base falls back to the client's, so a
single-deployment environment needs no new setting.

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
  protected is one case's S3 prefix. Extend that limiter with a second key; do not assume the IP one
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
  to a temp file, and a streamed body so it never lands on the heap. **Unit 30 changes the target
  (S3, not Drive) and not the property** — "EvalOS stores no bytes" stays a test, and it is now the
  only upload EvalOS accepts at all, since client uploads moved to the separate Client Portal.
  See `mem:core`.
- **One audit row per upload**, `actor_type = CLIENT`.
- **Antivirus, and this is no longer open in the way it was.** Drive used to scan on ingest, and
  losing that in Unit 30 left a real gap; **G14 closed the EvalOS half** (2026-09-04).
  `common/UploadedFileType` sniffs magic bytes for one of five kinds on **both** upload surfaces —
  the client's document and the signed letter — so a declared content type is never trusted, and
  every presigned read is minted `Content-Disposition: attachment`, which closes the *path* rather
  than the file. **The stated ceiling:** `.docx` is a ZIP and `.doc` an OLE2, so sniffing proves the
  container, not the document. **Real scanning is the bucket's job and is still owed** — an infra
  control, not a code one.

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
  - **Six roles, and there was briefly a seventh.** Unit 29's `SALES_EXECUTIVE` (SELF, `brand_id`
    NULL) was built and then removed with the sales desk. What it left behind is worth keeping:
    the fail-closed branch it relied on — **a brand-locked role with no brand matches nothing, not
    everything** — is a property of `ScopePredicate`, not of that role, and is still asserted in
    `ScopePredicateTest` on both the assignee and brand-only shapes (now via `CASE_MANAGER`).
    **`team_member_brand_required` is back to V3's `role = 'GM' OR brand_id IS NOT NULL`** and
    `team_member_role_valid` back to the six names (`V30__drop_sales_executive.sql`);
    `LocalPostgresIntegrationTest` pins both against the real database, which is the only place
    that failure can surface. **The GM is once again the only role that may have no brand**, and
    its NULL means "every brand" — the opposite of what the sales executive's meant.
  - **⚠ Six becomes eight at Unit 36 (specced 2026-09-10, not built).** `SALES` and `MARKETING`
    arrive, both on a **new `Tier.PIPELINE`**, keyed on `team_member.ghl_pipeline_id`.
    Three things about that, decided rather than defaulted:
    - **`PIPELINE` is not a reuse of `SELF`.** `SELF` means "rows naming me in an assignee column"
      and is about `evalos_case`; there is no assignee column on what these roles read.
    - **The three business kinds of each role (Attorney / Employer-Firm / Individual) are a
      `segment` column, NOT six enum values** — identical permissions, so encoding them as roles
      would grow every `switch (role)`, the role CHECK and the nav tests to express nothing.
      **Nothing may branch on `segment`**; a structural test enforces that.
    - **`uq_team_member_pipeline` is globally unique and deliberately not brand-scoped** — a GHL
      pipeline belongs to the one location, not to a brand. Partial on `active` so a replacement
      can inherit a leaver's pipeline.
    - The predicate **fails closed** on a null pipeline, on the same rule as brand. `V39`.
    Spec: `context/specs/36-pipeline-scoped-access.md`, programme: `00b-ghl-operational-programme.md`.
- **`SUPPLY` is a field tier, not a row tier, and this is the one that surprises people.** At the
  row level it is identical to `BRAND` — `ScopePredicate` handles both under `default -> {}` and
  adds no predicate — because the ENM's three signing transitions must load the case. What makes
  it supply-side is `Role.seesCaseContent()` (`tier != SUPPLY`), which withholds `clientName` and
  `draftLink` from that tier on **both** case payloads (`CaseController.CaseDetail` and
  `CaseBoardController.BoardCard`). `driveLink` was the third field it withheld and is gone with
  Unit 30. The predicate moved from `CaseController` onto `Role` when the presigned-URL route needed
  it from the service layer — a service reaching into a controller for an authorisation rule is how
  you end up with two copies of it. Added 2026-08-25 after the tier was found to be declared,
  documented as "not case content", and **referenced nowhere** — so `GET /api/cases/board`, which
  has no `@PreAuthorize` by design, returned every client name in the brand to an ENM.
  - Deliberately a **predicate over the tier**, not a `Set<Role>` like `SEES_DEAL_VALUE` /
    `SEES_STRATEGY_NOTES` beside it: those encode product decisions with no other home, while
    "who sees case content" is exactly what `Tier.SUPPLY` already means. A role list would be a
    second copy of that fact.
  - `CaseDetail.maySeeCaseContent` ships to the client because `clientName` is *also* legitimately
    null when no contact is linked; absence alone cannot distinguish withheld from unset, and the
    UI rendered the ambiguous case as "Unnamed contact".
- **Reads** — `service/ScopePredicate` is the one place brand/team/assignee predicates are built, and
  it **fails closed**: a brand-locked role with no brand matches nothing, not everything. Consumed
  through `repository/ScopedRepository` (`mem:backend/persistence`).
- **`Fields.unteamedVisible` — the one place a tier is deliberately widened** (Unit 23). When set,
  a `TEAM` caller matches `team = mine OR team IS NULL` instead of `team = mine`. It is set on
  **`CaseRepository.SCOPE` and nowhere else**: on a case an absent team means *unclaimed* — the
  pool — and the Project Manager who claims it out of their inbox has to be able to read it first.
  Before this a pooled case matched no PM at all, so `/inbox`'s *Unassigned* preset filtered a
  permanently empty set for the only role that could reach the screen.
  - **`TeamMemberQueryService` keeps the strict predicate on purpose.** It is the only other holder
    of a team axis, and an unteamed *person* is not unclaimed work. One flag meaning both is how a
    scope starts asserting something the schema never said.
  - The brand predicate is unconditional either way, so this widens a tier **inside one brand** and
    never across brands. `ScopePredicateTest` pins both the widening and the SELF-tier non-leak.
- **Writes** — `service/OwnershipGuard.assertCanAct(entityBrandId[, assigneeId])` before every
  mutation on a scoped row: a mutation targets one known row, so it is checked, not filtered.
- Role gates go on the controller (`@PreAuthorize("hasAnyRole(...)")`); the brand/team/assignee filter
  goes in the service. No request field may name a brand.

## Endpoints

`POST /api/auth/login` → `{token, role, brandId}` · `GET /api/me` → the principal ·
`GET /api/team-members` (`@PreAuthorize` GM/BRAND_MANAGER, scoped in `TeamMemberQueryService`).

Portal (no role gate, no case id on any route — the token names the case):
`GET /api/portal/client/case` (whitelisted view; stamps `client_portal_read_at` once) ·
`POST /api/portal/client/approve` (Handoff B) · `POST /api/portal/client/request-revisions` ·
`POST /api/portal/client/documents` (multipart, streams to S3) ·
**`GET /api/portal/client/documents`** (checklist + the client's own uploads) ·
**`GET /api/portal/client/documents/{documentId}/url`** (5-minute presign, Unit 34c).

**The last two exist because the upload was uncallable without them.** It takes a
`checklistItemId` and no portal route revealed one — `ClientDraftView`'s javadoc excludes the
checklist by design, and correctly: these are a *second* whitelist for a second screen, not a
widening of the first. Both reads carry **two** filters, and the second is half the
authorization: the document must be on the token's case **and** be `CLIENT_UPLOAD`. Without the
kind filter a client could name their own draft — or Unit 15's signed letter — and read it
outside the flow that decides when they may. No object key crosses the wire on either.
Staff-side: `GET`/`POST /api/cases/{id}/portal-link` — status and mint, GM · Brand Manager · PM ·
CM. **No route returns an existing link's URL**; losing it means minting a new one.

`@WebMvcTest` slices must `@Import` the security stack (`SecurityConfig`, `JwtService`, `ApiErrors`)
and set `evalos.security.jwt.secret`: `JwtFilter` is picked up as a `Filter` bean while `JwtService`
is not, which silently breaks the slice otherwise. A slice that also needs the portal chain imports
`PortalSecurityConfig` and sets `evalos.portal.rate-limit-per-minute` (see `web/ClientPortalTest`,
which imports **both** — asserting one chain alone proves nothing about the direction that leaks).
