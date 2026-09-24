-- Unit 54a follow-up (code review, 2026-09-24): a link with no GHL id is a delete marker.
--
-- A note whose push timed out after GHL had already created it has no link, and once its author
-- deletes it nothing records which contact to look on — so the DELETE found nothing to do and GHL
-- kept a note the author had deleted. The delete now always leaves a link carrying the contact;
-- when the push never learned the GHL id, that id is null and the drain searches the contact for
-- the note's `#id8` reference instead. The unique index allows any number of nulls.
ALTER TABLE opportunity_note_ghl_link ALTER COLUMN ghl_note_id DROP NOT NULL;
