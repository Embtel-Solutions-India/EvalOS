# backend/ — Persistence, audit & field encryption

> ## Removing an enum value: two different rules, and the difference is the store
>
> Learned removing Unit 13 (2026-09-02). **Whether a value can be deleted depends on whether
> anything ever wrote it, and whether that store can be rewritten.**
>
> - **`case_document.kind` → `REDACTED_PROFILE` was DROPPED.** Nothing ever wrote one (the
>   table is V31; Unit 13 stalled long before it), so the value could orphan nothing. `V33`
>   narrows the CHECK, because the CHECK is the writer the enum cannot reach — a seed script
>   or a hand-run UPDATE could still write a value `valueOf` would then choke on.
> - **`AuditAction.EXPORTED` was KEPT.** The audit trail is append-only by invariant and its
>   rows can never be rewritten, so an enum that cannot read a value some historical row
>   carries fails on read. **A retired audit action stays readable forever.** That is the
>   cost of an immutable history and it is the right cost.
>
> **The test to apply:** can I rewrite every row that might hold this value? If yes, drop it
> and narrow the CHECK. If no — audit, or any append-only table — keep the value and mark it
> retired in the javadoc. Same question, opposite answers, one session apart.

> ## ⚠ PIVOT: Google Drive → S3 document store (Unit 30, SPECCED 2026-09-02, NOT BUILT)
>
> **Read `context/specs/30-s3-document-store.md` before touching any document path.**
> Everything below about Drive still describes the **code as it stands today** — the client,
> the config, the columns are all still there. It no longer describes the **decision**.
>
> - Documents move to an **S3 bucket**. Google Drive leaves entirely: client, config,
>   service account, dependency, `drive_link` column.
> - **A separate Client Portal application writes client uploads**; EvalOS's credential is
>   **read-only** on `client/{clientId}/`. EvalOS writes only under `case/{caseId}/`.
> - `{clientId}` is **GHL's contact id** — one client across GHL, the Client Portal and
>   EvalOS, no mapping table. **Email stays a fallback key (V27), never the identity.**
> - Reads are **5-minute presigned URLs**, minted after the scope check, never stored.
> - **Invariant 14 is amended and "No object storage" is deleted** from `architecture.md`.
> - This **unblocks Units 13, 15 and 21**, which were all waiting on the Google service
>   account. Unit 21 is reshaped: the upload leaves EvalOS.
>
> **Do not build new Drive work, and do not cite the Drive notes below as settled.**

Built in Unit 03 (`V4`–`V10`). Entities: `ContactSnapshot`, `Case` (table **`evalos_case`** — `case`
is reserved SQL), `DocumentChecklistItem`, `Expert`, `PayoutLedger`, `PayoutPayment`, `Notification`, `AuditEvent`,
plus Unit 02's `Brand`/`TeamMember`, Unit 12's `ExpertCaseOffer` and Unit 14's `PortalAccess`. Schema
now runs to **`V28`**;
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

**Units 16 + 16b** added **`V28`**, one migration carrying the whole payout change: the new
`payout_payment` table (one real-world transfer — `amount > 0`, not `>= 0`, because a payment of
nothing would sum into money-out totals while looking like a settled week), `payout_ledger.payment_id`
pointing at it, and the **drop** of `payout_ledger.method`/`reference`/`paid_date`. That drop departs
from the precedent that left the dead HMAC columns in place, and the reason is that those three sat
on a *money* table and read as load-bearing — a future reader finding `payout_ledger.reference` would
reasonably assume a row carries its own reference and write code that half-works. Nothing had ever
written them (Unit 16 was never built), so no data was at risk. Also in `V28`: `uq_payout_per_case`,
`payout_ledger.currency SET NOT NULL`, a `CHECK (amount IS NULL OR amount >= 0)` where **NULL stays
legal** because it means "not decided yet", and `brand.payout_term_days`.

**`brand.currency` is nullable and that is not an oversight — it is the one constraint this schema
could not have.** Flyway orders by version across *all* configured locations, so `V28` applies before
`db/seed-local/V900`, which inserts brands with no currency: a `NOT NULL` there fails on every fresh
database, which is what CI builds each run. `V900` cannot be edited (invariant 9) and
`MigrationTreeTest` forbids a ≥900 script under `db/migration`, so no later `SET NOT NULL` can exist.
`payout_ledger.currency NOT NULL` is what actually keeps a null out of the ledger, and
`PayoutService.openForDelivery` refuses a brand with no currency rather than guessing one.

**Unit 21** adds `document_checklist_item.object_key` + `uploaded_at` (**was `drive_file_id`** — Unit 30;
the S3 object behind an `UPLOADED`
item, and when it arrived). **No `uploaded_by`** — the audit trail records who, and a second record of
the same fact is a second thing that can disagree, the same reasoning that refused `paid_by`.

**Retention columns are now dead on purpose.** `evalos_case.retention_30/90/180/365_sent_at` were
"reserved for the jobs unit"; retention is **GHL's end to end** since Production Process v2.0, so
`RetentionSweep` is gone and these four are **permanently unwritten and get no accessors**. Left in
place because an applied migration is never edited — but do not adopt them for something else, and do
not read their existence as a plan.
**`google_review_requested` / `_at` are different**: Unit 18 does write them, on a successful Handoff C,
because "GHL was told" is EvalOS's own fact and Unit 17's review tile counts it.

Still-dead-and-should-stay-dead, for the same derive-don't-store reason as the three `expert` counters:
`expert.avg_response_hours`. Unit 17 derives turnaround from `expert_case_offer`; reviving the column
would be a second, staler answer.

**Case Creation v2.0 (Unit 05b)** added `V24` `evalos_case.ghl_opportunity_id` + the per-brand
`uq_case_open_per_opportunity`, so a re-fired GHL workflow cannot open a second case for one
opportunity — the `V15`/`V16` index-not-lookup rule again. It is partial on **`WHERE
ghl_opportunity_id IS NOT NULL AND current_stage <> 'CLOSED'`, and the second clause is
load-bearing**: the open-case lookup ignores closed cases, so a client returning on a re-used
opportunity id takes the *create* path, and an unscoped index would turn legitimate repeat business
into a constraint violation — a 5xx GHL retries forever, and no case for a deal that was paid for.
It guards a **different** thing from the gateway's `event_id` dedupe (that stops a redelivered
webhook; this stops a second case), and the id is **never** an idempotency key.
Same change made `case.paid`/`paid_at` (`V14`) **write-once by intake only**: the `mark-paid`
transition and endpoint are deleted, so nothing but `CaseIntakeService` ever writes them.
`deal_value` now holds the won opportunity's amount rather than a quote — and is the one field
`CaseIntakeService.refresh()` **overwrites** rather than fills, because deleting `markPaid` removed
its only other writer and the figure feeds revenue recognition.

**⚠ Unit 30 drops `drive_link` and keeps `draft_link` — and the reason is the distinction below.** A
client's documents become *derivable* from the contact the case already points at
(`client/{ghl_contact_id}/`), so storing a link to them is a second copy of a fact the schema holds.
A draft is **one file among several versions** and is not derivable, so `draft_link` survives — as an
S3 **object key**, not a URL. Same column, different kind of value; nothing may guess which.
The original distinction, still worth reading: `drive_link` was the client's **own document folder**
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
contact snapshot per person) and a third that `V28`'s `uq_payout_per_case` now closes (two payout
rows for one delivery, since `deliverToClient`'s `deliveryDate == null` guard has nothing behind it).

**A second house answer, for when the guard covers a *set* of rows: one conditional `UPDATE ...
WHERE <the precondition>` plus an affected-count assertion.** A partial unique index works when the
rule is "one row like this"; it has nothing to say about "these twelve rows must all still be
`PENDING` when I write them". `PayoutLedgerRepository.attachToPayment` is the worked example — its
`WHERE` carries `id in :ids AND brand_id = :brandId AND status = 'PENDING'`, it returns `int`, and
`PayoutService.settle` throws when that count is short, rolling back the payment insert with it. The
database decides once, and a short count means someone else won a row. Still no `@Version`, still no
explicit lock.

**Both races are proved in `LocalPostgresIntegrationTest`, and both tests interleave on purpose.**
Each asserts that the second statement *blocks* while the first transaction is open — a sequential
version would pass against code with no guard at all, because the second statement would simply see
the first one's committed result. The two failures look different and the difference is the point:
the delivery race ends in a unique-constraint violation, while the settlement race ends with the
loser's `UPDATE` **succeeding** and affecting one row instead of the two it named. Note the tests use
raw connections with hand-kept SQL — Spring's repository shares the pooled connection and its
transaction, so two of its calls cannot genuinely overlap — which means they pin the semantics
production relies on, not that production still writes them.

**The house answer is a partial unique index, not a lock and not a re-read.** `V15` and `V16` are the
worked examples: `(brand_id, contact_id, service_type) WHERE current_stage <> 'CLOSED'`, and
`(brand_id, ghl_contact_id)` / `(brand_id, lower(email))` where not null — **the email half narrowed
again by `V27`, see below**. Partial because the
exclusion is what makes the rule correct — a contact returning after their case closed is new
business, not a duplicate. The loser's transaction rolls back; for webhook paths that surfaces as a
retriable 5xx and the redelivery refreshes the committed row, which is what intake wanted anyway.

**`V27` demoted the email key to a fallback: `WHERE email IS NOT NULL AND ghl_contact_id IS NULL`.**
V16's version constrained every row with an email, which asserts "one email, one person" — true only
while EvalOS has no better identifier, and `ghl_contact_id` is a better identifier (invariant 7). Two
GHL contacts sharing a firm's office inbox are two clients; the old index refused the second, and what
happened instead was worse than a refusal — intake fell back to email, matched the *first* client's
row, could not backfill its own id over the one already there (`linkGhlContact` is write-once), and
attached a paid case to the wrong client while overwriting their name and phone. **A wrong merge is
worse than a duplicate: the duplicate is visible, the merge looks like an ordinary case.**
`CaseIntakeService.contradicts` is the code half — an email match is refused when both ids are present
and differ. Neither half works alone: the guard forces an insert the old index would have rejected.
The race V16 actually closed survives, because two concurrent id-less rows are both still in scope. It
also cleared a latent 5xx — a contact changing their GHL email to one an id-less row already held used
to fail the sync on this constraint.

`lower(email)` in the index because the lookup is `findByBrandIdAndEmailIgnoreCase` — index expression
and finder must agree or the index does not apply. Note `V15`'s own comment claims the race "cannot
race" and is **wrong on disk**; it is applied, so it was corrected in `V16`'s header rather than
edited (invariant 9).

## `expert_case_offer` (`V19`, Unit 12) — append-only with exactly one mutable column

The one queryable record of an accept/decline; its whole purpose is to be **aggregated** into an
acceptance rate. It is not a second history — the audit trail still records each transition.

- Every column is `updatable = false` **except `outcome`**, which leaves `OFFERED` exactly once
  through `ExpertCaseOffer.resolve`. **First write wins; a later or repeated outcome is a no-op, not
  an error** — Unit 15 has two acts that both mean accepted (the expert presses Accept, then uploads
  the signed letter) and both fire on the happy path, so throwing would fail a normal
  sequence. That reasoning predates dropping the signature provider and survives it unchanged: the
  second act used to be a `signed` callback and is now the expert's own upload. The guard is on the entity, the one place owning the column, not in the four callers.
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
read scoped, so it carries a javadoc convention and a DB-gated brand-isolation test. Since
2026-08-06 it is the *only* finder still resting on that convention — narrowing it needs the counts
split per brand first, because one expert is reachable from several brands' cases. A DB-gated test also pins an expert with two open cases reporting a load of **2 while
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
- **The two batch reads now narrow themselves** (2026-08-06). They used to take ids alone and rest
  on a javadoc reading *do not call with ids that came from a request*; a convention is not a scope,
  and the thing standing between two brands should not be whether the next caller reads a comment.
  Both take the brands as well, and callers pass the distinct brands of the rows the scoped read
  returned — never a request parameter, which is null for the GM and would scope nothing.
  - `DocumentChecklistItemRepository.findByBrandIdInAndCaseIdIn` — derived query, `brand_id` on the
    item is non-null.
  - `AuditEventRepository.findCaseActionScoped` — native, joining `evalos_case` and filtering on the
    **case's** `brand_id`. It cannot filter on `audit_event.brand_id`: that column is nullable by
    design and is stamped from `TenantContext`, so every action the GM takes carries null and would
    vanish from the result. Native rather than JPQL because the entity name `Case` collides with the
    JPQL `CASE` keyword, the same reason `countCasesPerExpert` is native.
  - Only `CaseRepository.countCasesPerExpert` still has the old shape. Any new aggregate of that
    shape owes a brand predicate, not a comment.

## Append-only audit

**It is also the case-notes store (Unit 23).** `AuditAction.NOTE_ADDED` carries a staff note in the
snapshot's `note`, and there is deliberately **no `case_note` table** — the trail is already
append-only, brand-scoped, actor-resolving and interleaved with the transitions a note is usually
about. Everything below therefore applies to notes as well, and the sharpest consequence is that
**a note can never be edited or withdrawn by anyone.** That is the property being bought, not a
limitation to work around.

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
  a DTO, outbound webhook payload, or log line. It is **currently** the only encrypted field — payouts
  are manual, so there is no card or bank data anywhere else.
- **"Only" is no longer a rule, it is a fact about today (signed off 2026-08-26).** A second encrypted
  column is permitted, and there is exactly one approved way to add it: extract the AES-GCM into a
  shared `common/EncryptedStringConverter` and leave `PaymentDetailConverter` a thin subclass — one
  crypto implementation, one key, expert path unchanged. That is a **named, narrow exception** to the
  protected-file rule and nothing broader. Unit 25's GHL refresh token is the column that will use it;
  **the extraction is not written until that unit is built.** The rule that did not move: a credential
  that never has to be replayed is **hashed, not encrypted** (portal tokens) — encryption is only for
  what must be recovered, which is precisely why a refresh token cannot be hashed.
- **Write-only since Unit 11, which gave it its first screen.** `PUT /api/experts/{id}/payment-detail`
  sets it and **nothing reads it back** — no endpoint, no service method, not for the ENM who typed
  it. No DTO declares the field (not blanked, not masked: *not a member*), the audit snapshot records
  only that it was set, and the sheet import refuses a mapping naming it. Screens get
  `Expert.hasPaymentDetail()`. `ExpertControllerTest` walks every expert route with the field
  populated and greps each serialized body; it asserts on `"paymentDetail"` **quoted**, because
  `paymentDetailOnFile` is a legitimate member.
