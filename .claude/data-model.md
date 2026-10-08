# EvalOS — Data Model

## CURRENT DATABASE

Verified 2026-09-16 against the live local Postgres 18 database `evalos` (`pg_dump --schema-only`
plus `pg_constraint` / `pg_indexes`). **Flyway V1–V65 all applied, `success = true`.** (`V50`–`V55` are Unit 44's four slices, `V56` is
Unit 45b and `V57`/`V58` are 45c, added 2026-09-16 and verified by `LocalPostgresIntegrationTest`;
`V59`–`V62` are Units 46–47b; **`V63` and `V64` are the 2026-09-18 review pass** —
`opportunity.locally_edited_fields` and `team_member_pipeline.revoked_at`; **`V65` is Unit 53** — `application_document`; **`V78` is Unit 64** — it drops that table and `client_application`, retires `INTAKE`, and adds `created_via = 'CASE'`.) Migrations
live in `backend/src/main/resources/db/migration/`.

> No production database was reachable from this workspace. Everything below is the schema the
> migrations produce, confirmed against a real applied instance. Row counts cited anywhere are the
> **local seeded** database and say nothing about production.

### Tables (27, including `flyway_schema_history`)

| Table | Purpose | Brand-scoped |
|---|---|---|
| `brand` | tenant; webhook endpoint token, GHL webhook secret, currency, payout terms | — |
| `team_member` | staff login, role, `segment`; `ghl_pipeline_id` is **VESTIGIAL** as of 44b and, since `V84` (Unit 68), **no longer required** for SALES/MARKETING (still forbidden for every other role); **`ghl_user_id`** (`V74`, Unit 60): the member's GHL user, unique where set, linked by email on `REFERENCE_MIRROR` | yes (nullable for GM) |
| `team_member_pipeline` | **which pipelines a member may work** (44b, `V54`): FK to `pipeline`, many-to-many, `granted_at`/`granted_by`, `revoked_at` (`V64`). **A revoke stamps, never deletes** — the row is what stops `backfillFromLegacyColumn` re-creating the grant from `team_member.ghl_pipeline_id`, which `V39` forbids emptying for SALES/MARKETING. Every read filters `revoked_at IS NULL` | via the member |
| `client_account` | **portal identity**: email, password_hash, ghl_contact_id, `contact_id` (`V55` — FK to the CRM row), name, phone, `created_via` (`V59` — SEED / SIGNUP / STAFF, **CASE** since `V78`: opened when the client's case was created, Unit 64; the only thing `PORTAL_CLEANUP` is allowed to delete on) | yes |
| `client_credential_token` | single-use SET / RESET password links | yes |
| `contact_snapshot` | CRM snapshot a case hangs off; utm / source fields; `country`, `tags text[]`, `custom_fields jsonb` keyed by GHL field id (`V81`, shown on the deal screen) | yes |
| `conversations` | **case chat** (Unit 57, `V69`): three per case (`CLIENT`/`INTERNAL`/`EXPERT`, `UNIQUE (case_id, type)`), `ACTIVE`/`READ_ONLY` (at `CLOSED`), `last_message_at` | yes |
| `conversation_members` | **membership history**: kind (`STAFF`/`CLIENT`/`EXPERT`) + member id + role label, `created_at` = joined, `left_at` + `left_reason`. **A trigger refuses DELETE** and allows an UPDATE only to stamp `left_at` once; one current row per person (partial unique index) | yes |
| `messages` | text only (≤4,000), `parent_message_id` (replies one level), `edited_at`, `deleted_at` (body cleared, original in `audit_event`), generated `search` tsvector (GIN) | yes |
| `message_reactions` | one of six reactions per person per message; un-reacting deletes the row | yes |
| `message_reads` | one read watermark per member per conversation, moved forward only; drives unread counts and "seen by" | yes |
| `push_subscriptions` | web push (D37): one row per browser (`endpoint` unique), deleted on 404/410 | yes |
| `evalos_case` | the production case, 60 columns | yes |
| `case_document` | DRAFT / CLIENT_UPLOAD / SIGNED_LETTER, versioned, S3 `object_key`. A DRAFT (`V70`, Unit 58) holds its Word file in `object_key`/`filename`/`size_bytes` and its PDF in `pdf_object_key`/`pdf_filename`/`pdf_size_bytes` | yes |
| `draft_comments` | **one comment on one draft version** (`V70`, Unit 58): `document_id` → `case_document`, `author_kind` STAFF/CLIENT, `author_id` (team member, or the client's portal credential), body 1–2,000, `page` ≥ 1 or null. **A trigger refuses UPDATE and DELETE** | yes |
| `case_client_remark` | **what staff tell the client about a case** (`V85`, Unit 76, D74): `case_id`, `author_id`, `body` 1–2,000, `created_at`. Shown under the status the case was in when written. **A trigger refuses UPDATE and DELETE.** Internal notes are other tables and never reach the portal | yes |
| `document_checklist_item` | what the client still owes, per case; **`sent_at`/`sent_by`** (`V75`, Unit 61): null = unsent, not in the portal; `sent_by` null on a sent row = sent before D60 | yes |
| `expert` | expert roster, 50 columns incl. taxonomy arrays and encrypted `payment_detail`; **`credentials_verified_at`** (`V76`, Unit 63): when the ENM checked the credentials, null = not yet (who is on the `CREDENTIALS_VERIFIED` audit row) | yes |
| `expert_case_offer` | offer to ACCEPTED / DECLINED / TIMED_OUT / SUPERSEDED; **`note`** (`V82`, D69: the PM's note to the expert, required on every offer since, null only before); **`fee` / `fee_set_by` / `fee_set_at`** (`V79`, Unit 65): what the case pays the expert, editable only while `OFFERED`, nullable only for offers closed before V79 (open and accepted ones were backfilled from the payout, else `expert.standard_fee`; `V914` / `V953` do the same for the seed trees). Delivery opens `payout_ledger.amount` from the accepted offer's fee. **`version`** (`V80`): optimistic lock — staff edit the fee and the expert answers on the same row | yes |
| `payout_ledger` | one row per case, links to a payment | yes |
| `payout_payment` | one transfer | yes |
| `portal_access` | opaque tokens for CLIENT / EXPERT, case- or party-scoped | yes |
| `opportunity_note` | staff prose against a GHL opportunity — **`handoff`** (`V82`, D70: the note written with a win in EvalOS, read by the case page) — **its author may overwrite or hard-delete it** (`V67`, Unit 54a: trigger dropped, `updated_at` added; was append-only until 2026-09-24) | yes |
| `opportunity_note_ghl_link` | **the GHL note an `opportunity_note` became** (Unit 54, `V66`): `note_id` PK, `brand_id`, `ghl_note_id` unique per brand — **null on a delete marker** (`V68`: the note was deleted before its push learned the id, so the drain searches the contact for its reference) — `ghl_contact_id` (`V67`), `linked_at`. **No FK and no trigger since `V67`**: it outlives a deleted note until the drain has deleted the GHL copy, then the drain deletes it. `sync_outbox.entity_type` gained `OPPORTUNITY_NOTE` with no migration (no CHECK). `ghl_note.ghl_opportunity_id` null now means "the contact's note" | yes |
| `pipeline` | **mirror of a GHL pipeline** (Unit 44a): `ghl_id` verbatim, `name`, `position`, `purpose` (MARKETING / SALES / DELIVERY / **EXPERT_HIRING** (`V76`, Unit 63 — the ENM's hiring pipeline; every ENM of its brand works it) / UNASSIGNED), `synced_at`, `missing_since`. Upserted, never deleted | yes |
| `pipeline_stage` | **mirror of a GHL stage** (Unit 44a): FK to `pipeline`, `ghl_id` verbatim (mutable — see below), natural key `(pipeline_id, position, name)` | yes |
| `opportunity` | **mirror of a GHL opportunity** (Unit 44d, `V51`): EvalOS's `id` is also the GHL correlation key; `ghl_id` is null until GHL has seen the row; `ghl_stage_id` is text, not a FK, so one sweep being behind cannot fail another. `local_updated_at` says an edit is outstanding and `locally_edited_fields` (`V63`) says **which of the four shared fields it is about**, so a push sends only those. Upserted, never deleted | yes |
| `sales_monthly_goal` | **the GM's sales target per month** (`V77`, 2026-09-29): `brand_id` (the selling brand), `month` (the 1st, CHECKed), `amount` ≥ 0, `set_by` → `team_member`, `set_at`. **Append-only by convention** — a change is a new row and the newest per (brand, month) counts; `SALES_MONTHLY_GOAL` is the fallback | yes |
| `member_monthly_target` | **a Sales or Marketing member's target per month** (`V86`, 2026-10-08, D75): `brand_id`, `team_member_id`, `month` (the 1st, CHECKed), `kind` (`WON_VALUE` for Sales, `LEADS` for Marketing, from the member's role), `amount` ≥ 0, `set_by` → `team_member`, `set_at`. **Append-only, enforced** (`V87` trigger: an UPDATE or DELETE raises) — a change is a new row and the newest per (member, month) counts; no row means *not set*, which is not 0 | yes |
| `ghl_funnel_cache` | **orphaned 2026-09-16** — its only reader went with the funnel screens; the drop has nowhere to live (see `52`/`51` notes) | **no** |
| `meeting` | mirror of a GHL appointment booked from the Sales desk | yes |
| `follow_up` | mirror of a GHL contact task | yes |
| `notification` | in-app notification to a team member | yes |
| `audit_event` | **append-only trigger**; before/after jsonb, actor and actor_type | yes (nullable) |
| `webhook_event` | every inbound webhook, raw payload, processed flag | yes (nullable) |
| `sync_outbox` | **durable EvalOS→GHL pushes** (45c, `V57`/`V58`): `entity_id` never a payload, coarse `intent`, partial-unique while pending, dead rows kept | yes |
| `sync_drift` | **divergences between EvalOS and GHL** (45b, `V56`): one OPEN row per disagreement, `first_detected_at` / `last_seen_at`, resolved rows kept as history | yes |
| `scheduled_job` | one row per sweep run: RUNNING / OK / FAILED, items seen and acted | **no** |

### Relationships that matter

```
brand ─┬─ team_member ─┬─ reports_to → team_member
       │               └─ evalos_case.assigned_{pm,cm,coordinator}
       ├─ client_account ─┬─ client_credential_token
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

**`client_account` and `contact_snapshot` are joined as of Unit 44c** (`V55`): `contact_id`, a real
foreign key, backfilled on `ghl_contact_id` within the brand and set at sign-up. Null is legal — a
client may sign up before EvalOS has any other trace of them, and a wrong link would attach
somebody's cases to the wrong sign-in. **`contact_snapshot` keeps its name** only because two seeds
write it and a rename cannot be ordered after them; it is the mirror's contact table.

**`ghl_opportunity_cache` is GONE** (`V52`, 2026-09-16). `00d` §6.5's five reasons; the fifth — its
only write path was delete-all-then-insert-all per pipeline — is why it could not be altered into
`opportunity` and had to be replaced. `CachedOpportunity`, `CachedOpportunityRepository`,
`OpportunityCache` and `GhlOpportunityClient` went with it.

**There is no request table (Unit 64, `V78`).** Every client file is a `case_document`:
`case_id` is `NOT NULL` and FKs to `evalos_case`.

### Enumerations (CHECK constraints or Java enums over `text` — no Postgres enum types)

| Field | Values |
|---|---|
| `team_member.role` | GM, **ADMIN** (`V88`, D78; NULL brand like the GM), BRAND_MANAGER, PROJECT_MANAGER, PROJECT_COORDINATOR, CASE_MANAGER, EXPERT_NETWORK_MANAGER, SALES, MARKETING |
| `team_member.segment` | ATTORNEY, EMPLOYER_FIRM, INDIVIDUAL (required iff SALES/MARKETING) |
| `evalos_case.current_stage` | the 12 stages (see `architecture.md`) |
| `evalos_case.exception_state` | NONE plus hold / refund states |
| `client_credential_token.purpose` | SET, RESET |
| `case_document.kind` | DRAFT, CLIENT_UPLOAD, SIGNED_LETTER |
| `case_document.status` | SUBMITTED, RETURNED, PM_APPROVED, CLIENT_APPROVED, CHANGES_REQUESTED (`V70`), SIGNED, SUPERSEDED |
| `case_document.uploaded_by_type` | STAFF, CLIENT, EXPERT, SYSTEM |
| `portal_access.audience` | CLIENT, EXPERT |
| `expert_case_offer.outcome` | OFFERED, ACCEPTED, DECLINED, TIMED_OUT, SUPERSEDED |
| `scheduled_job.status` | RUNNING, OK, FAILED |
| `expert.*` arrays | 39 primary/secondary fields, 7 letter types, 11 visa categories, 5 affiliation types |

### Uniqueness worth knowing

| Index | Rule |
|---|---|
| `client_account_brand_email_key` | one account per brand per lower(email) |
| `uq_case_open_per_opportunity` | one non-CLOSED case per brand per GHL opportunity |
| `uq_case_open_per_contact_service` | one non-CLOSED case per brand / contact / service |
| `uq_contact_per_brand_ghl_id`, `uq_contact_per_brand_email` | ghl id where present; email only as fallback |
| `uq_portal_access_*` (four) | one unrevoked token per case+audience, per client party, per expert party, per account |
| `uq_team_member_pipeline` | **VESTIGIAL** — `team_member_pipeline` is the authority as of 44b. Kept only because seeds `V908`/`V909` write the column it guards and a DROP cannot be ordered after them |
| `team_member_pipeline` PK `(team_member_id, pipeline_id)` | One row per pair, revoked or live. `grant` is `ON CONFLICT ... DO UPDATE` clearing `revoked_at`, so re-granting revives the row rather than colliding with it; `backfillFromLegacyColumn`'s `DO NOTHING` relies on the same row being present |
| `uq_pipeline_per_brand_ghl_id`, `uq_pipeline_stage_per_brand_ghl_id` | GHL's id, unique per brand rather than globally: two brands will hold two locations and ids are only unique within one |
| `uq_sync_outbox_pending` | **PARTIAL** — `where sent_at is null and dead_at is null`. Pending pushes collapse; sent and dead ones are history and must not block the next edit |
| `uq_client_account_per_brand_ghl_contact` | **PARTIAL** — D6 enforced at last (`V55`): two accounts cannot claim one GHL contact, while many accounts with none can coexist (post-cutover clients have no contact) |
| `uq_opportunity_per_brand_ghl_id` | **PARTIAL** — `where ghl_id is not null`. Many local-only rows must coexist while every GHL id appears at most once; a plain unique would allow only one |
| `uq_webhook_event_source_brand_external` | idempotency, `NULLS NOT DISTINCT` |
| `uq_payout_per_case` | one non-VOIDED payout per case |
| `uq_case_document_version` | (case, kind, version) |

### Notable nullability

`client_account.ghl_contact_id` is **nullable and not unique** — post-cutover clients may have
none, and nothing prevents two accounts naming one contact.

---


**`expert_account`** (V72, Unit 59): `id`, `brand_id`, `expert_id` unique → `expert`, `password_hash`,
`created_at`, `last_sign_in_at`; **`terms_accepted_at`, `terms_version`** (`V83`, Unit 72 / D71 — the
policy version accepted; `client_account` has the same two). **`expert_credential_token`**: `id`, `brand_id`, `expert_account_id`,
`token_hash` unique, `purpose` SET|RESET, `expires_at`, `used_at`, `created_at` — single use.

## REQUIRED FUTURE MODEL

Not present today. Do not write code that assumes any of it exists.

### From the mirror programme (Units 44–48, `context/specs/00c-ghl-independence-programme.md`)

**Unit 44 is BUILT in full** — `V50`–`V55`,
2026-09-16. They are in CURRENT above. What is left:

`sync_drift` (45b, `V56`) and `sync_outbox` (45c, `V57`/`V58`) are **built** and are in CURRENT
above. Unit 45 has no schema left: 45d (webhooks + delta sweep) and 45e (per-field ownership) are
code over the tables that exist.

Two deviations from `00c` §2's sketch, both recorded in `44-ghl-tier1-mirror.md` §5:
**`opportunity` has no `sync_state` column** — today it is derivable (`ghl_id IS NULL`) and every
other value needs a writer that is Unit 45's — and **`contact_id` is still `ghl_contact_id` text**,
because 44c is where `contact` arrives. The rest of tier 2 and all of tier 3 follow in Unit 47,
scoped to "what 46 reads" rather than to completeness (`00d` §6.6).

Slice order and the reasoning behind it: `context/specs/44-ghl-tier1-mirror.md`.

### From Unit 47 (BUILT 2026-09-17) — in CURRENT above

`ghl_custom_field`, `ghl_calendar` and `ghl_user` (`V60`) mirror the location's reference lists on
GHL's own ids, stamped `synced_at`, never deleted (`missing_since` instead). Prefixed `ghl_` because
`user` is reserved in Postgres and `calendar` collides with vocabulary the app already uses; the
three are named consistently rather than one being the odd one out. **Free slots are not a table and
must not become one** (D48). **Custom field values are not columns** (D49): a `jsonb` map keyed by
field id on `opportunity` and, since `V81`, on `contact_snapshot`. `ghl_custom_field.model` holds
`opportunity` and `contact` definitions side by side; every reader filters by model.

### From other approved-but-unbuilt work

- ~~`expert_application` plus recruitment stages — Unit 50~~ — **replaced by D61 (Unit 63)**: hiring candidates are GHL opportunities on an `EXPERT_HIRING` pipeline, mirrored like every other; no candidate table.
- Expert accounts on the Unit 42 pattern — no table exists, **and none is designed until the
  stakeholder discussion happens** (Q6, D23).
- Conversations, any channel — **no table, no column, no code anywhere.**
- Outbound webhook queue — invariant 11 describes it; nothing implements it.
