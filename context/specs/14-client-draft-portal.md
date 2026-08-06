# Unit 14 — Client draft-review portal

**Phase:** 2 — Connect the seams
**Depends on:** 02 (a separate auth surface beside the staff chain), 04
**Unlocks:** 15 (the expert portal reuses this unit's token model and portal
principal), and Handoff B — client approval is what sends the case to the expert
**Gating open questions:** two.
1. **Whether GHL can send a client-facing transactional message on an EvalOS event
   trigger** (open question (b)). The portal is reachable only by a link somebody
   delivers; if GHL cannot deliver it, this unit ships a working portal that no
   client can reach, and the link has to be copied out by staff as a stopgap.
2. **Explicit sign-off to touch the audit-trail entity and its write path.**
   `ai-workflow-rules.md` lists both as protected — "do not modify unless
   explicitly instructed" — and this unit adds an `actor_type` column and a third
   writer to them. The append-only guarantee is *not* weakened (no update or delete
   path is introduced, `AuditEventRepository` still extends the bare `Repository`
   marker, every column stays `updatable = false`, and the `V10` trigger is
   untouched), and the reasoning is set out under "the audit actor problem" below —
   but the rule asks for instruction, not for a good argument, so **ask before
   writing it**. If the answer is no, the fallback is that a client's approval is
   audited with a null actor and is indistinguishable from a webhook's, which is a
   worse trail for the one action in the system a client performs.

Confirm both before starting.

## Goal

The client reads the drafted letter and says yes or asks for changes. Today
`draft.ready_for_client` is published and a staff member records the client's
answer by hand. This unit gives the client the screen, so their approval is their
own act, recorded as theirs.

**Verifiable result:** a client opens a passwordless link, sees exactly their own
case's draft and nothing else, approves or requests revisions, and the case moves
(`DRAFT_GENERATION → EXPERT_SIGNING` on approval) with an audit entry naming
**the client** as the actor — while the same link on another case, an expired
link, and a revoked link all fail.

## In scope

- The **`portal_access` token model** and a **separate Spring Security filter
  chain** for client access. Both are built once here and reused by Unit 15.
- The **`draft_link` column** and the defect it closes (below).
- The single-page draft view; approve / request-revisions.
- Read-receipt tracking into the existing `client_portal_read_at`.
- The `actor_type` the audit trail needs to say a client did something.

## Out of scope

- **Source-document upload.** Documents are collected in Drive (Unit 10); the
  client portal is draft review only.
- Any case list. A token names one case, so there is no index page to build.
- Delivering the link — GHL sends it (Unit 18 dispatches the trigger). This unit
  mints the link and exposes it to staff for the stopgap.
- The expert's portal — Unit 15, on this unit's foundations.
- Client login, account, or password. There is none, by design.

## The defect this unit must close first

`frontend/src/features/case/DraftPanel.tsx` renders **"Open the current draft ↗"**
pointing at `detail.driveLink`, and there is **no `draft_link` anywhere in the
backend or the frontend.** `drive_link` is the client's *own document folder* —
where their passport scans and transcripts live.

Internally that is a mislabel. Put a client-facing portal on top of it and it is a
leak: the portal would hand the client a link to a folder whose contents and
sharing EvalOS does not control, presented as "your draft". A client portal cannot
be built on a column that means something else.

So: a new `draft_link` column on `evalos_case`, set by the Case Manager when they
submit a draft, and `DraftPanel` re-pointed at it. **`drive_link` is never sent to
the portal** — not renamed, not aliased, not defaulted to. A case whose
`draft_link` is unset shows the portal an honest "the draft is not ready" rather
than falling back to the documents folder.

## The token model

New migration (next free `V`-number), `portal_access` — the one table for both
portals:

| column | note |
| --- | --- |
| `id`, `brand_id`, `created_at` | the `ScopedEntity` shape |
| `case_id` | the **one** case this token admits |
| `audience` | `CLIENT` · `EXPERT` (Unit 15 uses the second) |
| `token_hash` | SHA-256 of the token. **The token itself is never stored** |
| `expires_at` | absolute expiry |
| `revoked_at` | nullable; set when a new token supersedes this one |
| `last_seen_at` | stamped on use, for the read receipt and for support |

Rules, and why:

- **The token is 256 bits from `SecureRandom`, base64url**, and is returned
  exactly once — at mint time. It is stored **hashed**, so a database read (a
  backup, a support query, a leaked dump) yields no working link. This is the same
  reasoning as BCrypt on `team_member.password_hash`; a portal link is a
  credential.
- **Compared with `MessageDigest.isEqual`**, like `WebhookVerifier` — a portal
  token check is exactly as much a secret comparison as an HMAC check.
- **Unique index on `token_hash`.**
  **Corrected during the build — this paragraph shipped wrong and `V23` fixes it.**
  What it said: a partial unique index on
  `(case_id, audience) WHERE revoked_at IS NULL AND expires_at > now()` cannot exist
  because `now()` is not immutable, therefore one live token per case per audience is
  enforced in the service by revoking the previous one inside the minting transaction.
  The premise is true and the conclusion does not follow — the invariant can be stated
  without `now()` at all.
  Enforcing it in the service alone made `mint` a check-then-act: SELECT-live → revoke
  → INSERT, with no lock, so under READ COMMITTED two concurrent mints for one case
  (two staff members, or one double-click) could both see the same previous row, both
  revoke it, and both insert — two live credentials for a case the javadoc promised
  one. `V23` adds the partial unique index on `(case_id, audience) WHERE revoked_at IS
  NULL`, which is immutable and enough, since an expired row is revoked too and so
  leaves the index. `isLive` is unchanged.
  **The general rule this is an instance of:** "the service does it inside a
  transaction" is not a uniqueness guarantee. A transaction gives atomicity, not
  mutual exclusion; only the database can refuse the second writer.
- **Expiry is real.** Default 30 days, configurable. A draft-review link that works
  forever is a permanent bearer credential sitting in somebody's inbox.
- **Re-minting revokes the previous token.** A client who says "the link doesn't
  work" gets a new one, and the old one stops working — otherwise every support
  request permanently widens the number of live credentials.

### The portal principal — why it cannot reuse `TenantContext`

`CaseLifecycleService.load(caseId)` calls `cases.findScoped(TenantContext.current(),
caseId)`, and `TenantContext` is `(memberId, Role, brandId, teamId)` built from a
`StaffPrincipal`. A client has none of those, and **manufacturing a synthetic
`TenantContext` for them is the wrong answer** — it would put a non-staff caller
into the staff scoping path, where a future widening of a role tier silently widens
what a client can read.

Instead:

- `security/PortalPrincipal` — `(portalAccessId, brandId, caseId, audience)`. The
  token *is* the scope: it names one case, so there is no predicate to build and
  nothing to fail open. `ScopePredicate` is not involved and is not modified.
- `security/PortalTokenFilter` + a **second `SecurityFilterChain`**,
  `securityMatcher("/api/portal/**")`, ordered **before** `staffApi`. The chains
  are fully separate: no JWT is accepted on a portal route and no portal token is
  accepted on a staff route. `ApiErrors` writes the envelope for its 401s, as it
  does for the staff chain.
- `service/PortalCaseService` — the portal's own narrow read, loading by the
  token's `case_id` and projecting **only** what the client may see. Not a widened
  `CaseDetailService`: that DTO carries the deal value, the PM's strategy notes,
  the expert, the assignment history and the audit timeline.
- The transitions are still Unit 04's. `clientApproveDraft` / `clientRequestRevisions`
  already exist and are already documented as "called by the client portal (Unit
  14) and by staff recording the answer" — they gain a portal-safe entry point
  that takes the already-authorized `Case`, so the state machine, the guards, the
  audit row and the event stay in exactly one place.

### What the client may see

A whitelist, for the same reason Unit 13's redaction is one: the client's case is
full of things that are not theirs.

**Included:** their own name, the service type, the case reference, the
`draft_link`, the draft version, the approval state, and the redacted expert
profile (Unit 13). **Excluded:** `deal_value`, `pm_strategy_notes`, the expert's
identity, `invoice_ref`, `campaign_attribution`, every assignment field, the audit
timeline, the document checklist, and `drive_link`.

## The audit actor problem, and the append-only consequence

Invariant 13 wants an actor on every entry. `audit_event.actor_id` is nullable and
`null` currently means *the system* (`AuditService.recordSystemEvent`, used by the
inbound webhook). A client approving a draft is neither a staff member nor the
system, and it is the approval that sends a letter to an expert to sign — the trail
has to attribute it correctly.

Add `actor_type` to `audit_event`, and a third writer,
`AuditService.recordPortalEvent(brandId, audience, ...)`, which takes its brand
from the **token**, the way `recordSystemEvent` takes it from the endpoint token,
and for the same stated reason: the argument is trustworthy only because it comes
from the most authoritative signal available, never from a request body.

**The new column is nullable with no default, and existing rows are not
backfilled.** This is forced, not lazy: `V10` installs a
`BEFORE UPDATE OR DELETE` trigger that raises on `audit_event`, so **no `UPDATE`
can ever touch the existing rows** — the append-only guarantee has teeth, and this
is the first unit to feel them. `ADD COLUMN … NOT NULL DEFAULT 'STAFF'` would
stamp every historical row `STAFF`, including the Unit 05 webhook rows that are
genuinely `SYSTEM`, and that mistake would be **permanently unfixable**. So: null
means "written before this column existed", readers infer `SYSTEM` from a null
`actor_id` and `STAFF` otherwise, and every row written from this unit onward
states its actor type explicitly. Recorded in the migration header, which is where
this file's convention puts a correction to the record.

**Readers key on the column and fall back only when it is null** — do not keep
inferring from `actor_id`, which was the pre-`V22` rule and is now wrong. `CLIENT` and
`EXPERT` both carry a null `actor_id` exactly as `SYSTEM` does, so inference cannot
tell a client's approval apart from a job's write, and the timeline would credit the
client's decision to the system. `CaseTimelineService.actorName` is the shipped
shape and the one to copy: a named staff member if `actor_id` resolves, otherwise
whatever `actor_type` says, and "System" only when it says nothing. The null check
before the `Map` lookup is load-bearing — `Map.of()` throws on a null key rather than
returning the default.

## Backend

Client portal routes, all under the portal chain. The token travels in the
`X-Portal-Token` header, sent by the SPA after it reads the token out of the URL
fragment — **not as a query parameter**, which lands in access logs, `Referer`
headers and browser history.

| Method | Path | Auth | Notes |
| --- | --- | --- | --- |
| GET | /api/portal/client/case | portal token (CLIENT) | the whitelisted view; stamps `client_portal_read_at` and `last_seen_at` on first read |
| POST | /api/portal/client/approve | portal token (CLIENT) | → Unit 04 `clientApproveDraft`; Handoff B |
| POST | /api/portal/client/request-revisions | portal token (CLIENT) | → Unit 04 `clientRequestRevisions` with the client's notes |

Staff-side, on the normal chain:

| Method | Path | Auth | Notes |
| --- | --- | --- | --- |
| POST | /api/cases/{id}/portal-link | GM · Brand Manager · PM · CM | mint (or re-mint) the client link; returns the full URL **once**; audited |

No route takes a case id from the client. There is nothing to enumerate, because
the token names the case.

**Rate-limit the portal chain** and answer a bad token identically whether it is
unknown, expired or revoked — nothing about which is learnable from the response,
the same discipline `WebhookVerifier` applies.

The read receipt is stamped **once**, on first read (`client_portal_read_at` is
already on the case and unused). `last_seen_at` on the token moves every time,
which is the field support actually needs.

## Frontend deliverables

1. **A separate entry point** (`features/client-portal`) that does **not** mount
   `AppShell`, `AuthProvider`, the brand switcher or the nav — a client is not a
   staff user with fewer links. Route `/portal/client#<token>`; the token is read
   from the fragment, held in memory, and put in the header. Same-tab only; nothing
   is written to `sessionStorage` — Unit 07 put the staff token there deliberately,
   and a link forwarded to a shared machine is a different risk.
2. **Single-page draft view**: the client's name, what was ordered, the draft link,
   the version, and the redacted expert profile. One screen, no navigation.
3. **Approve** and **Request revisions** (with a notes box; revisions without a
   reason are useless to the Case Manager). Both confirm before firing —
   approval is what commits the letter to an expert's signature.
4. **Post-action state**: after approving, the page says what happens next and the
   actions are gone. The token still reads; it just has nothing left to do.
5. **Honest failure states** for expired / revoked / not-yet-ready-draft, each
   telling the client to contact whoever sent the link — never a stack trace and
   never a login form, which a client has no account for.
6. Staff side: a **Client portal link** control on the case detail page showing
   whether a live link exists, when it expires, whether it has been opened, and a
   re-mint action that warns the old link stops working.

## Acceptance criteria

- [ ] A valid `CLIENT` token reads exactly its own case. The same token on any
      other case is impossible (there is no case parameter), and a token from
      another brand's case reads that case only — proved by minting two and
      crossing them.
- [ ] The portal response contains **none** of `deal_value`,
      `pm_strategy_notes`, the expert's name, `invoice_ref`,
      `campaign_attribution`, `drive_link`, or any assignment field. Asserted by
      serializing the response and grepping for each.
- [ ] A staff JWT is **rejected** on `/api/portal/**`, and a portal token is
      **rejected** on `/api/cases/**`. Both directions asserted — two chains that
      accept each other's credentials are one chain.
- [ ] Expired, revoked and unknown tokens are indistinguishable in the response.
- [ ] Re-minting revokes the previous token: the old link stops working
      immediately.
- [ ] Approving moves the case to `EXPERT_SIGNING`, publishes
      `draft.client_approved`, and writes **one** audit entry whose `actor_type`
      is `CLIENT` and whose `actor_id` is null — distinguishable from the webhook's
      `SYSTEM` rows.
- [ ] Approving a case whose draft is not with the client answers 409 through the
      existing guard, not a portal-specific check — the state machine is not
      duplicated.
- [ ] `client_portal_read_at` is stamped once and does not move on the second read;
      `last_seen_at` moves every time.
- [ ] `draft_link` is what the portal and `DraftPanel` both show, and a case with
      no `draft_link` shows "not ready" rather than `drive_link`.
- [ ] `npm run build` green; `./mvnw verify` green, with the new indexes and the
      `actor_type` column exercised DB-gated against local Postgres.

## Invariants honored

Brand isolation — the token carries the brand and admits one case, and the client
view is a whitelist (1, 3); the client sees nothing outside their assignment (3);
`payment_detail` is nowhere near this surface (4); controllers thin, the
transition in `service` via Unit 04 (6); the client's approval writes an
append-only audit entry naming the client, and the column added to support that
does **not** weaken append-only — no `UPDATE` path is introduced and the trigger
is untouched (13); no email sent — GHL delivers the link (14).

## Files touched

**Created.** Backend: `domain/PortalAccess.java`, `domain/PortalAudience.java`,
`domain/ActorType.java`, `repository/PortalAccessRepository.java`,
`security/PortalPrincipal.java`, `security/PortalTokenFilter.java`,
`service/PortalAccessService.java` (mint / revoke / resolve),
`service/PortalCaseService.java`, `web/ClientPortalController.java`,
`web/PortalLinkController.java` (+ DTOs). Migrations:
`V<next>__case_draft_link.sql`, `V<next+1>__portal_access.sql`,
`V<next+2>__audit_actor_type.sql`. Frontend:
`frontend/src/features/client-portal/*` (`ClientDraftView`, `portalApi`,
`PortalRoot`), `frontend/src/features/case/PortalLinkPanel.tsx`.

**Modified.** `security/SecurityConfig.java` — the second chain beside
`staffApi`, ordered first, matching `/api/portal/**`. `domain/Case.java` and
`service/CaseLifecycleService.java` — `draft_link`, plus the portal-safe entry to
`clientApproveDraft` / `clientRequestRevisions` taking an already-authorized case.
`service/AuditService.java` — `recordPortalEvent`, and `actor_type` on the two
existing writers. `domain/AuditEvent.java` — the new column, mapped
`updatable = false` like every other field on it.
`frontend/src/features/case/DraftPanel.tsx` — re-pointed at `draftLink`.
`frontend/src/App.tsx` — the portal route mounted outside the shell.

**Not touched.** `service/ScopePredicate.java` — the portal deliberately does not
use it. `AuditEventRepository` (no write/delete path added), the `V10` trigger, and
every applied migration.
