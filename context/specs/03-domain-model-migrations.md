# Unit 03 — Domain model & migrations

**Phase:** 1 — Structure the data (the spine)
**Depends on:** 02
**Unlocks:** 04 (state machine), 05 (Handoff A), 11 (experts), 16 (payouts)
**Gating open questions:** none blocking; `payment_detail` encryption key comes
from env.

## Goal

The system-of-record schema: JPA entities, repositories, and Flyway migrations
for every remaining domain object, all brand-scoped and audit-ready, plus the
enum vocabulary, the field-level encryption converter for the one sensitive
field, and the append-only audit store. `Brand` and `TeamMember` already exist
from Unit 02.

**Verifiable result:** migrations apply cleanly on a fresh DB; the app starts
with `ddl-auto=validate` (entities match schema exactly); repositories load and
are brand-scoped; `Expert.payment_detail` round-trips encrypted and never
surfaces in a DTO/log; the audit repository exposes no update/delete.

## In scope

- Entities + migrations: `ContactSnapshot`, `Case`, `Expert`, `PayoutLedger`,
  `DocumentChecklistItem`, `Notification`, `AuditEvent`.
- Enums (below).
- Field-level encryption `AttributeConverter` for `Expert.payment_detail`.
- Append-only `AuditEvent` store + a `recordEvent(...)` helper.
- Brand-scoped repositories (using the Unit 02 scoping mechanism).
- Compound indexes.

## Out of scope

- Transition logic / SLA computation — Unit 04. (Unit 03 provides the audit
  entity + helper; Unit 04 calls it on each transition.)
- Webhook idempotency table (`WebhookEvent`) — Unit 05.
- `scheduled_job` table — Unit 19.
- Any endpoints, matching, or portal.

## Enums

- **Stage:** `DOC_COLLECTION · EXPERT_ASSIGNMENT · DRAFT_GENERATION ·
  EXPERT_SIGNING · FINAL_DELIVERY · CLOSED`
- **ExceptionState:** `NONE · ON_HOLD_AWAITING_CLIENT ·
  EXPERT_DECLINED_REMATCHING · REFUND_REQUESTED`
- **ServiceType:** `CREDENTIAL_EVALUATION · EXPERT_OPINION_LETTER · PERM ·
  RFE_RESPONSE · TRANSLATION`
- **ServiceSubtype** (for CREDENTIAL_EVALUATION): `COURSE_BY_COURSE ·
  EDUCATION_PLUS_EXPERIENCE · WORK_EXPERIENCE_ONLY`
- **VisaCategory:** `H1B · EB1A · EB2_NIW · O1 · TN · PERM · OTHER`
- **ClientType:** `ATTORNEY · EMPLOYER · INDIVIDUAL · AGENT`
- **SourceChannel:** `WEBSITE · GOOGLE_ADS · EMAIL_CAMPAIGN · LINKEDIN ·
  INSTAGRAM · FACEBOOK · WHATSAPP · REFERRAL · PARTNER`
- **PoolStatus:** `IN_POOL · ASSIGNED`
- **SlaStatus:** `ON_TRACK · AT_RISK · OVERDUE`
- **PmApprovalStatus:** `PENDING · APPROVED · RETURNED`
- **ClientApprovalStatus:** `PENDING · APPROVED · REVISION_REQUESTED`
- **ExpertSignStatus:** `PENDING · SIGNED · OVERDUE · REASSIGNED`
- **ChecklistItemStatus:** `REQUIRED · UPLOADED · APPROVED · MISSING · INCORRECT`
- **PayoutStatus:** `PENDING · PAID · CONFIRMED · VOIDED`
- **ExpertTier:** `TIER_1 · TIER_2 · TIER_3`
- **Availability:** `AVAILABLE · AT_CAPACITY · INACTIVE · ON_LEAVE`
- **AgreementStatus:** `SENT · SIGNED · EXPIRED`
- **ExpertPaymentStatus:** `UP_TO_DATE · PENDING · OVERDUE`
- **PerformanceFlag:** `SLOW_RESPONSE · QUALITY_ISSUE · DECLINED_CASES ·
  CLIENT_COMPLAINT`
- **NotificationType** and **AuditAction** as open string-backed enums.

## Data / schema

Every table below has `brand_id uuid NOT NULL FK→brand` (except where noted) and
timestamps. Audit is separate (see `audit_event`).

### `contact_snapshot` — read-only, synced from GHL (`V4`)
| column | type | notes |
| --- | --- | --- |
| id | uuid PK | |
| brand_id | uuid NOT NULL | |
| ghl_contact_id | text | source key |
| full_name / email / phone | text | |
| company | text | |
| client_type | text | ClientType |
| source_channel | text | SourceChannel |
| utm_source / utm_medium / utm_campaign | text | |
| date_first_captured | timestamptz | |
| synced_at | timestamptz | last sync |
No update path for synced fields (invariant 7).

### `evalos_case` (`V5`)
| column | type | notes |
| --- | --- | --- |
| id | uuid PK | |
| brand_id | uuid NOT NULL | |
| team_id | uuid | assigned team |
| case_code | text UNIQUE | human-facing id, generated on create |
| pool_status | text | PoolStatus |
| assigned_pm | uuid FK→team_member | nullable |
| assigned_cm | uuid FK→team_member | nullable |
| contact_id | uuid FK→contact_snapshot | |
| service_type / service_subtype | text | |
| visa_category | text | |
| client_type | text | |
| deal_value | numeric(12,2) | role-restricted in DTO (PM/BM/GM) |
| deadline | timestamptz | |
| current_stage | text NOT NULL | Stage |
| exception_state | text NOT NULL default 'NONE' | ExceptionState |
| stage_entered_at | timestamptz | |
| sla_status | text | SlaStatus (computed in Unit 04) |
| pm_strategy_notes | text | |
| expert_id | uuid FK→expert | nullable |
| expert_sign_status | text | ExpertSignStatus |
| draft_version_count | int default 0 | |
| pm_approval_status | text | PmApprovalStatus |
| client_approval_status | text | ClientApprovalStatus |
| client_portal_read_at | timestamptz | read receipt |
| drive_link | text | Google Drive folder |
| invoice_ref | text | from GHL |
| campaign_attribution | text | from GHL |
| delivery_date / case_closed_date | timestamptz | |
| google_review_requested | boolean default false | |
| google_review_requested_at | timestamptz | |
| retention_30/90/180/365_sent_at | timestamptz | log |
| created_at | timestamptz | |
Indexes: `(brand_id, team_id, assigned_cm, current_stage)`,
`(brand_id, deadline)`, `(brand_id, sla_status)`, `(brand_id, expert_id)`.

### `document_checklist_item` (`V6`)
| column | type | notes |
| --- | --- | --- |
| id | uuid PK | |
| brand_id | uuid NOT NULL | |
| case_id | uuid FK→evalos_case | |
| label | text | required document name |
| status | text | ChecklistItemStatus |
| updated_at | timestamptz | |
Index: `(brand_id, case_id)`.

### `expert` (`V7`) — brand-scoped roster
| column | type | notes |
| --- | --- | --- |
| id | uuid PK | |
| brand_id | uuid NOT NULL | |
| full_name / title / institution | text | |
| primary_fields / secondary_fields | text[] | taxonomy tags |
| availability | text | Availability |
| tier | text | ExpertTier |
| quality_score | numeric(3,1) | 1–10 |
| avg_response_hours | numeric | computed |
| total_cases_completed | int default 0 | |
| current_active_count | int default 0 | |
| agreement_status | text | AgreementStatus |
| payment_status | text | ExpertPaymentStatus |
| total_payments_pending | numeric(12,2) default 0 | |
| performance_flags | text[] | PerformanceFlag |
| recruitment_source | text | |
| date_onboarded | date | |
| notes | text | |
| **payment_detail** | text | **encrypted** via AttributeConverter; excluded from DTOs/logs |
Indexes: `(brand_id, availability)`, `(brand_id, tier)`.

### `payout_ledger` (`V8`)
| column | type | notes |
| --- | --- | --- |
| id | uuid PK | |
| brand_id | uuid NOT NULL | |
| case_id | uuid FK→evalos_case | |
| expert_id | uuid FK→expert | |
| amount | numeric(12,2) | |
| currency | text | |
| status | text | PayoutStatus (created PENDING in Unit 16) |
| method / reference | text | filled by the manual form |
| due_date / paid_date | timestamptz | |
| recorded_by | uuid FK→team_member | |
| created_at | timestamptz | |
Index: `(brand_id, status)`, `(brand_id, expert_id)`.

### `notification` — in-app, staff (`V9`)
| column | type | notes |
| --- | --- | --- |
| id | uuid PK | |
| brand_id | uuid NOT NULL | |
| recipient_id | uuid FK→team_member | |
| type | text | NotificationType |
| case_id | uuid | nullable ref |
| body | text | |
| read | boolean default false | |
| created_at | timestamptz | |
Index: `(brand_id, recipient_id, read)`.

### `audit_event` — global, append-only (`V10`)
| column | type | notes |
| --- | --- | --- |
| id | uuid PK | |
| brand_id | uuid | nullable (some system events) |
| object_type | text NOT NULL | e.g. CASE, EXPERT, PAYOUT |
| object_id | uuid NOT NULL | |
| action | text NOT NULL | AuditAction |
| actor_id | uuid | team_member or system |
| before_snapshot / after_snapshot | jsonb | optional |
| created_at | timestamptz NOT NULL default now() | |
Index: `(object_type, object_id)`, `(brand_id, created_at)`.
**Append-only:** the repository exposes `save` + `find*` only. No update/delete
method anywhere; enforce with a DB grant where possible.

## Deliverables

1. All entities + repositories + `V4`–`V10` migrations above.
2. All enums.
3. `PaymentDetailConverter` (`common`): JPA `AttributeConverter<String,String>`
   doing AES-256-GCM with a key from env (`EVALOS_FIELD_KEY`). Applied to
   `Expert.payment_detail`. The field is annotated so it is excluded from any DTO
   mapping and never logged.
4. `AuditEvent` entity + append-only repository + `AuditService.recordEvent(
   objectType, objectId, action, actor, before, after)` helper for Unit 04+.
5. Brand-scoped repository finders built on the Unit 02 `ScopePredicate`
   (e.g. `findScoped(...)` variants), so no repository returns cross-brand rows
   by default.
6. A JPA entity listener or base-class hook that stamps `created_at` and asserts
   `brand_id` non-null on persist for scoped entities.

## Acceptance criteria

- [x] Fresh DB: `V1`–`V10` apply in order with no error; a second startup is a
      no-op. (Verified on a fresh `evalos_unit03` database: 11 migrations applied
      including the local seed, then "Schema is up to date. No migration
      necessary." on the next boot.)
- [x] App starts with `ddl-auto=validate` — entities match the migrated schema
      exactly (no Hibernate mismatch). Covers `text[]` and `jsonb`.
- [x] Every scoped table has a NOT NULL `brand_id` and the specified indexes.
      (Confirmed in `information_schema` / `pg_indexes`; `audit_event` is the one
      nullable `brand_id`, by design.)
- [x] `Expert.payment_detail` is stored as ciphertext (verified in raw SQL), reads
      back as plaintext through the entity, and never appears in any DTO, log
      line, or `toString`. (`Expert` has no `toString`; the field and its getter
      are `@JsonIgnore`.)
- [x] The audit repository has no update/delete method; attempting a mutation to
      an existing audit row is not possible through the app. (Enforced three
      ways: `Repository` marker rather than `JpaRepository`, every column mapped
      `updatable = false`, and a `BEFORE UPDATE OR DELETE` trigger. Raw-SQL
      `UPDATE` and `DELETE` both raise.)
- [x] Repositories return only the caller's brand data when used with the Unit 02
      scope mechanism (two-brand test: a Brand Manager sees only their brand's
      expert, the GM sees both, and the by-id variant is scoped too).
- [x] `./mvnw verify` green — 37 tests, 31 run and 6 skipped (the DB-gated
      integration test; run it with `-Devalos.db.test=true`).

## Invariants honored

- Brand isolation on every scoped table (1); read-only synced contacts (7);
  `payment_detail` encrypted and never exposed (4); append-only audit on every
  object (13); Flyway-only schema, new migrations only (9); no object storage /
  no mail (14).

## Files touched (created)

`.../domain/{ContactSnapshot,Case,DocumentChecklistItem,Expert,PayoutLedger,
Notification,AuditEvent}.java` + the enum types; `.../repository/*Repository.java`;
`.../common/PaymentDetailConverter.java`; `.../service/AuditService.java`;
`db/migration/V4__contact_snapshot.sql` … `V10__audit_event.sql`.
