# Data model

**The authoritative file is `.claude/data-model.md`. It separates CURRENT DATABASE from REQUIRED
FUTURE MODEL — never mix them.**

25 tables, Flyway V1 to V65 all applied (V1–V49 verified against a live Postgres instance on
2026-09-16; V50–V62 are Units 44–47b, V63–V64 the 2026-09-18 review pass).
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
`audit_event` carries an append-only trigger that raises on UPDATE and DELETE. `opportunity_note`
lost its trigger in `V67` (Unit 54a — author edit/delete) and gained `updated_at`.
`opportunity_note_ghl_link` (`V66`/`V67`, Units 54/54a) records a pushed note's GHL id and contact —
no FK, no trigger, it outlives a deleted note until the drain deletes the GHL copy, and a null GHL id
(`V68`) is a delete marker the drain resolves by the note's `#id8` reference; outbox entity type `OPPORTUNITY_NOTE`; `ghl_note.ghl_opportunity_id` null = the
contact's note.

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

**2026-09-18 review pass — two columns.**

`opportunity.locally_edited_fields` (`V63`): comma-joined `FieldOwnership` property names, saying
WHICH of the four shared fields a desk edited. `local_updated_at` said only that an edit existed, so
the outbox push sent all four — and the mirror's stage is up to one `MIRROR_DELTA` behind, so a
rename re-sent a stale stage and undid a GHL workflow's card move. Cleared together with
`local_updated_at`.

`team_member_pipeline.revoked_at` (`V64`): **a revoke stamps, it does not delete.** The row has to
survive, because `backfillFromLegacyColumn` re-derives grants from `team_member.ghl_pipeline_id`
after every `PIPELINE_MIRROR` pass and was resurrecting revoked ones — past the role check, the
selling-brand check and the audit event. Clearing that column instead is forbidden: `V39`'s
`team_member_pipeline_matches_role` requires a SALES/MARKETING row to hold a non-null one. Every
read filters `revoked_at IS NULL`; `grant` is `ON CONFLICT ... DO UPDATE` so re-granting revives the
row. It is also the shape append-only assignment history wanted.

**`application_document` (`V65`, Unit 53, 2026-09-18).** Documents sent WITH a request, before any
case exists. FK to `client_application` and to `contact_snapshot`; `object_key` authoritative for
reads; `carried_to_case_document_id` stamped once at Handoff A, which is the whole idempotency.
**It shares its S3 object with the `case_document` it becomes** — the key is
`{brand}/client/{ghl_contact_id}/{doc}`, the person's prefix, so carrying a document onto a case is
one new row and no copy. No status, no review, no checklist item: a request has no checklist.