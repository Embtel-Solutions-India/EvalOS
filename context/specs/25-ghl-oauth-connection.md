# Unit 25 — GHL OAuth connection (per brand)

> **Status: specced, DEFERRED — not scheduled.** Spec first this time (Unit 24 was
> written the wrong way round and said so), and then parked by decision: only
> International Evaluations is being set up for now, and IE works on the Unit 24
> Private Integration Token without any of this.
>
> **What deferring costs, so the trade is on the record:** a second brand cannot be
> configured at all until this lands. `GHL_LOCATION_ID` is a single global value, so
> XpertsPortal has nowhere to go — it is not "add another variable", it is this unit.
> Pick it up when XpertsPortal needs its funnel.
>
> **What deferring saves:** nothing else, now — the encryption sign-off it used to also
> defer has been given (see below), so this unit is *unscheduled*, not *blocked*. Picking
> it up is a scheduling decision, not another approval.
>
> **The encryption sign-off is DONE (2026-08-26) — this unit is no longer blocked, only
> unscheduled.** Option 1 was approved: one shared `common/EncryptedStringConverter`, with
> `PaymentDetailConverter` left as a thin subclass. `code-standards.md` and
> `ai-workflow-rules.md` now carry the decision and the narrow protected-file exception
> that goes with it. **The extraction is deliberately not written yet** — nothing needs a
> generic converter until there is a second column to put in it. (It was about AES-GCM
> encryption at rest, not currency — the `AttributeConverter` name was misread that way
> once.) Nothing else about this unit is open.

## Goal

Replace the hand-managed Private Integration Token with an **OAuth connection the
GM makes from inside EvalOS**, held per brand, refreshed automatically, and
revocable.

## Why, in one paragraph

Unit 24 authenticates to GHL with a `GHL_API_TOKEN` and a single global
`GHL_LOCATION_ID` — one token, one sub-account, both pasted into an environment by
hand. That is why the marketing funnel is GM-only: the credential belongs to nobody in
particular, so the figure behind it cannot be attributed to a brand. **A second brand
has nowhere to go** — `GHL_LOCATION_ID` is one value, so XpertsPortal cannot be
configured at all without this unit. `architecture.md` already
records where this goes ("if the brands are ever split across two GHL locations,
`location-id` becomes a column on `brand`"). OAuth is what makes that possible:
a connection is *per location by construction*, so the credential stops being
global configuration and becomes a brand-scoped row.

## In scope

- A brand-scoped `ghl_connection` row holding the OAuth grant for one GHL location.
- The authorize → callback → token-exchange flow, initiated by the GM from EvalOS.
- Automatic refresh, serialized so concurrent readers cannot rotate each other out.
- `GhlPipelineClient` resolving a token per brand instead of a static header.
- Connection status + connect/disconnect on a GM screen.
- **Deleting `GHL_API_TOKEN` and `GHL_LOCATION_ID`.** See *No dual path*.

## Out of scope

- **Re-scoping the marketing funnel.** This unit makes the funnel brand-scopable;
  actually adding `brandId`, admitting the Brand Manager, and retiring invariant 1's
  stated exception is **Unit 25a**, because it is a different boundary (a screen's
  role list) from this one (a credential's lifecycle). See *What this unlocks*.
- Any scope beyond `opportunities.readonly`. Least privilege, added by the unit that
  needs it — the same rule `evalos.drive.scope` follows.
- Marketplace listing / public distribution. A **private** app is what an internal
  tool needs, and it skips GHL's review.
- **Agency-level (company) tokens — rejected, not merely deferred.** See *Two brands,
  one app* for why: an agency grant can reach every sub-account in the agency, which is
  the same cross-brand hole `application.yml` already refuses for Google Drive.
- Writing anything to GHL. Unit 24's boundary is unchanged: read-only by grant and
  by code, and invariant 2 still forbids running marketing.

## No dual path

**OAuth replaces the PIT outright; it does not sit beside it.** Two authentication
paths into one upstream is two things to keep correct and one to forget, and a
fallback would be exercised only when the primary is broken — the worst time to
first run a code path.

This is free right now precisely because **Unit 24 has never run live**:
`GHL_API_TOKEN` defaults to empty and the screen answers 502, so there is no
working configuration to migrate and no window where both must work. That window
closes the day somebody sets the variable. **Do this before the PIT is used in
anger, or the cutover stops being free.**

## Two brands, one app — and the fork in what "one" means

The question this unit gets asked first: *we have two brands, how does a single OAuth
setup serve both?* There are two answers and they are not equivalent.

### Option A — one agency install (rejected)

Install the app once at **Company/agency** level, hold one agency grant, and mint a
per-location token from it on demand (`POST /oauth/locationToken` with `companyId` +
`locationId`). One install, one row, scales to N brands with no extra admin. Genuinely
"one thing".

**Rejected, and on this codebase's own precedent.** An agency token can mint a token
for **every sub-account in the agency**, including ones EvalOS has no business reading.
That is a cross-brand hole living *outside the database*, where no `brand_id` predicate
can reach it — which is word for word the argument `application.yml` already makes about
Google Drive:

> The grant behind it must be scoped PER BRAND FOLDER TREE. A service account with
> blanket access to both brands' Drives is a cross-brand hole that lives outside the
> database, where no `brand_id` predicate can reach it.

Taking Option A would repeat a mistake this project has already reasoned its way out of
once. It is also the harder one to walk back: revoking a per-location grant is one
disconnect, while an over-broad agency grant has to be re-scoped in GHL.

*(Also unverifiable from here: the GHL MCP's operation registry carries no auth
endpoints, so `POST /oauth/locationToken` is from documentation rather than something
this repo has confirmed.)*

### Option B — one app, one install per brand (chosen)

**"Single thing" is satisfied at the level that matters — the app.** One
`client_id`, one `client_secret`, one redirect URI, one code path. What multiplies is
the *grant*, and that is the point: two rows, each holding a credential that can reach
exactly one brand's sub-account.

| Single, shared by both brands | Per brand |
| --- | --- |
| The GHL Marketplace app | The install / grant |
| `client_id`, `client_secret` | `refresh_token` |
| The redirect URI | `location_id` |
| Every line of EvalOS code | The `ghl_connection` row |

Adding XpertsPortal later is then **an install, not a deployment**: the GM opens
`/brands`, clicks Connect on that brand, and picks its sub-account. No new config, no
new secret, no restart.

**Distribution type:** *Private*, installed on each sub-account. A private app can be
installed across locations in your own agency without GHL review. If the two brands ever
sit under different agencies, this still works — which is the other thing Option A
cannot claim.

### Why the redirect URI forces the `state` design

GHL matches `redirect_uri` **exactly**, so there is exactly one callback URL for both
brands. The brand therefore **cannot** travel in the path or the query string — there is
nowhere to put it, and anything a caller could put there would be attacker-controlled
anyway. It has to come from the server-side `state` row. That is not defensive
programming bolted on; it is the only place the brand can legally come from.

## Testing it before production, with ngrok

The callback is a browser redirect from GHL, so it needs a public HTTPS URL. ngrok is
already installed here (3.39.9).

**Use a reserved static domain, not an ephemeral tunnel.** A random URL per restart means
editing the app's redirect URI in GHL every single time, and each edit is a chance to
create the exact-match mismatch that makes the flow fail with an unhelpful error:

```
ngrok http 8080 --url=https://<your-static>.ngrok-free.app
```

then

```
GHL_REDIRECT_URI=https://<your-static>.ngrok-free.app/api/oauth/ghl/callback
```

registered identically in the GHL app. Two snags worth knowing before they cost an hour:

- **`server.forward-headers-strategy` is `none` by default**, and `application.yml`
  already explains why: without it, `getRemoteAddr()` returns the tunnel's address and
  every portal caller shares one rate-limit budget. Irrelevant to the OAuth flow itself,
  but set `FORWARD_HEADERS_STRATEGY=framework` if testing portal behaviour through the
  same tunnel — and **only** behind a trusted proxy, never in an environment exposed
  directly.
- ngrok's free tier can serve a browser interstitial on first visit. The OAuth redirect
  is a browser navigation, so it can land on that page instead of the callback. A
  reserved domain on a paid plan avoids it; otherwise click through once per session.

In production the redirect URI is simply the real origin — no tunnel, and the same one
value.

## The token model

The shape follows `PortalAccessService`, which is this codebase's precedent for
treating a credential as a credential — with one deliberate difference, stated
because it is the interesting part:

> **A portal token is stored hashed. An OAuth token cannot be.** A portal token is
> only ever *compared* against what a caller presents, so a hash is sufficient and a
> database read yields nothing usable. A refresh token is *replayed to GHL*, so we
> must be able to recover the value. Hashing is not an option here, and that is the
> whole reason this unit needs encryption at rest rather than the cheaper thing.

### One row per brand

`ghl_connection`, brand-scoped, extending `ScopedEntity`:

| Column | Notes |
| --- | --- |
| `brand_id` | FK, **unique** — one live GHL connection per brand |
| `location_id` | The sub-account this grant is for. Comes from the token response, **never from a request** |
| `refresh_token` | **Encrypted.** The only long-lived secret here |
| `scopes` | What GHL actually granted, recorded so a missing scope is diagnosable |
| `connected_at`, `connected_by` | Who bound this brand to that location, for the audit trail |
| `revoked_at` | Set on disconnect or on a refresh GHL refuses. Nullable |
| `state_hash`, `state_expires_at` | The pending CSRF nonce. Nullable — see below |

**The access token is deliberately not persisted.** It lives in memory on the
instance that fetched it. It expires in roughly a day, so persisting it buys one
avoided refresh per restart and costs a second encrypted column carrying a live
bearer credential. On boot the first caller refreshes once. *(Confirm the actual
`expires_in` against GHL's docs — this design only assumes "short enough that a
restart-time refresh is cheap", which holds for anything under a week.)*

**`state` lives on the same row rather than in its own table.** Initiating a
reconnect stamps a fresh nonce without touching the live tokens, so an abandoned
attempt leaves a working connection working. A second table would have to be
cleaned up and would let a stale attempt outlive the connection it was for.

### The `state` parameter is not optional

Without it, anyone who can reach the callback can complete a flow and bind **their**
GHL location to one of our brands — then every figure on the funnel is a stranger's
data, presented as the brand's. So:

1. The GM starts the flow in EvalOS. We generate a 256-bit nonce from
   `SecureRandom`, store **only its SHA-256**, stamp a short expiry, and put the
   nonce in the authorize URL's `state`.
2. The callback recomputes the hash and compares with `MessageDigest.isEqual` — the
   constant-time comparison `PortalAccessService` and `WebhookVerifier` both use,
   for the same reason.
3. **Single use.** The nonce is cleared in the same transaction that stores the
   grant. A replayed callback finds nothing and is refused.
4. Unknown, expired and already-used are **one indistinguishable refusal**, as with
   portal tokens.

The `brand_id` being connected comes from **the state row**, never from a query
parameter on the callback. A `brandId` in the redirect would be an open invitation
to attach a location to somebody else's brand.

## Refresh, and the one thing that will break if it is done casually

**GHL rotates the refresh token: every refresh response contains a new one, and the
old one stops working.** That single fact dictates the design.

Two app instances exist during every rolling deploy (`code-standards.md` says so
about job sweeps). If both notice an expired access token and both refresh:
instance A succeeds and writes `RT2`; instance B replays `RT1`, which GHL has
already retired, gets a 4xx — and a naive implementation marks the connection dead.
**A working connection is destroyed by two readers doing the obvious thing.**

The fix is to serialize on the row:

- Refresh inside a transaction that takes `SELECT … FOR UPDATE` on the
  `ghl_connection` row (`@Lock(PESSIMISTIC_WRITE)`).
- **Re-read the row after acquiring the lock.** The loser of the race must see the
  token the winner just wrote and use it, not the one it read before waiting.
- Persist the rotated refresh token in that same transaction, before the access
  token is handed to any caller. A commit ordering that returns the access token
  first can lose the rotation on a crash and orphan the connection.

A row lock rather than the advisory lock the sweeps use, because here the contended
resource **is** a row — an advisory lock would be a second naming scheme for
something Postgres already identifies.

### Revocation is detected, not subscribed to

A refresh that GHL refuses with 401/400 means the grant is gone — uninstalled,
revoked, or rotated out. Set `revoked_at`, and the screen says *reconnect GHL*.

**GHL's `INSTALL`/`UNINSTALL` webhooks are deliberately not consumed.** They arrive
at an app-level endpoint, whereas `InboundWebhookController` is *one endpoint per
brand, gated by that brand's endpoint token plus an HMAC over the body* — an
app-level hook fits neither half of that gate, so consuming it means a second
inbound model for one bit of information a failed refresh already tells us. Revisit
only if the delay between an uninstall and the next read becomes a real complaint.

## The encryption decision — **SIGNED OFF 2026-08-26: option 1**

`code-standards.md` and `mem:backend/persistence` both stated that
`expert.payment_detail` **is the only encrypted field**. This unit needs a second, and
**option 1 below is approved**: extract the AES-GCM into one
`common/EncryptedStringConverter` and leave `PaymentDetailConverter` as a thin subclass.

**The protected-file exception is narrow and named**: that one extraction, with the
expert path's behaviour unchanged — same key, same AES-256-GCM, same fresh 12-byte IV per
write, same authenticated failure on a tampered column, and `PaymentDetailConverter`
keeps its type and its call sites. Anything else in that file still needs its own
sign-off. **Write it when this unit is built, not before** — a shared abstraction with a
single implementation is the thing this codebase deletes.

The four options as they were ranked, kept because the rejections are the useful part:

1. **Extract the crypto from `PaymentDetailConverter` into one
   `EncryptedStringConverter`, and leave `PaymentDetailConverter` as a thin subclass.**
   One AES-GCM implementation, one key, no behaviour change on the expert path.
   *Recommended.* **But it edits a protected file** — `ai-workflow-rules.md` lists
   "the field-level encryption `AttributeConverter` in `common`" as protected, so
   this is exactly the sign-off being asked for.
2. A separate `RefreshTokenConverter` duplicating the ~60 lines of AES-GCM. No
   protected file touched, at the cost of **two crypto implementations to keep
   correct** — and the second one is the one nobody re-reads. Worse than 1 for the
   reason this codebase deletes duplicates.
3. A separate key for OAuth tokens (`evalos.security.oauth-key`). Better blast-radius
   isolation, one more secret every environment must not forget. Defensible; more
   operational surface than the threat warrants for an internal tool.
4. Don't encrypt; rely on database access control. **Rejected.** A refresh token is a
   live credential to a third-party system holding customer data, and `payment_detail`
   set the precedent that such things are encrypted at rest here.

Whichever is chosen, the properties are unchanged from `PaymentDetailConverter`:
AES-256-GCM, fresh 12-byte IV per write, authenticated so a tampered column fails
rather than decrypting to something plausible, and **never** in a DTO, a log line, or
a webhook payload.

## Security boundary — one permitted path, no new filter chain

The callback is a browser redirect carrying a `code`. It cannot present a staff JWT,
so it must be permitted:

```java
.requestMatchers("/api/oauth/ghl/callback").permitAll()
```

added to `SecurityConfig`'s existing list, beside `/api/webhooks/**`.

**Not under `/api/webhooks/`, and not a third filter chain.** Not a webhook, because
nothing here is HMAC-verified over a body — filing it there would invite the next
reader to assume `WebhookVerifier` guards it, when the guard is the `state` nonce. And
not a chain, because a chain exists to carry *a different kind of credential*
(`PortalSecurityConfig` has one because a portal token is not a staff token); this
endpoint carries no EvalOS credential at all, exactly like the webhook endpoints that
already live on the staff chain as permitted paths.

**The initiating endpoint is a normal GM-gated route** — `POST /api/oauth/ghl/authorize`
returns the URL to visit. Only the callback is public.

## Backend

| Piece | File | What it does |
| --- | --- | --- |
| Entity | `domain/GhlConnection` | The row above, extends `ScopedEntity` |
| Repo | `repository/GhlConnectionRepository` | `ScopedRepository`; the `FOR UPDATE` finder |
| Crypto | `common/EncryptedStringConverter` | Per the decision above |
| Service | `service/GhlConnectionService` | Nonce mint, code exchange, refresh-under-lock, disconnect |
| Client | `integration/GhlOAuthClient` | The two `/oauth/token` calls, form-urlencoded |
| Routes | `web/GhlOAuthController` | `POST /authorize` (GM), `GET /callback` (public), `GET /status` (GM), `POST /disconnect` (GM) |
| Migration | `V25__ghl_connection.sql` | Table + unique index on `brand_id` |
| Changed | `integration/GhlPipelineClient` | Token per brand, not a default header |
| Changed | `security/SecurityConfig` | One permitted matcher |
| Changed | `resources/application.yml` | `evalos.ghl.client-id` / `client-secret` / `redirect-uri`; **delete `token` and `location-id`** |

### The flow, concretely

1. `POST /api/oauth/ghl/authorize` (GM) → mint nonce, return
   `https://marketplace.gohighlevel.com/oauth/chooselocation?response_type=code&client_id=…&redirect_uri=…&scope=opportunities.readonly&state=<nonce>`
2. GM approves in GHL → `GET /api/oauth/ghl/callback?code=…&state=…`
3. Verify + consume the nonce; exchange the code at
   `POST https://services.leadconnectorhq.com/oauth/token`, **form-urlencoded**, with
   `client_id`, `client_secret`, `grant_type=authorization_code`, `code`,
   `user_type=Location`, `redirect_uri`
4. Store `refresh_token` (encrypted), `location_id` and `scopes` from the response;
   audit the connection against the brand
5. Reads call `GhlConnectionService.accessTokenFor(brandId)` — in-memory if fresh,
   otherwise refresh under the row lock

`user_type`, the exact `expires_in`, and the refresh-token lifetime **must be confirmed
against GHL's current docs before implementing.** The MCP operation registry does not
expose the auth endpoints, so they could not be verified while writing this — treat
the four field names in step 3 as high-confidence and the two lifetimes as unverified.

## Frontend deliverables

One panel on the GM's existing `/brands` screen — not a new nav entry, because
"connect this brand to GHL" is brand administration and that screen already exists:

- Per brand: connected / not connected, the `location_id`, granted scopes, when and
  by whom, and a **Connect** or **Disconnect** button.
- Connect opens the authorize URL; the callback lands back on `/brands`.
- **`MarketingPipelinePage` gets one new state** (all three funnels, since Units 26 and 27
  made it one component): connection revoked or absent → "Connect
  GHL to see this" pointing at `/brands`, rather than the raw 502 it shows today.
  A missing connection is a setup step, not an upstream fault.

## Acceptance criteria

- [ ] A GM connects a brand end to end against a real GHL sub-account, and the
      marketing funnel loads from the resulting grant. **This also closes Unit 24's
      one outstanding item** — the live GHL read — so that acceptance moves here.
- [ ] A second brand connects to a *different* location, and each brand's funnel
      shows its own location's deals.
- [ ] A callback with a missing, wrong, expired or replayed `state` is refused, and
      all four are indistinguishable in the response.
- [ ] A callback cannot bind a location to a brand named in the request; only the
      state row decides the brand.
- [ ] **Two concurrent reads across an expired access token produce one refresh, and
      both callers succeed.** The regression this unit exists to avoid — test it with
      two threads against a real Postgres, in `LocalPostgresIntegrationTest`.
- [ ] The rotated refresh token is persisted; the previous one is not reused.
- [ ] A refresh GHL refuses marks the connection revoked, and the screen says
      reconnect instead of 502.
- [ ] Disconnect clears the grant; the next read reports no connection.
- [ ] The refresh token appears in no DTO, no log line, and no error message. Assert
      it, the way `GhlPipelineClientTest` asserts the PIT never reaches a message.
- [ ] `GHL_API_TOKEN` and `GHL_LOCATION_ID` are gone from `application.yml` and from
      the code, and `GhlPipelineClientHttpTest` still passes against its local server.
- [ ] `./mvnw -Devalos.db.test=true test` green with 0 skipped; `npm run build` clean.

## Invariants honored

- **1 — brand isolation.** Strengthened rather than merely honored: the credential
  becomes a brand-scoped row, so the GHL read stops being global configuration. The
  unique index on `brand_id` is what makes "one brand, one location" the database's
  rule rather than a convention.
- **2 — EvalOS runs no marketing.** Unchanged. `opportunities.readonly`, and
  `GhlPipelineClient` still has no write method.
- **4 — secrets never leave.** Extended to the refresh token, by the same converter
  and the same "never in a DTO, log or payload" rule.
- **6 — one bounded request.** A refresh is one extra bounded call on the read path,
  and Unit 24's cache means it happens once per TTL rather than per dashboard.
- **13 — append-only.** Connect and disconnect are audit rows; the token itself is
  never in a snapshot.

## What this unlocks (Unit 25a, not this unit)

Once credentials are per brand, Unit 24's central scoping argument expires. The
follow-on is small and should be taken deliberately, not drifted into:

- `GET /api/marketing/ads-pipeline` accepts `brandId` and narrows by it.
- The **Brand Manager can be admitted** — they would see their own brand's funnel, so
  the cross-brand leak that excludes them today no longer exists.
- Invariant 1's stated exception in `architecture.md` is **removed**, along with the
  matching notes in `code-standards.md`, `mem:core` and `navigation.test.ts`'s GM-only
  assertion.

Depends on: 02 (staff auth + chains), 24 (the client and screen this credentials).
