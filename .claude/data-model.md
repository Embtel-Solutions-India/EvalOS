# EvalOS — Data Model

## CURRENT DATABASE

Schema migrations **`V1`–`V98`** (there is **no `V71`** — it was held and never written), live in
`backend/src/main/resources/db/migration/`; re-derived from the migrations 2026-10-30 (V1–V65 were
last checked against a live local Postgres on 2026-09-16). Landmarks: `V50`–`V55` Unit 44, `V56`–`V58`
Unit 45, `V59`–`V62` Units 46–47b, `V63`/`V64` the 2026-09-18 review pass, `V65` added
`application_document` and **`V78` (Unit 64) dropped it and `client_application`**, retired `INTAKE`
and added `created_via = 'CASE'`.

**Seed trees** (siblings of `db/migration`, never inside it — `MigrationTreeTest`): `db/seed-local`
`V900`–`V916` (no `V906`; local profile), `db/seed-testprod` `V950`–`V954`, `db/seed-prod` `V960`
(the six IE desk logins) and `V961` (the production Admin, brand NULL).

> No production database was reachable from this workspace. Everything below is the schema the
> migrations produce, confirmed against a real applied instance. Row counts cited anywhere are the
> **local seeded** database and say nothing about production.

### Tables (47, plus `flyway_schema_history`)

| Table | Purpose | Brand-scoped |
|---|---|---|
| `brand` | tenant; webhook endpoint token, GHL webhook secret, currency, payout terms. **Name, currency and payout term are editable by the Administrator** (D83, `Brand.update`, audited `BRAND`); slug, token and secret are not | — |
| `app_setting` (`V90`, D83) | the Administrator's saved settings: `key` (a `Setting` enum name), `value` (AES-GCM ciphertext for a secret), `updated_at`, `updated_by`. **No row = the env var applies**; reset deletes the row. Deployment-wide, no `brand_id` (configures the server, not a brand's rows). History is the audit trail (`object_type = 'SETTING'`, secrets as set/cleared) | — |
| `team_member` | staff login, role; `segment` is **VESTIGIAL** since `V91` (D21: unmapped, always NULL, no CHECK — kept because applied seeds insert into it); `ghl_pipeline_id` is **VESTIGIAL** as of 44b and, since `V84` (Unit 68), **no longer required** for SALES/MARKETING (still forbidden for every other role); **`ghl_user_id`** (`V74`, Unit 60): the member's GHL user, unique where set, linked by email on `REFERENCE_MIRROR`; **`team_id` VESTIGIAL** since 2026-10-29 (D20: no teams); **`archived_at`** (`V98`, 2026-10-29): set when the Admin deletes a member with history — never active again, hidden from the Staff list, row kept for history | yes (nullable for GM) |
| `team_member_pipeline` | **which pipelines a member may work** (44b, `V54`): FK to `pipeline`, many-to-many, `granted_at`/`granted_by`, `revoked_at` (`V64`). **A revoke stamps, never deletes** — the row is what stops `backfillFromLegacyColumn` (`ON CONFLICT DO NOTHING`) re-creating the grant from a still-filled `team_member.ghl_pipeline_id` (no longer *required* since `V84`). Every read filters `revoked_at IS NULL`. **Not history:** `grant` on a revoked row is `ON CONFLICT DO UPDATE`, overwriting `granted_at`/`granted_by` and clearing `revoked_at` — the earlier grant/revoke survives only in `audit_event`. **No `brand_id` column**; the per-member and per-pipeline reads key on ids alone (`salesGhlIds` is the one that joins the brand) | **no** — via the member |
| `client_account` | **portal identity**: email, password_hash, ghl_contact_id, `contact_id` (`V55` — FK to the CRM row), name, phone, `created_via` (`V59` — SEED / SIGNUP / STAFF, **CASE** since `V78`: opened when the client's case was created, Unit 64; the only thing `PORTAL_CLEANUP` is allowed to delete on) | yes |
| `client_credential_token` | single-use SET / RESET password links | yes |
| `expert_account` | **the expert's portal sign-in** (`V72`, Unit 59): `expert_id` unique → `expert`, `password_hash`, `created_at`, `last_sign_in_at`; `terms_accepted_at` / `terms_version` (`V83`, D71 — `client_account` has the same two) | yes |
| `expert_credential_token` | single-use SET / RESET links for an `expert_account` (`V72`): `token_hash` unique, `purpose`, `expires_at`, `used_at` | yes |
| `contact_snapshot` | CRM snapshot a case hangs off; utm / source fields; `country`, `tags text[]`, `custom_fields jsonb` keyed by GHL field id (`V81`, shown on the deal screen) | yes |
| `conversations` | **case chat** (Unit 57, `V69`): three per case (`CLIENT`/`INTERNAL`/`EXPERT`, `UNIQUE (case_id, type)`), `ACTIVE`/`READ_ONLY` (at `CLOSED`), `last_message_at` | yes |
| `conversation_members` | **membership history**: kind (`STAFF`/`CLIENT`/`EXPERT`) + member id + role label, `created_at` = joined, `left_at` + `left_reason`. **A trigger refuses DELETE** and allows an UPDATE only to stamp `left_at` once; one current row per person (partial unique index) | yes |
| `messages` | text only (≤4,000), `parent_message_id` (replies one level), `edited_at`, `deleted_at` (body cleared, original in `audit_event`), generated `search` tsvector (GIN) | yes |
| `message_reactions` | one of six reactions per person per message; un-reacting deletes the row | yes |
| `message_reads` | one read watermark per member per conversation, moved forward only; drives unread counts and "seen by" | yes |
| `push_subscriptions` | web push (D37): one row per browser (`endpoint` unique), deleted on 404/410 | yes |
| `push_outbox` | **a push owed a retry** (`V92`, 2026-10-15): `subscription_id` → `push_subscriptions` ON DELETE CASCADE, `payload`, `retries`, `due_at`. A work queue, not history: deleted when the push settles (sent, refused, browser gone, out of retries). Drained by `PUSH_OUTBOX` (1m), which reads every brand's due rows — a system sweep, like `findActiveForSweep` | yes |
| `evalos_case` | the production case, 60 columns; **`team_id` VESTIGIAL** since 2026-10-29 (D20: no teams; nothing writes or reads it) | yes |
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
| `pipeline` | **mirror of a GHL pipeline** (Unit 44a): `ghl_id` verbatim, `name`, `position`, `opportunities_synced_at` (`V61`, board staleness), `purpose` (MARKETING / SALES / DELIVERY / **EXPERT_HIRING** (`V76`, Unit 63 — the ENM's hiring pipeline; an ENM works it only when granted it in `team_member_pipeline`, per person — edited 2026-10-09) / **MASTER** (`V93`, 2026-10-15 — the GM's default board; at most one per brand, partial unique index; V93 tagged the pipeline the name rule matched) / UNASSIGNED), `synced_at`, `missing_since`. Upserted, never deleted | yes |
| `pipeline_stage` | **mirror of a GHL stage** (Unit 44a): FK to `pipeline`, `ghl_id` verbatim (mutable — see below), natural key `(pipeline_id, position, name)`. **`won`** (`V94`, 2026-10-15) is the one column EvalOS owns: moving a deal onto a won stage wins it (D70). Backfilled from the name rule, defaulted the same way for a new stage, never overwritten by the mirror; the GM sets it on the Pipelines screen (the Admin only sees it) | yes |
| `opportunity` | **mirror of a GHL opportunity** (Unit 44d, `V51`): EvalOS's `id` is also the GHL correlation key; `ghl_id` is null until GHL has seen the row; `ghl_stage_id` is text, not a FK, so one sweep being behind cannot fail another. `local_updated_at` says an edit is outstanding and `locally_edited_fields` (`V63`) says **which of the four shared fields it is about**, so a push sends only those; `ghl_base` (`V96`, jsonb, spec 86) holds each edited field's value **before** the first unconfirmed edit, so the push can tell "GHL did not touch it" from "GHL changed it too" — cleared with the edit flags. `custom_fields` (`V62`): jsonb keyed by GHL field id (D49). `ghl_contact_id` is text, no FK, and there is no `sync_state` (derivable from `ghl_id IS NULL`). Upserted, never deleted | yes |
| `ghl_custom_field`, `ghl_calendar`, `ghl_user` | **the location's reference lists** (`V60`, Unit 47), upserted on GHL's id (unique per brand), `synced_at`, never deleted (`missing_since`). `ghl_custom_field.model` holds `opportunity` and `contact` definitions side by side; readers filter by model. Prefixed `ghl_` because `user` is reserved. **No slots table** (D48) | yes |
| `ghl_note` | **GHL's own notes** (`V62`, Unit 47b), read-only mirror: `ghl_id` (unique per brand), `ghl_contact_id`, `ghl_opportunity_id` (null = the contact's note), title, body, GHL author and date, `synced_at`, `missing_since`. Never merged with `opportunity_note` | yes |
| `ghl_tag` | **the location's tag vocabulary** (`V62`): `ghl_id` (unique per brand), `name`, `synced_at`, `missing_since` | yes |
| `sales_monthly_goal` | **the GM's sales target per month** (`V77`, 2026-09-29): `brand_id` (the selling brand), `month` (the 1st, CHECKed), `amount` ≥ 0, `set_by` → `team_member`, `set_at`. **Append-only by convention only — no trigger** (unlike `member_monthly_target`): a change is a new row and the newest per (brand, month) counts; `SALES_MONTHLY_GOAL` is the fallback | yes |
| `member_monthly_target` | **a Sales or Marketing member's target per month** (`V86`, 2026-10-08, D75): `brand_id`, `team_member_id`, `month` (the 1st, CHECKed), `kind` (`WON_VALUE` for Sales, `LEADS` for Marketing, from the member's role), `amount` ≥ 0, `set_by` → `team_member`, `set_at`. **Append-only, enforced** (`V87` trigger: an UPDATE or DELETE raises) — a change is a new row and the newest per (member, month) counts; no row means *not set*, which is not 0 | yes |
| `ghl_funnel_cache` | **orphaned 2026-09-16** — its only reader went with the funnel screens; no migration drops it | **no** |
| `meeting` | mirror of a GHL appointment booked from the Sales desk | yes |
| `follow_up` | mirror of a GHL contact task | yes |
| `notification` | in-app notification to a team member | yes |
| `audit_event` | **append-only trigger**; before/after jsonb, actor and actor_type | yes (nullable) |
| `webhook_event` | every inbound webhook, raw payload, processed flag | yes (nullable) |
| `sync_outbox` | **durable EvalOS→GHL pushes** (45c, `V57`/`V58`): `entity_id` never a payload, coarse `intent`, partial-unique while pending, dead rows kept; `retried_at` / `retried_by` (`V95`, 2026-10-28) stamp a dead row an Administrator re-queued — the retry is a new row, the dead one stays dead (CHECK: retried only when dead) | yes |
| `sync_drift` | **divergences between EvalOS and GHL** (45b, `V56`): one OPEN row per disagreement, `first_detected_at` / `last_seen_at`, resolved rows kept as history | yes |
| `scheduled_job` | one row per sweep run: RUNNING / OK / FAILED, items seen and acted; indexed on `started_at` (`V97`) | **no** |

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
| `uq_portal_access_one_unrevoked_{case,client_party,expert_party}` (`V38`), `portal_access_one_live_per_account` (`V44`) | one unrevoked token per case+audience, per client party, per expert party, per account |
| `uq_portal_access_token_hash` (`V21`) | a token hash is unique — a different rule from the four above |
| `uq_team_member_pipeline` | **VESTIGIAL** — `team_member_pipeline` is the authority as of 44b. Kept only because seeds `V908`/`V909` write the column it guards and a DROP cannot be ordered after them |
| `team_member_pipeline` PK `(team_member_id, pipeline_id)` | One row per pair, revoked or live. `grant` is `ON CONFLICT ... DO UPDATE` (only when revoked) clearing `revoked_at` and overwriting `granted_at`/`granted_by`, so re-granting revives the row rather than colliding with it; `backfillFromLegacyColumn`'s `DO NOTHING` relies on the same row being present |
| `uq_pipeline_per_brand_ghl_id`, `uq_pipeline_stage_per_brand_ghl_id` | GHL's id, unique per brand rather than globally: two brands will hold two locations and ids are only unique within one |
| `uq_sync_outbox_pending` | **PARTIAL** — `where sent_at is null and dead_at is null`. Pending pushes collapse; sent and dead ones are history and must not block the next edit |
| `uq_client_account_per_brand_ghl_contact` | **PARTIAL** — D6 enforced at last (`V55`): two accounts cannot claim one GHL contact, while many accounts with none can coexist (post-cutover clients have no contact) |
| `uq_opportunity_per_brand_ghl_id` | **PARTIAL** — `where ghl_id is not null`. Many local-only rows must coexist while every GHL id appears at most once; a plain unique would allow only one |
| `uq_webhook_event_source_brand_external` | idempotency, `NULLS NOT DISTINCT` |
| `uq_payout_per_case` | one non-VOIDED payout per case |
| `uq_case_document_version` | (case, kind, version) |

### Notable nullability

`client_account.ghl_contact_id` is **nullable, and unique per brand where set**
(`uq_client_account_per_brand_ghl_contact`, `V55`) — post-cutover clients may have none.

### Dead code worth knowing

`TeamMemberRepository.findPipelinesOfActiveMembers` has **no callers** (only Javadoc mentions it).

---

## REQUIRED FUTURE MODEL

Not present today. Do not write code that assumes any of it exists.

**Nothing approved is unbuilt.** The mirror programme (Units 44–47b), expert accounts (`V72`) and
case chat (`V69`) are all in CURRENT above. Open schema questions (e.g. Q27, brand-level mail
branding) live in `open-decisions.md` until decided.

- ~~`expert_application` plus recruitment stages (Unit 50)~~ — **replaced by D61 (Unit 63)**: hiring
  candidates are GHL opportunities on an `EXPERT_HIRING` pipeline; no candidate table.
- ~~Outbound webhook queue~~ — **dropped with Handoff C (D53)**; nothing will implement it.
