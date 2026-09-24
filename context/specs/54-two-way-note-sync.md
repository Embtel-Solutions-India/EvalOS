# Unit 54 — Two-way note sync

**Decided 2026-09-24 by the business, reversing D42's and D49's "never synced".** A note written on
a deal in EvalOS reaches GoHighLevel, and a note written in GoHighLevel shows on the deal in EvalOS.
Before this unit the two note streams were deliberately separate: `opportunity_note` was EvalOS
staff prose that never left EvalOS, and `ghl_note` was mirrored (Unit 47b, `V62`) but read by
nothing.

**Status: BUILT 2026-09-24.** **Superseded in part by `54a-note-edit-delete.md`**: an author may now
edit or delete an EvalOS note, and "created once, never edited, never deleted" (§1, §6) no longer holds. §7 is answered below; the spec was amended where the live probe
overturned it (§2, "filed by relation").

---

## 0. The facts the design stands on

Read from GHL's API reference (`marketplace.gohighlevel.com/docs/ghl/contacts/…`) and the code,
2026-09-24 — not inferred.

1. **A GHL note belongs to a contact, not to an opportunity.** `POST /contacts/:contactId/notes`
   takes `body` (required), plus optional `userId`, `title`, `color` and `pinned`. It answers
   `201 { note: { id, body, userId, dateAdded, contactId } }`. The note object has no opportunity
   field, and there is no upsert. `PUT /contacts/:contactId/notes/:id` exists, and so does
   `GET /contacts/:contactId/notes`. The docs page lists no scope; the path suggests
   `contacts.write`, and **§7.1 verifies that against the live token before any code relies on
   it.**
2. **EvalOS notes are append-only by trigger.** `opportunity_note_no_mutation` (`V41`) raises on
   any UPDATE or DELETE, so no push state can live on that row.
3. **GHL notes are already mirrored.** `OpportunityMirrorService.absorbNotes` fills `ghl_note` from
   the opportunity search (`getNotes=true`) on every `MIRROR_DELTA` pass and every contact webhook.
   It upserts on `(brand_id, ghl_id)`, and `markMissing` soft-deletes a note GHL no longer returns.
   **Nothing reads the table.**
4. **The outbox only knows opportunities.** `SyncOutboxService.enqueue` hardcodes
   `SyncEntity.OPPORTUNITY`, and `push` always loads an `Opportunity`. `sync_outbox.entity_type`
   has no CHECK constraint, so a new entity type needs no migration.

## 1. Outbound: an EvalOS note is pushed to the deal's contact

- **The write stays exactly as it is.** `OpportunityNoteService.add` saves the row and, in the same
  transaction, enqueues `(OPPORTUNITY_NOTE, note.id, UPSERT)`. Nothing calls GHL inline. This is
  D44's rule applied to notes: no note is lost to a GHL outage, and the author sees it at once.
- **`SYNC_OUTBOX` sends it** through a new `GhlWriteClient.addContactNote(contactId, ghlOpportunityId,
  title, body)` (the opportunity id is for the audit row only; GHL takes none). That
  method must also be overridden in `StubGhlWriteClient`. The contact is the deal's
  `opportunity.ghl_contact_id`.
- **Order is enforced by retry, not by sequencing.** If the deal has no `ghl_id` or no contact yet
  (a portal-born deal GHL has not seen), the note row is recorded RETRY and waits. Its deal's own
  push creates it first.
- **The note carries its context, because GHL's note cannot.** A repeat client's contact holds
  notes from every deal, so the pushed note is titled `EvalOS · <deal name>` and its body is:

  ```
  <note body>

  — <author display name>, in EvalOS · #<first 8 of note id>
  ```

  The title is how a GHL user tells which deal a note was about; the `#<id8>` reference is the
  idempotency key in the next bullet.
- **Idempotency without an upsert.** Before posting, the drain checks `opportunity_note_ghl_link`
  (§3); a linked note is done. On any **retry** (attempts > 0 — the previous try may have landed),
  it reads `GET /contacts/:contactId/notes` (`GhlPipelineClient.notesOnContact`) and looks for the
  `#<id8>` reference; finding it links that note instead of posting again. A first attempt skips the
  read. A timeout must never become two notes in GHL.
- **A note not yet visible is a wait too.** `enqueue` commits on its own transaction, so a drain
  can see the outbox row before the note commits; that is RETRY, not a dead letter.
- **Created once, never edited, never deleted.** EvalOS notes are append-only (D24), so the only
  intent is UPSERT-as-create. An edit or deletion of the pushed note inside GHL is GHL's business:
  EvalOS keeps its own note unchanged, because it is the append-only record.
- **No `userId` is sent.** EvalOS has no mapping from a team member to a GHL user. The author is
  named in the trailer instead, and GHL records the integration as the writer.

## 2. Inbound: GHL notes show on the deal

- **No new sync, one correction to the existing one.** `ghl_note` is already filled every
  `MIRROR_DELTA` pass (≤5 min) and on contact webhooks. **The live probe (2026-09-24, 100 deals,
  30 notes) showed the search repeats a contact's notes under every deal of that contact** — 23
  of 30 listings were under a deal the note is not about — so filing by listing flipped a note
  between deals. GHL's answer carries an undocumented `relations` array naming the one deal a
  note was written on (or none, for a note on the contact alone), and an undocumented
  `createdBy.userId` (the documented `userId` was absent on 28 of 30). The mirror now files by
  `relations` — `ghl_opportunity_id` null means "the contact's" — and reads the author from
  `createdBy`. Contact-only notes are checked for deletion on every deal of the contact.
- **The timeline is this deal's notes plus the contact's own**, the latter labelled "on the
  contact".
- **One timeline, two tables.** `GET /api/opportunities/{id}/notes` returns both sources merged,
  newest first. **Storage does not merge** (D49's rule survives in that form): `opportunity_note`
  stays the append-only EvalOS record, and `ghl_note` stays a mirror GHL owns.
- **Additive response shape.** `Note` gains `origin` (`EVALOS` | `GHL`), `authorName` (a team
  member's display name, or the GHL user's name from `ghl_user`, or null) and `title` (GHL only).
  Existing fields keep their meaning, so an old client keeps working.
- **The echo is dropped.** A note EvalOS pushed comes back through the mirror as a `ghl_note`. Any
  `ghl_note` whose `ghl_id` appears in `opportunity_note_ghl_link` is left out of the timeline,
  because the EvalOS row is the one shown.
- **Only live GHL notes are shown.** A note with `missing_since` set was deleted in GHL, so it is
  not drawn. It stays in the table, as the mirror's rule already is.
- **Scope does not change.** The read is `scope.requireVisible(opportunityId)`, exactly as today,
  and `ghl_note` is read through `findByBrandIdAndGhlOpportunityIdOrderByDateAddedDesc`, which is
  brand-scoped.
- **The screen.** `DealNotes` shows a small origin badge ("GHL" or "EvalOS") and the author on
  every note. A GHL note is read-only there, just like an EvalOS one.

## 3. Schema — `V66__opportunity_note_ghl_link.sql`

```sql
CREATE TABLE opportunity_note_ghl_link (
  note_id     uuid PRIMARY KEY REFERENCES opportunity_note(id),
  brand_id    uuid NOT NULL REFERENCES brand(id),
  ghl_note_id text NOT NULL,
  linked_at   timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT uq_note_link_per_brand_ghl UNIQUE (brand_id, ghl_note_id)
);
-- plus the same BEFORE UPDATE OR DELETE trigger as opportunity_note: a link is a fact, written once
```

It is **insert-only**, so D24's append-only rule is kept rather than bent: the note row is never
touched, and its GHL identity is a second fact recorded beside it once. The trigger is proved by
`LocalPostgresIntegrationTest.aNoteGhlLinkCannotBeEditedOrDeleted`; the repository is a bare
`Repository` exposing no delete. **`V66` also carries the backfill** (§7.2): every existing
`opportunity_note` is queued once.

## 4. Code changes, in build order

1. `V66` and a `OpportunityNoteGhlLink` entity and repository.
2. `SyncEntity.OPPORTUNITY_NOTE`, and `SyncOutboxService.enqueue(brandId, entityType, entityId,
   intent)`. The existing three-argument form stays as an `OPPORTUNITY` overload, so no caller
   changes.
3. Make `push` branch on entity type: the opportunity path as today, and a note path as in §1.
4. `GhlWriteClient.addContactNote` (with its stub override; audited like every write) and
   `GhlPipelineClient.notesOnContact` for the retry read. The mirror's `Note` binds `relations` and
   `createdBy`, and `absorbNotes` files by relation (§2).
5. `OpportunityNoteService.add` enqueues. `on` merges, drops the echo and resolves authors.
6. `FieldOwnership`: the javadoc for `opportunityNote` changes from "never synced in either
   direction" to "EvalOS-owned, pushed once to the contact".
7. `DealNotes.tsx` gets the badge and author. The `opportunityApi.ts` `Note` type gains `origin`,
   `authorName` and `title`.

**Tests that must exist:**
- a note enqueues and does not call GHL;
- the drain posts the body with its trailer and writes the link;
- a deal with no contact retries rather than dies;
- `NO_ANSWER` then a retry finds the trailer and does not post twice;
- the echo is hidden;
- a missing GHL note is hidden;
- the read is scoped the same as today.

## 5. What it costs, named

- **One GHL request per note**, on the shared 100-per-10s budget, capped by `BATCH = 25`. Notes are
  written by hand, so the volume is small.
- **Up to 2 minutes out, up to 5 minutes back.** Outbound waits for the `SYNC_OUTBOX` tick. Inbound
  waits for `MIRROR_DELTA`, or arrives sooner through a contact webhook.
- **GHL shows a repeat client's notes from every deal together.** That is GHL's model, and the
  trailer is the mitigation.

## 6. What is deliberately not done

- No push of edits or deletions, in either direction. EvalOS notes are immutable, and a GHL edit
  shows up through the mirror's upsert.
- No writing of GHL notes from the EvalOS screen as GHL-owned notes. Every note written in EvalOS
  is an EvalOS note that is also sent to GHL.
- No backfill of old EvalOS notes. **§7.2 asks whether to have one.**

## 7. Answered at build (2026-09-24)

1. **Scope: `contacts.write`** — the GHL operation registry names it for `create-note`, and the
   location already holds it (spec 37). No test note was written into a real contact to prove it.
2. **Backfill: yes** — `V66` queues every existing `opportunity_note` once.
3. **Yes, the search repeats contact notes on every deal** — see §2; filed by `relations`.

## 7a. The questions as they were asked

1. **Verify `POST /contacts/{id}/notes` on the live token** (scope, and what a 403 looks like)
   before step 4. If the grant is missing, stop and raise it: do not ship a push that dead-letters
   every row.
2. **Backfill?** Existing `opportunity_note` rows could be enqueued once by a migration-shipped
   job. _Recommend:_ yes, one-off, because a salesperson opening GHL expects the history.
3. **Does the opportunity search return a contact's notes on every deal of that contact?** If it
   does, the mirror files a repeat client's GHL notes under each deal, and they show on all of them.
   _Recommend:_ probe the live location first. If it is true, show GHL notes on each of the
   contact's deals and label them as the contact's.
