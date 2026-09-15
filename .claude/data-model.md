# EvalOS — Data Model

## CURRENT DATABASE

Verified 2026-09-16 against the live local Postgres 18 database `evalos` (`pg_dump --schema-only`
plus `pg_constraint` / `pg_indexes`). **Flyway V1–V50 all applied, `success = true`.** (`V50__pipeline_mirror.sql` is Unit 44a,
added 2026-09-16 and verified by `LocalPostgresIntegrationTest`.) Migrations
live in `backend/src/main/resources/db/migration/`.

> No production database was reachable from this workspace. Everything below is the schema the
> migrations produce, confirmed against a real applied instance. Row counts cited anywhere are the
> **local seeded** database and say nothing about production.

### Tables (26, including `flyway_schema_history`)

| Table | Purpose | Brand-scoped |
|---|---|---|
| `brand` | tenant; webhook endpoint token, GHL webhook secret, currency, payout terms | — |
| `team_member` | staff login, role, optional `ghl_pipeline_id` / `segment` | yes (nullable for GM) |
| `client_account` | **portal identity**: email, password_hash, ghl_contact_id, name, phone | yes |
| `client_credential_token` | single-use SET / RESET password links | yes |
| `client_application` | **the client's request**: service, purpose, answers (jsonb), status, ghl_opportunity_id | yes |
| `contact_snapshot` | CRM snapshot a case hangs off; utm / source fields | yes |
| `evalos_case` | the production case, 60 columns | yes |
| `case_document` | DRAFT / CLIENT_UPLOAD / SIGNED_LETTER, versioned, S3 `object_key` | yes |
| `document_checklist_item` | what the client still owes, per case | yes |
| `expert` | expert roster, 50 columns incl. taxonomy arrays and encrypted `payment_detail` | yes |
| `expert_case_offer` | offer to ACCEPTED / DECLINED / TIMED_OUT / SUPERSEDED | yes |
| `payout_ledger` | one row per case, links to a payment | yes |
| `payout_payment` | one transfer | yes |
| `portal_access` | opaque tokens for CLIENT / EXPERT, case- or party-scoped | yes |
| `opportunity_note` | staff prose against a GHL opportunity — **append-only trigger** | yes |
| `pipeline` | **mirror of a GHL pipeline** (Unit 44a): `ghl_id` verbatim, `name`, `position`, `purpose`, `synced_at`, `missing_since`. Upserted, never deleted | yes |
| `pipeline_stage` | **mirror of a GHL stage** (Unit 44a): FK to `pipeline`, `ghl_id` verbatim (mutable — see below), natural key `(pipeline_id, position, name)` | yes |
| `ghl_opportunity_cache` | droppable mirror of GHL opportunity fields — **superseded by `opportunity` at slice 44d**, not yet removed | **no** |
| `ghl_funnel_cache` | **orphaned 2026-09-16** — its only reader went with the funnel screens; the drop has nowhere to live (see `52`/`51` notes) | **no** |
| `meeting` | mirror of a GHL appointment booked from the Sales desk | yes |
| `follow_up` | mirror of a GHL contact task | yes |
| `notification` | in-app notification to a team member | yes |
| `audit_event` | **append-only trigger**; before/after jsonb, actor and actor_type | yes (nullable) |
| `webhook_event` | every inbound webhook, raw payload, processed flag | yes (nullable) |
| `scheduled_job` | one row per sweep run: RUNNING / OK / FAILED, items seen and acted | **no** |

### Relationships that matter

```
brand ─┬─ team_member ─┬─ reports_to → team_member
       │               └─ evalos_case.assigned_{pm,cm,coordinator}
       ├─ client_account ─┬─ client_credential_token
       │                  ├─ client_application ── ghl_opportunity_id (text, no FK)
       │                  └─ portal_access.client_account_id
       ├─ contact_snapshot ── evalos_case.contact_id
       ├─ expert ─┬─ evalos_case.expert_id
       │          ├─ expert_case_offer
       │          └─ payout_payment
       └─ evalos_case ─┬─ case_document
                       ├─ document_checklist_item
                       ├─ payout_ledger ── payout_payment
                       ├─ portal_access.case_id
                       └─ ghl_opportunity_id (text, no FK)
```

**`client_account` and `contact_snapshot` are not joined.** Both can hold a `ghl_contact_id` and
nothing links them. A case reaches a contact snapshot; it does not reach the account.

**`client_application` has no document relationship.** `case_document.case_id` is `NOT NULL` and
FKs to `evalos_case`. No table, column or route attaches a file to a request.

### Enumerations (CHECK constraints or Java enums over `text` — no Postgres enum types)

| Field | Values |
|---|---|
| `team_member.role` | GM, BRAND_MANAGER, PROJECT_MANAGER, PROJECT_COORDINATOR, CASE_MANAGER, EXPERT_NETWORK_MANAGER, SALES, MARKETING |
| `team_member.segment` | ATTORNEY, EMPLOYER_FIRM, INDIVIDUAL (required iff SALES/MARKETING) |
| `evalos_case.current_stage` | the 12 stages (see `architecture.md`) |
| `evalos_case.exception_state` | NONE plus hold / refund states |
| `client_application.status` | **DRAFT, SUBMITTED — only two** |
| `client_credential_token.purpose` | SET, RESET |
| `case_document.kind` | DRAFT, CLIENT_UPLOAD, SIGNED_LETTER |
| `case_document.status` | SUBMITTED, RETURNED, PM_APPROVED, CLIENT_APPROVED, SIGNED, SUPERSEDED |
| `case_document.uploaded_by_type` | STAFF, CLIENT, EXPERT, SYSTEM |
| `portal_access.audience` | CLIENT, EXPERT |
| `expert_case_offer.outcome` | OFFERED, ACCEPTED, DECLINED, TIMED_OUT, SUPERSEDED |
| `scheduled_job.status` | RUNNING, OK, FAILED |
| `expert.*` arrays | 39 primary/secondary fields, 7 letter types, 11 visa categories, 5 affiliation types |

### Uniqueness worth knowing

| Index | Rule |
|---|---|
| `client_account_brand_email_key` | one account per brand per lower(email) |
| `client_application_one_draft_idx` | **one DRAFT application per client account** |
| `uq_case_open_per_opportunity` | one non-CLOSED case per brand per GHL opportunity |
| `uq_case_open_per_contact_service` | one non-CLOSED case per brand / contact / service |
| `uq_contact_per_brand_ghl_id`, `uq_contact_per_brand_email` | ghl id where present; email only as fallback |
| `uq_portal_access_*` (four) | one unrevoked token per case+audience, per client party, per expert party, per account |
| `uq_team_member_pipeline` | one active member per GHL pipeline — **replaced by `team_member_pipeline` at slice 44b**, which is many-to-many because Case Delivery has no single owner |
| `uq_pipeline_per_brand_ghl_id`, `uq_pipeline_stage_per_brand_ghl_id` | GHL's id, unique per brand rather than globally: two brands will hold two locations and ids are only unique within one |
| `uq_webhook_event_source_brand_external` | idempotency, `NULLS NOT DISTINCT` |
| `uq_payout_per_case` | one non-VOIDED payout per case |
| `uq_case_document_version` | (case, kind, version) |

### Notable nullability

`client_account.ghl_contact_id` is **nullable and not unique** — post-cutover clients may have
none, and nothing prevents two accounts naming one contact.
`client_application.ghl_opportunity_id` is nullable, so a draft survives a GHL outage.

---

## REQUIRED FUTURE MODEL

Not present today. Do not write code that assumes any of it exists.

### From the target request lifecycle (this reset's business model)

| Needed | Why | Nearest thing today |
|---|---|---|
| **Request documents** — a table, or a nullable `client_application_id` on a rebuilt document table, plus routes and an S3 prefix | the target flow submits documents *with* the request, before a case exists | `case_document.case_id NOT NULL` |
| **A join from `client_application` to the case it became** | nothing records that a request turned into a case | both hold `ghl_opportunity_id` as text, unjoined |
| **A richer `client_application.status`** | two values cannot express Sales review, approval or rejection | `DRAFT` / `SUBMITTED` |
| **`client_account` merged with or joined to `contact_snapshot`** | one person is two rows with no link | both hold `ghl_contact_id` |
| **Unique `ghl_contact_id` per brand on `client_account`** | D6 (one contact, many opportunities) is not enforced by the schema | nullable, non-unique |

### From the mirror programme (Units 44–48, `context/specs/00c-ghl-independence-programme.md`)

**`pipeline` and `pipeline_stage` are BUILT** — slice 44a, `V50`, 2026-09-16. They are in CURRENT
above. What is left:

```sql
team_member_pipeline (team_member_id, pipeline_id)   -- 44b, replaces team_member.ghl_pipeline_id
contact              (id uuid pk, brand_id, ghl_id unique null, name, email, phone, ...)  -- 44c
opportunity          (id uuid pk, brand_id, ghl_id unique null, contact_id, pipeline_id, stage_id,
                      name, amount, status, ghl_updated_at, local_updated_at, sync_state)  -- 44d
outbox               (partial-unique on entity_id, not payload)   -- 45
sync_drift           (the reported mismatches)                    -- 45
```

Slice 44d replaces `ghl_opportunity_cache` with `opportunity` and carries the **correlation custom
field** — `00d` §6.1 pulls that one tier-2 item forward into Unit 44, because at-least-once outbox
delivery over a non-idempotent create is how one opportunity becomes two. Slice 44c merges
`contact_snapshot` and `client_account`. The rest of tier 2 and all of tier 3 follow in Unit 47,
scoped to "what 46 reads" rather than to completeness (`00d` §6.6).

Slice order and the reasoning behind it: `context/specs/44-ghl-tier1-mirror.md`.

### From other approved-but-unbuilt work

- `expert_application` plus recruitment stages — Unit 50 (ENM as a function).
- Expert accounts on the Unit 42 pattern — no table exists.
- Conversations, any channel — **no table, no column, no code anywhere.**
- Outbound webhook queue — invariant 11 describes it; nothing implements it.
