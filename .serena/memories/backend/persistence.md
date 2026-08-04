# backend/ — Persistence, audit & field encryption

Built in Unit 03 (`V4`–`V10`). Entities: `ContactSnapshot`, `Case` (table **`evalos_case`** — `case`
is reserved SQL), `DocumentChecklistItem`, `Expert`, `PayoutLedger`, `Notification`, `AuditEvent`,
plus Unit 02's `Brand`/`TeamMember`, Unit 12's `ExpertCaseOffer` and Unit 14's `PortalAccess`. Schema
now runs to **`V23`**;
`V11`–`V17` added the per-brand
webhook secret, the webhook archive + its brand-scoped idempotency key, `case.paid`/`paid_at`, the
one-open-case-per-contact-service index, contact identity, and `case.assigned_coordinator`. **`V18`
(Unit 11)** added `expert.email`/`phone`/`letter_types`/`standard_fee`, three vocabulary CHECKs
(`primary_fields`/`secondary_fields` against `FieldTag`, `letter_types` against `LetterType` — the
`V3` role-CHECK pattern, and the only CHECKs on enum columns besides that one), the partial unique
index `uq_expert_per_brand_email` on `(brand_id, lower(email))`, and a GIN index on
`primary_fields` built for Unit 12's tag containment rather than for Unit 11's own filter.
**Unit 14** added four: `V20` `evalos_case.draft_link`, `V21` `portal_access`, `V22`
`audit_event.actor_type` (see the append-only section — that one is the first column ever added to
the audit table, and it was added on explicit instruction), and `V23`'s one-unrevoked-token index,
which its code review added after finding the mint was a check-then-act.

`draft_link` vs `drive_link` is not a detail: `drive_link` is the client's **own document folder**
(passports, transcripts) and `draft_link` is the drafted letter. Only the second is ever shown to a
client, and a case without one is told "not ready" — never given the folder as a fallback. `DraftPanel`
pointed the client-facing "open the draft" link at `drive_link` from Unit 09 until `V20` existed, which
is the mislabel Unit 14 had to close before it could put a portal on top of it.

## `portal_access` (`V21`, Unit 14) — one table, both portals

One row admits one `case_id` to one `audience` (`CLIENT` / `EXPERT`, closed by CHECK). **The token is
never stored** — only its hex SHA-256, so a backup, a support query or a leaked dump yields no working
link; `PortalAccess.matches` compares with `MessageDigest.isEqual`. `expires_at` is absolute,
`revoked_at` is set when a re-mint supersedes the row, `last_seen_at` moves on every use (the case's
own `client_portal_read_at` is stamped **once**, on first read — two fields, two questions).
Two unique indexes. `uq_portal_access_token_hash` on the hash, and **`uq_portal_access_one_unrevoked`
on `(case_id, audience) WHERE revoked_at IS NULL` (`V23`)** — that second one is the invariant "one
live token per case per audience", and it is a constraint rather than a service check because `mint`
was otherwise a check-then-act two concurrent calls could both win (the `V15`/`V16` lesson).
**`V21`'s header says this could not be an index and is wrong on disk** (applied migrations are never
edited; `V23`'s header corrects it): its premise was right — `expires_at > now()` cannot sit in an
index predicate — but stating the invariant as *at most one unrevoked row* needs no clock.
Consequence to keep: **`mint` retires every unrevoked row it supersedes, not only the live ones**, or
an expired row would sit in that index forever and block the next mint for that case.

`PortalAccessRepository.findByTokenHash` is deliberately **unscoped**: a client has no
`TenantContext`, and the row that comes back carries the brand. See `mem:backend/security`.

## Entity patterns

- Scoped entities extend `domain/ScopedEntity` (`@MappedSuperclass`): generated UUID `id`, `brand_id`
  (NOT NULL, `updatable = false` — a row never changes brand), `created_at`. Its `@PrePersist` stamps
  `created_at` and **throws if `brand_id` is null**: a row with no brand cannot be scoped by anything
  afterwards, so it never gets written.
- Hibernate uses **field access** (`@Id` on a field), so getters are not needed to persist or
  validate. Accessors are added when a consumer appears — do not bulk-write getters/setters.
- **Foreign keys are raw `UUID` columns, never JPA associations.** No `@ManyToOne`/`@OneToMany`
  anywhere: scoping must stay a plain column predicate and never depend on loading another entity.
- Enum columns are `text` + `@Enumerated(EnumType.STRING)`; the 21 vocabulary enums live in `domain/`.
  `NotificationType` and `AuditAction` are **open** — their columns carry no CHECK, so later units add
  values without a migration. No CHECK constraints on the other enum columns either (only `V3.role`).
  That openness has been spent three times: `CHASED` (Unit 10), `IMPORTED` (Unit 11) and `EXPORTED`
  (Unit 13 — a generated document left EvalOS; the snapshot carries the Drive file and folder ids, so
  the trail answers *which* document and *where it went*). Each is its own action rather than
  `UPDATED` because in all three nothing about the object itself changed.
- `text[]` → `String[]` with `@JdbcTypeCode(SqlTypes.ARRAY)`; `jsonb` → `String` with
  `SqlTypes.JSON`. Enum arrays are avoided — they buy nothing and risk `validate` mismatches.
- Contact snapshots: GHL is the only writer (invariant 7). Columns stay physically updatable so the
  `contact.updated` sync can refresh them; the rule is enforced by "only the sync writes", not by
  `updatable = false`.

## No optimistic locking — guard uniqueness in the database

**No entity carries `@Version`.** Every "check the row, then act" guard in the service layer is
therefore a check-then-act that two concurrent callers can both win: both read, both save,
last-write-wins. This has already produced two real defects (a second open case per contact, a second
contact snapshot per person) and one latent third (two payout rows for one delivery, since
`deliverToClient`'s `deliveryDate == null` guard has nothing behind it).

**The house answer is a partial unique index, not a lock and not a re-read.** `V15` and `V16` are the
worked examples: `(brand_id, contact_id, service_type) WHERE current_stage <> 'CLOSED'`, and
`(brand_id, ghl_contact_id)` / `(brand_id, lower(email))` where not null. Partial because the
exclusion is what makes the rule correct — a contact returning after their case closed is new
business, not a duplicate. The loser's transaction rolls back; for webhook paths that surfaces as a
retriable 5xx and the redelivery refreshes the committed row, which is what intake wanted anyway.

`lower(email)` in the index because the lookup is `findByBrandIdAndEmailIgnoreCase` — index expression
and finder must agree or the index does not apply. Note `V15`'s own comment claims the race "cannot
race" and is **wrong on disk**; it is applied, so it was corrected in `V16`'s header rather than
edited (invariant 9).

## `expert_case_offer` (`V19`, Unit 12) — append-only with exactly one mutable column

The one queryable record of an accept/decline; its whole purpose is to be **aggregated** into an
acceptance rate. It is not a second history — the audit trail still records each transition.

- Every column is `updatable = false` **except `outcome`**, which leaves `OFFERED` exactly once
  through `ExpertCaseOffer.resolve`. **First write wins; a later or repeated outcome is a no-op, not
  an error** — Unit 15 has two acts that both mean accepted (expert presses Accept, then Dropbox
  Sign's `signed` callback) and both fire on the happy path, so throwing would fail a normal
  sequence. The guard is on the entity, the one place owning the column, not in the four callers.
  `resolve` **refuses `OFFERED` as a resolution** rather than letting the CHECK below blow up at
  flush.
- Two CHECKs, for the reason `V18` gives: `outcome IN (...)` because the scorer divides by a count of
  these values and one misspelling drops out of numerator and denominator at once; and
  `(outcome = 'OFFERED') = (outcome_at IS NULL)`, because an open offer with a resolution date and a
  resolved one without are the same fact stated twice.
- **The partial index on `(case_id) WHERE outcome = 'OFFERED'` is NOT unique**, so this section's
  check-then-act warning applies: two concurrent assignments can leave two `OFFERED` rows. That
  already shipped one defect — `resolveOpenOffer` stamped *every* open offer, crediting an expert
  never shown the case. **Only the case's own expert takes the real outcome; a stray is closed
  `SUPERSEDED`**, which already means "never had the chance to answer".
- `resolveOpenOffer` is tolerant on both edges on purpose: a case with **no** open offer (assigned
  before `V19` existed) is left alone rather than failing the transition — a reporting concern must
  not block the pipeline.
- `TIMED_OUT` is declared and **written by nobody until Unit 15's `EXPERT_TIMED_OUT`**.

## Denormalized columns nothing writes

Four `NOT NULL DEFAULT 0` columns on `expert` were created in `V7` and **have never been written by
anything**: `current_active_count`, `total_cases_completed`, `total_payments_pending` (and the
`performance_flags` array is written only by seed data). Reading them yields a permanent zero.

**Derive these; do not start incrementing them.** A counter has to be adjusted on assign, close,
refund, reassign and decline, and backfilled for existing rows — five chances to drift on figures
about load and money, at a scale (50–100 cases/brand/month) where a grouped `COUNT`/`SUM` over
`evalos_case` / `payout_ledger` is trivial. Batch the query for a whole page; never one per row. The
columns are deliberately left in place rather than dropped — dropping columns is not a drive-by, and a
later read-model unit may want them as a materialized cache.

**Unit 11 did this for the two case counters and it is the pattern to copy.**
`service/ExpertLoadService` answers `{active, completed}` per expert from one
`CaseRepository.countCasesPerExpert` — native SQL, `count(*) FILTER (WHERE …)`, one grouped pass for
a whole roster page. Native because `FILTER` makes it one pass and because this entity's JPQL name is
`Case`, which is also a JPQL keyword. "Completed" excludes a refunded case, matching
`RefundService.isRefunded`. The finder is **deliberately brand-unscoped** over ids the caller already
read scoped, so it carries the `findByCaseIdIn` javadoc convention and a DB-gated brand-isolation
test. A DB-gated test also pins an expert with two open cases reporting a load of **2 while
`current_active_count` in the same row is still 0** — so "fixing" the derivation by incrementing the
column breaks the build. Unit 12 reuses the service; Unit 16 owes `total_payments_pending` the same
treatment.

Same trap, different shape: `Case.retention_30_sent_at` … `retention_365_sent_at` and
`google_review_requested` exist and are unwritten, reserved for the jobs/outbound units.

## Scoped repositories

Extend `repository/ScopedRepository<T>` and declare a `SCOPE` constant + `scopeFields()` override
naming the entity attributes that carry brand / team / assignee. That yields `findScoped(ctx)` and
`findScoped(ctx, id)`, both built on `service/ScopePredicate` (`mem:backend/security`).

- Inherited `findAll()` / `findById()` are **not** scoped and cannot be removed — use `findScoped`
  for reads, and `OwnershipGuard` before writing a row obtained any other way.
- `Case` is the only type using all three axes (`brandId`, `teamId`, and a **set** of assignee
  attributes — `assignedCm` OR `assignedCoordinator`, since one case is worked by several people in
  different slots); `Notification` scopes by `recipientId` as its assignee; contacts, checklist items,
  experts and payouts are brand-only (`recorded_by` names who typed the row, not who owns it).
- `scopeFields()` is abstract on purpose — **never** give it a brand-only default. Forgetting to
  declare a team/assignee axis widens reads, the one direction `ScopePredicate` cannot fail closed on.
- `DomainInvariantsTest` guards all three ways that can go wrong: every `SCOPE` attribute must be a
  real mapped field (a typo would otherwise surface as a runtime failure in the query meant to keep two
  brands apart); an entity declaring `teamId` must scope by it; and **every** `ScopedEntity` subclass
  on the classpath must appear in the test's repository table, so adding an entity without declaring
  its scope breaks the build.
- A few finders are **deliberately brand-unscoped** batch reads over ids the caller already read
  scoped (`DocumentChecklistItemRepository.findByCaseIdIn`,
  `AuditEventRepository.findByObjectTypeAndActionAndObjectIdIn`). They carry a javadoc convention —
  *do not call with ids that came from a request* — plus a DB-gated brand-isolation test. Any new
  aggregate of this shape owes both.

## Append-only audit

Enforced three independent ways, because lost audit history is unrecoverable:

1. `AuditEventRepository extends Repository` (the bare marker, **not** `JpaRepository`) — `save` plus
   a few finders are the only methods that exist. Never add one that can update or delete. Adding a
   *read* finder means widening the whitelist in
   `DomainInvariantsTest.theAuditRepositoryCannotChangeHistory`, which is what that test is for.
2. Every `AuditEvent` column is mapped `updatable = false`, so Hibernate cannot emit an UPDATE.
3. A `BEFORE UPDATE OR DELETE` trigger in `V10` raises for every role, including the owner (a GRANT
   cannot do this: the app connects as the table owner, and an owner ignores REVOKE).

**Consequence: `audit_event` can never be backfilled.** The trigger blocks every `UPDATE`, so a new
column added with `NOT NULL DEFAULT 'x'` stamps *every historical row* with a value you can then never
correct (`ALTER TABLE` is DDL and does not fire row triggers, so it succeeds — and the mistake is
permanent). Add columns to this table **nullable with no default**; null means "written before this
column existed" and readers infer the old meaning. Record it in the migration header.

**`V22` (Unit 14) is the first and so far only instance of that rule, and it was written on explicit
instruction** — `ai-workflow-rules.md` protects this entity and its write path, so it was signed off
before the migration existed. `actor_type` (`ActorType`: `STAFF`/`SYSTEM`/`CLIENT`/`EXPERT`) is
nullable, undefaulted and unbackfilled: a `DEFAULT 'STAFF'` would have stamped the Unit 05 webhook
rows STAFF when they are genuinely SYSTEM, permanently. For a null, read SYSTEM when `actor_id` is
null and STAFF otherwise (`CaseTimelineService.actorName` does exactly that — and its null check is
load-bearing, because `Map.of()` throws on a null key rather than answering the default). Append-only
is untouched: no update/delete path was added, the column is `updatable = false`, and the `V10`
trigger is unchanged. **No CHECK on it**, unlike `V18`/`V19`/`V21` — a constraint here is a way for an
audit write to fail, and that is the one write that must never roll a transition back.

`AuditEvent` is deliberately **not** a `ScopedEntity` — `brand_id` is nullable for system events — but
it stamps `created_at` in `@PrePersist` like everything else: **one clock for every timestamp in the
schema**, so a timeline interleaving an object's `created_at` with its audit rows orders correctly. DB
`DEFAULT now()` stays as the backstop for raw-SQL inserts only.

Every column carries an explicit `@Column(name = ...)`, including single-word ones and `id`. Nothing
relies on `CamelCaseToUnderscoresNamingStrategy`: the column name is a contract shared with the Flyway
migrations, and the strategy does not always agree with it (`retention30SentAt` derives to
`retention30_sent_at`, not the actual `retention_30_sent_at`).

`service/AuditService.recordEvent(objectType, objectId, action, actorId, before, after)` is the only
writer for request-scoped actions. It joins the caller's transaction, so the trail commits with the
change it describes or not at all. Brand is derived from `TenantContext`, never a parameter.
`recordSystemEvent(brandId, …)` is the separately-named variant for actions with no authenticated
caller (today only the inbound webhook, which resolved its brand from the endpoint token first) —
separately named, not an overload, so no request-scoped caller can reach it and quietly claim a brand.
`recordPortalEvent(brandId, audience, …)` is the third writer (Unit 14) for something a client or
expert did through their own portal link: `actor_id` stays null because no `team_member` acted, and
`actor_type` is what stops that null reading as "the system".
**`actor_id` and `actor_type` must never disagree, and no writer may hardcode the type**: `recordEvent`
derives it (`actorId == null ? SYSTEM : STAFF`) precisely because its contract allows a null actor, and
a STAFF row beside a null actor could never be corrected on an append-only table. `AuditServiceTest`
pins all three writers and both branches. Its brand comes off the **token's own
row** — the most authoritative signal on that surface, the same argument `recordSystemEvent` makes for
the endpoint token. A null `actor_id` therefore no longer means "the system" on its own; read it with
`actor_type`. Snapshots are Jackson-serialized to `jsonb`: pass DTOs or maps, not entities.

Audit rows are written against the **owning object** (usually the case) rather than the child row, with
the change stated in the snapshot's `note` — so one screen's timeline shows all of it. Derive
"when did X last happen" from the trail rather than adding a column: a second record of one fact is a
second thing that can disagree (the reason there is no `paid_by` and no `last_chased_at`).

## The one encrypted field

`common/PaymentDetailConverter` (`@Component` + `@Converter`; key `evalos.security.field-key`, base64
of exactly 32 bytes, injected — there is no no-arg constructor, so a context that cannot supply the
key fails at startup instead of writing plaintext) is the only path to `expert.payment_detail`:
AES-256-GCM, fresh 12-byte IV per write, stored as `base64(iv || ciphertext||tag)`.

- GCM is authenticated ⇒ an edited column fails to decrypt rather than returning plausible plaintext.
- The random IV means the column is **not searchable or equality-comparable**. It is display data.
- Field and getter are `@JsonIgnore`, and `Expert` deliberately has no `toString()`. Never map it into
  a DTO, outbound webhook payload, or log line. It is the only encrypted field — payouts are manual,
  so there is no card or bank data anywhere else.
- **Write-only since Unit 11, which gave it its first screen.** `PUT /api/experts/{id}/payment-detail`
  sets it and **nothing reads it back** — no endpoint, no service method, not for the ENM who typed
  it. No DTO declares the field (not blanked, not masked: *not a member*), the audit snapshot records
  only that it was set, and the sheet import refuses a mapping naming it. Screens get
  `Expert.hasPaymentDetail()`. `ExpertControllerTest` walks every expert route with the field
  populated and greps each serialized body; it asserts on `"paymentDetail"` **quoted**, because
  `paymentDetailOnFile` is a legitimate member.
