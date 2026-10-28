-- An Administrator can retry a dead-lettered push from Sync health (2026-10-28).
--
-- A retry QUEUES A NEW ROW for the same entity and intent; the dead row is not revived. It stays
-- dead as the record of what failed, and these two columns say that someone acted on it and who.
-- Stamped once: a row already retried cannot be retried again (the new row carries any later failure).
ALTER TABLE sync_outbox
    ADD COLUMN retried_at timestamptz,
    ADD COLUMN retried_by uuid REFERENCES team_member (id);

ALTER TABLE sync_outbox
    ADD CONSTRAINT sync_outbox_retried_only_when_dead CHECK (retried_at IS NULL OR dead_at IS NOT NULL);
