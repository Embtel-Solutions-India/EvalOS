# Unit 54a — Editing and deleting a note

**Decided 2026-09-24 by the business, reversing D24 for `opportunity_note`.** The author of an
EvalOS note may edit it or delete it, and the change reaches GoHighLevel. The business chose
**overwrite and hard delete** over append-only revisions: an edit replaces the text, a delete
removes the row, and the previous text is not kept anywhere. Supersedes the "never edited, never
deleted" parts of Unit 54 (`54-two-way-note-sync.md` §1, §6). **Status: BUILT 2026-09-24.**

## 1. Rules

- **Author only.** Only the team member who wrote the note (`opportunity_note.author_id`) may edit
  or delete it, and only while `scope.requireMine` still admits them to the deal. Anyone else gets
  403, including a GM: the GM has no note write path at all (D19e, the POST's own javadoc).
- **EvalOS notes only.** A note written in GHL stays read-only in EvalOS. It is changed in GHL, and
  the mirror brings the change back as it already does.
- **Overwrite.** `PUT /api/opportunities/{id}/notes/{noteId}` `{ body }` replaces `body` and stamps
  `updated_at`. The screen shows "edited".
- **Hard delete.** `DELETE /api/opportunities/{id}/notes/{noteId}` removes the row.
- **Audited without the text.** `audit_event` stays append-only (D24 still holds for it). Each edit
  or delete writes `NOTE_EDITED` or `NOTE_DELETED` with the note id, deal and actor, but **no
  body**: the business chose "gone", and an audit row holding the deleted words would undo that.

## 2. Reaching GHL

Every change is still queued, never sent inline (D44).

| EvalOS change | Outbox | Drain |
|---|---|---|
| edit, note already in GHL | `UPSERT` | `PUT /contacts/{c}/notes/{ghlNoteId}` with the new title and body |
| edit, not in GHL yet | `UPSERT` (collapses onto the pending create) | the create, with the current text |
| delete, note in GHL | `DELETE` | `DELETE /contacts/{c}/notes/{ghlNoteId}` (a 404 counts as done), then drop the link and stamp the mirror row missing |
| delete, never reached GHL | `DELETE` | nothing to send, so done |

- **The link outlives the note until the drain is done with it.** A deleted note's GHL id and
  contact are needed after the row is gone, so `opportunity_note_ghl_link` loses its foreign key
  and gains `ghl_contact_id`. The drain deletes the link after GHL confirms. Until then, the link
  keeps the echo hidden.
- **The mirror row is stamped missing on delete**, so the GHL copy does not surface as a "GHL
  note" in the window before the next `MIRROR_DELTA`.
- **A queued UPSERT whose note is gone means "deleted", and is done.** That is only safe if a
  missing note can never mean "not committed yet". So note enqueues move to **after commit**
  (`TransactionSynchronization.afterCommit`): the outbox row never exists before its note does.
- **Stub mode sends nothing**, and changes nothing in GHL, the same as for a create.

## 3. Schema — `V67__opportunity_note_editable.sql`

- `opportunity_note`: drop the trigger `opportunity_note_no_mutation` and its function; add
  `updated_at timestamptz`. The blank-body CHECK stays.
- `opportunity_note_ghl_link`: drop the FK to `opportunity_note` and the append-only trigger; add
  `ghl_contact_id text`, backfilled from the note's deal.

## 4. What it costs, named

- **The previous text is unrecoverable** — in EvalOS by design, and in GHL once the drain runs.
- **`LocalPostgresIntegrationTest.anOpportunityNoteCannotBeEditedOrDeleted` is inverted**, not
  deleted: it now proves the row *can* be changed, so a trigger restored by accident fails the
  build.
- **Two in-flight races, both handled** (found in review, 2026-09-24):
  - *Deleted while its create is in flight:* the create still saves its link, because the link has
    no FK since `V67`. The DELETE row, queued after it, then finds the link and deletes the GHL copy.
  - *Edited while a push is in flight:* the edit's enqueue collapses onto the row being sent, so it
    would be lost. `SyncOutboxService.editedSince` sees the newer `updated_at` after GHL answers, and
    the drain re-queues it — the note twin of the opportunity path's `confirmPushed` returning zero
    (`SyncOutboxServiceTest.anEditMadeDuringThePushIsRequeued`).
- **Three more, from `/code-review` the same day, all fixed:**
  - *A collapsed enqueue failed its commit.* The outbox caught the unique-index violation as
    "already queued", but a failed `saveAndFlush` marks the transaction rollback-only, so editing a
    note (or a deal) twice before the drain answered 500 for a write that had landed. `enqueue` is
    now a native `INSERT … ON CONFLICT DO NOTHING` (`SyncOutboxRepository.enqueueIfAbsent`), proved
    on the real database by `LocalPostgresIntegrationTest.aSecondIdenticalEnqueueCollapsesWithoutFailing`.
    This fixes every existing caller, not only notes.
  - *A deleted note reappeared as a GHL note until the next sweep.* The sweep revived the mirrored
    copy's `missing_since`, and the drain dropped the link that hid it. `deleteNote` now stamps the
    mirrored copy missing as it drops the link (`aDeleteStampsTheMirroredCopyMissing`).
  - *A note deleted after a timed-out push stayed in GHL.* No link recorded which contact to look
    on. `delete()` now always leaves a link carrying the contact — a **delete marker** with a null
    GHL id (`V68`) when none is known — and the drain finds the note that landed by its `#id8`
    reference (`aDeleteMarkerFindsTheNoteThatLandedByItsReference`). A push still in flight overwrites
    the null with the id it gets back.
- **And three from a second `/code-review`, all fixed:**
  - *The re-queue path had no transaction.* The drain re-queues through a self-call that skips
    `enqueue`'s `REQUIRES_NEW`, and the native insert carried none of its own, so a re-queue after
    an edit-during-push threw and dead-lettered the row. `enqueueIfAbsent` is now `@Transactional`
    itself (`theOutboxInsertCarriesItsOwnTransaction`, on Postgres, with no transaction around it).
  - *An edit could be lost after an ambiguous create.* A retry that found the note already in GHL
    linked it without sending the current text. It now overwrites it with the current text.
  - *A delete on a deal with no known contact was dead-lettered.* No marker is written without a
    contact, and a marker that has none is dropped as done (`aDeleteWithNoContactIsDoneNotDead`).
