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
