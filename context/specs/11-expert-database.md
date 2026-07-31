# Unit 11 — Expert database (ENM) + sheet upload

**Phase:** 2 — Connect the seams — first unit
**Depends on:** 03
**Unlocks:** 12 (match scoring reads this taxonomy), 13 (redacted CV reads this
profile), 16 (payout ledger names the expert)
**Gating open questions:** the **`FieldTag` value list needs the ENM's sign-off
before the migration lands** (the mechanism is settled, the vocabulary is not);
`EVALOS_FIELD_KEY` must be a real key in any non-local environment — this is the
first unit that writes `payment_detail` from a screen.

## Goal

Replace the expert Google Sheet. Each brand's Expert Network Manager maintains
their own roster in EvalOS — by hand for one expert, by **bulk sheet upload** for
the fifty they already have in a spreadsheet — against a **controlled field-tag
vocabulary** so that Unit 12 can match on tags rather than guess at free text.

The `expert` table and entity already exist (Unit 03, `V7`). This unit gives them
a screen, a contact, a letter-type axis, and a vocabulary.

**Verifiable result:** an ENM can upload their existing expert sheet and get
either a fully imported roster or a per-row report of exactly what was wrong;
can then search/filter/edit that roster and set availability; sees their own
brand's experts and no other brand's; and never sees `payment_detail` in any
response body, including their own brand's.

## In scope

- The closed **`FieldTag`** and **`LetterType`** vocabularies, enforced in the
  database as well as the entity.
- The columns an expert roster needs and Unit 03 did not give it: `email`,
  `phone`, `letter_types`, `standard_fee`.
- ENM CRUD over the expert profile, brand-scoped.
- The availability board (who is free, who is at capacity, who is on leave).
- **Bulk sheet upload** with column mapping, validation, and an all-or-nothing
  import.

## Out of scope

- **Match scoring** — Unit 12. This unit produces the data the scorer reads and
  deliberately holds no ranking logic.
- **Redacted CV generation** — Unit 13.
- Expert-facing screens (the expert sees nothing of this) — Unit 15.
- Payout amounts and history — Unit 16. `total_payments_pending` is maintained
  there, displayed here.
- Acceptance / decline rates — Unit 12 owns the record that makes them
  computable. This unit shows what it can already count.
- Cross-brand expert sharing. An expert recruited by two brands is two rows, per
  `Expert`'s own class comment; nothing here merges them.

## The vocabulary decision

`primary_fields` / `secondary_fields` are `text[]` with no vocabulary today.
Unit 12 matches a case's requirement against these tags, and
`"Mechanical Engineering"` does not match `"mechanical engg"`. **Decision taken:
a closed enum, unknown tags rejected.** Exact matching for Unit 12, at the cost
of a migration whenever a new discipline is recruited into.

Enforced in two places, because either alone is insufficient:

- `domain/FieldTag` + `domain/LetterType` — Java enums, so a controller cannot
  accept an unknown tag.
- A database `CHECK` per column, so a migration, a seed script or a hand-written
  `UPDATE` cannot either:
  ```sql
  ALTER TABLE expert ADD CONSTRAINT expert_primary_fields_known
    CHECK (primary_fields <@ ARRAY[...]::text[]);
  ```
  Same for `secondary_fields` and `letter_types`. This is the `team_member`
  brand-CHECK pattern from Unit 02 — the constraint exists so a mis-seeded row
  cannot silently become invalid data.

**The value list is not mine to choose.** Ship the migration with the list the
ENM confirms. A starter list to react to, not to adopt unreviewed:
`MECHANICAL_ENGINEERING`, `ELECTRICAL_ENGINEERING`, `CIVIL_ENGINEERING`,
`CHEMICAL_ENGINEERING`, `COMPUTER_SCIENCE`, `INFORMATION_TECHNOLOGY`,
`DATA_SCIENCE`, `BUSINESS_ADMINISTRATION`, `FINANCE`, `ACCOUNTING`, `MARKETING`,
`ECONOMICS`, `NURSING`, `MEDICINE`, `PHARMACY`, `PUBLIC_HEALTH`, `EDUCATION`,
`LAW`, `ARCHITECTURE`, `BIOLOGY`, `CHEMISTRY`, `PHYSICS`, `MATHEMATICS`,
`PSYCHOLOGY`, `FINE_ARTS`, `HOSPITALITY_MANAGEMENT`, `SUPPLY_CHAIN`,
`HUMAN_RESOURCES`. `LetterType` follows the two deliverables and the goals the
design already names: `CREDENTIAL_EVALUATION`, `EXPERT_OPINION_LETTER`,
`RFE_RESPONSE`, `PERM_LETTER`, `TRANSLATION_CERTIFICATION`.

Adding a tag later is a new migration that widens the CHECK — never an edit to
the applied one (invariant 9).

## Backend

New migration (next free `V`-number at build time): `email`, `phone`,
`letter_types text[]`, `standard_fee numeric(12,2)` on `expert`; the three
`CHECK`s above; a **partial unique
index on `(brand_id, lower(email)) WHERE email IS NOT NULL`** — the same shape
`V16` used for contact identity, and the key the import upserts on; and a **GIN
index on `primary_fields`** so Unit 12's tag containment does not table-scan the
roster.

| Method | Path | Auth | Notes |
| --- | --- | --- | --- |
| GET | /api/experts/roster | GM · Brand Manager · PM · ENM | the roster screen: search by name/institution, filter by field tag, letter type, availability, tier. Brand-scoped, paged. **Never projects `payment_detail`.** |
| GET | /api/experts/{id} | GM · Brand Manager · PM · ENM | one profile. `paymentDetail` is **absent from the DTO**, not blanked |
| POST | /api/experts | GM · Brand Manager · ENM | create; validates tags against the enums |
| PATCH | /api/experts/{id} | GM · Brand Manager · ENM | edit; audited |
| PATCH | /api/experts/{id}/availability | GM · Brand Manager · ENM | set `AVAILABLE/AT_CAPACITY/INACTIVE/ON_LEAVE`; audited |
| PUT | /api/experts/{id}/payment-detail | GM · Brand Manager · ENM | **write-only.** Sets the encrypted field. There is no read endpoint for it at all |
| GET | /api/experts/availability-board | GM · Brand Manager · PM · ENM | counts + lists by availability, with `current_active_count` against capacity |
| POST | /api/experts/import/validate | GM · Brand Manager · ENM | dry run: parse + validate, return the per-row report, write nothing |
| POST | /api/experts/import | GM · Brand Manager · ENM | the real import, all-or-nothing |

`GET /api/experts` (`web/ExpertPickerController`, Unit 08) **stays exactly as it
is** — the `{id, fullName}` `AVAILABLE`-only picker read the assignment dialog
uses. This unit adds the roster beside it rather than widening it: a PM staffing
a case needs a name, and the picker's narrowness is the reason the encrypted
field and the quality scores cannot leak through it.

Every read goes through `ExpertRepository.findScoped(ctx[, id])` — `expert` is a
brand-only scoped entity, so the ENM's `Tier.SUPPLY` and the PM's `Tier.TEAM`
both resolve to the brand predicate `ScopePredicate` already builds. **No new
scoping code.**

### Current load is **derived**, not read off the column

`V7` gave `expert` a `current_active_count` and a `total_cases_completed`, both
`NOT NULL DEFAULT 0`, and **nothing in the system has ever written either one.**
They are permanently zero. This unit is the first to display an expert's load and
Unit 12 scores on it, so a column that is always 0 would mean a roster reporting
everyone as free and a scorer whose load term is a constant.

**Derive both with one grouped count over `evalos_case`**, not by starting to
increment the columns:

- `current active` = cases naming this expert whose stage is not `CLOSED`.
- `completed` = cases naming this expert that reached `CLOSED` un-refunded.

`service/ExpertLoadService` owns the single batched query — one query for the
whole roster page, keyed by expert id, never one per row. A counter has to be
incremented on assignment, decremented on close, on refund, on reassignment and
on decline, and backfilled for existing rows; every one of those is a chance to
drift, and a load figure that is wrong is worse than one that is slow at this
scale (50–100 cases per brand per month, per the NFRs).

The two columns are therefore **left unmaintained and unread**. Do not delete
them here — dropping columns is not a drive-by, and Unit 17's read models may
want them as a materialized cache. Recorded as a known-dead pair in the tracker
instead.

Unit 12 reuses `ExpertLoadService` rather than counting again.

### `payment_detail` — the rule this unit is most able to break

It is the only encrypted field in EvalOS (invariant 4) and this is the first unit
with a screen that writes it. Three things hold:

- The write endpoint is `PUT`-only and **there is no read path**. The ENM types a
  new value or leaves it alone; nobody reads it back through the API, including
  the person who wrote it.
- No `ExpertView` / `ExpertSummary` / import-report DTO declares the field. It is
  not blanked, masked or `null`ed — it is **not a member**, so no future edit to
  a mapper can start populating it.
- The roster screen shows only whether a payment detail **is on file** (a boolean
  derived server-side), which is what an ENM actually needs to know.
- The import **never accepts a payment-detail column.** A bank reference in a
  spreadsheet that has been mailed around is exactly the exposure the encrypted
  column exists to end; it is typed once, into the field, by a person.

### Sheet upload

The ENM's roster lives in a Google Sheet today, so the import is the primary
maintenance path, not a convenience.

- **Format.** CSV is required. **XLSX costs a dependency** — Apache POI, ~10 MB
  with transitives, against `commons-csv` at ~50 KB. The build plan asks for
  both; if the ENM is content to use Sheets' one-click *File → Download → CSV*,
  CSV-only is the cheaper build. **Confirm at build time**; do not add POI
  speculatively (`ai-workflow-rules.md`: install a dependency only in the unit
  where it first unlocks real behaviour).
- **Column mapping.** The uploader posts the file plus a mapping of sheet column
  → expert field, so the ENM does not have to rename their spreadsheet's headers.
  The mapping is submitted with the file; it is not persisted.
- **Validation is a separate call.** `import/validate` parses and checks every
  row and writes nothing. The report names, per row: the row number, the offending
  column, and the reason — an unknown field tag reports **the value it did not
  recognise and the closest legal tags**, because "row 34 invalid" against a
  closed vocabulary is a dead end for whoever has to fix the sheet.
- **The import is all-or-nothing**, in one transaction. A closed vocabulary means
  a typo is an error rather than a variant, and a half-imported roster is worse
  than a rejected one: the ENM cannot tell which half landed. They fix the sheet
  and re-upload.
- **Re-upload updates, it does not duplicate.** The upsert key is
  `(brand_id, lower(email))`, backed by the partial unique index — so the index
  refuses a duplicate even if two ENMs upload the same sheet at once, rather than
  the lookup being trusted (the `V15`/`V16` lesson: a lookup then an insert is a
  check-then-act). **A row with no email cannot be upserted** and is reported as
  such, because there is nothing to match it on.
- Rows never delete. An expert dropped from the sheet is set `INACTIVE` by hand;
  a sheet that silently deletes roster rows is how history disappears.
- One audit entry per import naming the file, the row count and the actor, plus
  the per-expert `CREATED`/`UPDATED` rows.

## Frontend deliverables

1. **Expert roster** (`features/experts`): searchable, filterable table — name,
   title, institution, field tags, letter types, tier, availability, quality
   score, active load, "payment detail on file". Tabular figures on every numeric
   column, per `ui-context.md`. **Active load is the derived count**, not
   `current_active_count`. `standard_fee` is the expert's usual fee — the figure
   Unit 16 prefills a payout with, not a price EvalOS charges anyone.
2. **Expert profile / edit form**: field tags and letter types as
   **multi-selects over the enums**, never free-text inputs — the closed
   vocabulary has to be visible in the UI or the ENM will paste from the sheet.
   `payment_detail` is a write-only field showing "on file / not on file", never
   a value.
3. **Availability board**: grouped by availability with the active-case load,
   so the ENM can see who to free up before a PM finds nobody to assign.
4. **Sheet upload flow**: pick file → map columns → **validation report** →
   confirm import. The report is the screen that matters; it lists every bad row
   with its reason and offers no "import anyway" button.
5. Nav: the ENM's existing **Expert database** entry stops being a placeholder.
   Add the roster to the GM / Brand Manager / PM nav through the same
   `NAV_ITEMS` table (one table, per Unit 07's note) — a PM who picks experts
   should be able to read the roster they are picking from.

## Acceptance criteria

- [ ] An ENM sees only their own brand's experts on every route, and a second
      brand's expert is absent rather than forbidden.
- [ ] `payment_detail` appears in **no** response body on any route, for any
      role, including the ENM who wrote it. Asserted by a test that walks every
      expert route and greps the serialized response.
- [ ] Creating or editing an expert with an unknown field tag or letter type is
      rejected by the API, **and** a raw `UPDATE` writing one is rejected by the
      database CHECK.
- [ ] A sheet with three bad rows imports **nothing** and reports all three, each
      with its row number, column and reason.
- [ ] Re-uploading the same sheet updates the existing experts and creates no
      duplicates; two concurrent uploads of it cannot create two rows for one
      email (the partial unique index, proved in real SQL).
- [ ] An expert carrying two open cases shows an active load of **2** on the
      roster and the availability board, while `current_active_count` in the
      database is still `0` — proving the figure is derived and not the dead
      column.
- [ ] Setting availability writes an audit entry, and an expert set to anything
      other than `AVAILABLE` disappears from `GET /api/experts` (the Unit 08
      picker) — so the picker still cannot offer what `availableExpert` would
      refuse.
- [ ] `npm run build` green; `./mvnw verify` green, with the DB-gated checks run
      against local Postgres (the new CHECKs and the partial unique index are
      only real there).

## Invariants honored

Brand isolation on every roster read (1); role + ownership before every mutation
(3); **`payment_detail` encrypted, write-only, and in no DTO or response body**
(4); thin controllers, validation and import in `service` (6, and
`architecture.md`'s boundaries); EvalOS is the system of record for experts (7);
new migration, never an edit to `V7` (9); an audit entry on every create, edit,
availability change and import (13); no file hosted — the uploaded sheet is
parsed in memory and never stored (14).

## Files touched

**Created.** Backend: `domain/FieldTag.java`, `domain/LetterType.java`,
`service/ExpertService.java`, `service/ExpertImportService.java`,
`service/ExpertLoadService.java`,
`web/ExpertController.java` (+ DTOs: roster row, profile view, create/update
request, import report). Migration:
`db/migration/V<next>__expert_contact_and_taxonomy.sql`. Frontend:
`frontend/src/features/experts/*` (`ExpertRoster`, `ExpertProfile`,
`AvailabilityBoard`, `SheetUpload`, `ImportReport`, `expertApi`).

**Modified.** `domain/Expert.java` (the new columns + accessors the roster needs;
`letterTypes`, `email`, `phone`). `repository/ExpertRepository.java` (search and
filter finders). `repository/CaseRepository.java` — one batched
`count … group by expert_id` projection for `ExpertLoadService`. It is a
deliberately brand-*unscoped* aggregate over ids the caller already read scoped,
so it carries the same javadoc convention as `findByCaseIdIn` ("do not call it
with ids that came from a request") and the same DB-gated brand-isolation test
the Unit 10 review added for those two finders.
`frontend/src/features/shell/navigation.ts` (the roster entry).
`db/migration/local/V9xx__seed_local.sql` or a new local seed for a handful of
experts carrying legal tags.

**Not touched.** `web/ExpertPickerController.java`, `common/PaymentDetailConverter.java`
(protected — `ai-workflow-rules.md`), `service/ScopePredicate.java`, and every
applied migration.
