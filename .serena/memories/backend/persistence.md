# backend/ — Persistence, audit & field encryption

Built in Unit 03 (`V4`–`V10`). Entities: `ContactSnapshot`, `Case` (table **`evalos_case`** — `case`
is reserved SQL), `DocumentChecklistItem`, `Expert`, `PayoutLedger`, `Notification`, `AuditEvent`,
plus Unit 02's `Brand`/`TeamMember`.

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
- `text[]` → `String[]` with `@JdbcTypeCode(SqlTypes.ARRAY)`; `jsonb` → `String` with
  `SqlTypes.JSON`. Enum arrays are avoided — they buy nothing and risk `validate` mismatches.
- Contact snapshots: GHL is the only writer (invariant 7). Columns stay physically updatable so the
  `contact.updated` sync can refresh them; the rule is enforced by "only the sync writes", not by
  `updatable = false`.

## Scoped repositories

Extend `repository/ScopedRepository<T>` and declare a `SCOPE` constant + `scopeFields()` override
naming the entity attributes that carry brand / team / assignee. That yields `findScoped(ctx)` and
`findScoped(ctx, id)`, both built on `service/ScopePredicate` (`mem:backend/security`).

- Inherited `findAll()` / `findById()` are **not** scoped and cannot be removed — use `findScoped`
  for reads, and `OwnershipGuard` before writing a row obtained any other way.
- `Case` is the only type using all three axes (`brandId`, `teamId`, `assignedCm`); `Notification`
  scopes by `recipientId` as its assignee; contacts, checklist items, experts and payouts are
  brand-only (`recorded_by` names who typed the row, not who owns it).
- `DomainInvariantsTest` reflects over every `SCOPE` and fails if an attribute name is not a real
  mapped field — a typo there would otherwise surface as a runtime failure in the query that was
  supposed to keep two brands apart.

## Append-only audit

Enforced three independent ways, because lost audit history is unrecoverable:

1. `AuditEventRepository extends Repository` (the bare marker, **not** `JpaRepository`) — `save` plus
   two finders are the only methods that exist. Never add one that can update or delete.
2. Every `AuditEvent` column is mapped `updatable = false`, so Hibernate cannot emit an UPDATE.
3. A `BEFORE UPDATE OR DELETE` trigger in `V10` raises for every role, including the owner (a GRANT
   cannot do this: the app connects as the table owner, and an owner ignores REVOKE).

`AuditEvent` is deliberately **not** a `ScopedEntity` — `brand_id` is nullable for system events —
and its `created_at` is stamped by the database clock (`insertable = false`).

`service/AuditService.recordEvent(objectType, objectId, action, actorId, before, after)` is the only
writer. It joins the caller's transaction, so the trail commits with the change it describes or not at
all. Brand is derived from `TenantContext`, never a parameter. Snapshots are Jackson-serialized to
`jsonb`: pass DTOs or maps, not entities.

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
