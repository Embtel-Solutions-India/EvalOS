-- The Admin deletes staff (2026-10-29). A member with no history is deleted outright; one with history
-- (audit trail, cases, notes, pipeline grants — append-only, never removed) is archived instead:
-- deactivated and hidden from the Staff screen, the row kept so that history still names them.
ALTER TABLE team_member ADD COLUMN archived_at timestamptz;
