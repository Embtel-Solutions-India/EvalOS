-- Unit 54a — a note's author may edit or delete it (`context/specs/54a-note-edit-delete.md`).
--
-- **This reverses D24 for `opportunity_note`, deliberately and by the business's choice
-- (2026-09-24): overwrite and hard delete, not append-only revisions.** `audit_event` keeps its
-- trigger; only the note stream loses one. What an edit or delete leaves behind is an audit row
-- saying who did it and when — never the words, which the business chose to be gone.
DROP TRIGGER opportunity_note_no_mutation ON opportunity_note;
DROP FUNCTION opportunity_note_is_append_only();

ALTER TABLE opportunity_note ADD COLUMN updated_at timestamptz;

-- **The link must outlive its note until the drain has told GHL.** A delete removes the note row,
-- and the outbox then needs the GHL note id and the contact to send `DELETE /contacts/{c}/notes/{id}`
-- — so the foreign key goes, the contact is recorded on the link itself, and the link stops being
-- append-only (the drain deletes it once GHL confirms).
ALTER TABLE opportunity_note_ghl_link DROP CONSTRAINT opportunity_note_ghl_link_note_id_fkey;
DROP TRIGGER opportunity_note_ghl_link_no_mutation ON opportunity_note_ghl_link;
DROP FUNCTION opportunity_note_ghl_link_is_append_only();

ALTER TABLE opportunity_note_ghl_link ADD COLUMN ghl_contact_id text;

UPDATE opportunity_note_ghl_link link
SET ghl_contact_id = deal.ghl_contact_id
FROM opportunity_note note
JOIN opportunity deal ON deal.brand_id = note.brand_id AND deal.ghl_id = note.ghl_opportunity_id
WHERE note.id = link.note_id;
