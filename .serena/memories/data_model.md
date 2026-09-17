# Data model

**The authoritative file is `.claude/data-model.md`. It separates CURRENT DATABASE from REQUIRED
FUTURE MODEL — never mix them.**

24 tables, Flyway V1 to V49 all applied (verified against a live Postgres instance on 2026-09-16).
Migrations live in `backend/src/main/resources/db/migration/`. New schema means a new migration; an
applied one is never edited.

Three facts that catch people out:

1. **`client_application` has no document relationship.** `case_document.case_id` is NOT NULL and
   FKs to `evalos_case`. Nothing attaches a file to a request.
2. **`client_account` and `contact_snapshot` are not joined.** Both can hold a `ghl_contact_id`;
   nothing links them. A case reaches the snapshot, not the account.
3. **`client_application.status` has two values only**: DRAFT and SUBMITTED. There is no review
   state.

`client_account.ghl_contact_id` is nullable and not unique.
`audit_event` and `opportunity_note` carry append-only triggers that raise on UPDATE and DELETE.

The mirror tables (`pipeline`, `pipeline_stage`, `contact`, `opportunity`, `outbox`, `sync_drift`)
do **not** exist — they are Units 44 to 48.

**Unit 44a added `pipeline` and `pipeline_stage` (`V50`, 2026-09-16)** — mirrors of GHL's own rows,
keyed on GHL's ids verbatim so a comparison is `a == b` rather than a translation. EvalOS mints its
own `id` beside `ghl_id` (`00c` §2a) because a portal-born row must exist before GHL has seen it.
Never deleted: `missing_since` is a soft delete, because `pipeline.purpose` is EvalOS's own
judgement. `pipeline_stage.ghl_id` is MUTABLE — GHL stage ids are not stable across a
delete-and-recreate, so the sweep repoints a row by `(pipeline_id, position, name)` rather than
inserting a second one.

**Unit 44d added `opportunity` (`V51`) and DROPPED `ghl_opportunity_cache` (`V52`)**, 2026-09-16.
EvalOS's `id` is stable from creation and IS the GHL correlation key; `ghl_id` is null until GHL has
seen the row, and its uniqueness index is PARTIAL (`where ghl_id is not null`) so many local-only
rows can coexist. `ghl_stage_id` is TEXT and deliberately not a FK into `pipeline_stage`: the two
mirrors run on two sweeps, and a FK would make an opportunity sync fail because a different sweep is
behind. `client_application.opportunity_id` (`V53`) persists the correlation key before GHL is
called — a key minted in memory and lost to a timeout is a key no retry can search for.

**Decided 2026-09-17 — what the future model now owes, and what it no longer does:**

- **`application_document`** (Unit 53, D33): request-stage uploads, `brand_id`,
  `client_application_id`, `contact_id` → `contact_snapshot`, `object_key`, and
  `carried_to_case_document_id` so Handoff A's carry-forward is idempotent. The S3 key is
  `DocumentStore.clientKey` — `{brand}/client/{ghl_contact_id}/{doc}` (D41, 2026-09-17; it was
  `contact_snapshot.id` for three days) — so the carry-forward is a row insert over the same object,
  never a copy or a re-key. `contact_id` stays a real FK: naming a contact by GHL's id does not
  change a primary key (D18).
- **A push-subscription table** (endpoint + keys per staff user) for D37. The `notification` table
  already records *what happened*; this is delivery only.
- ~~A richer `client_application.status`~~ — **NOT NEEDED (D35).** `DRAFT`/`SUBMITTED` are the right
  two; Sales review is a GHL pipeline stage, not an EvalOS column.

**Unit 47 (`V60`, BUILT 2026-09-17):** `ghl_custom_field`, `ghl_calendar`, `ghl_user` — the
location's reference lists, upserted on GHL's id, `synced_at` stamped, never deleted
(`missing_since`). Prefixed `ghl_` because `user` is reserved in Postgres. **No slots table, ever**
(D48). **No custom field values** (D49).
