-- Unit 54 — two-way note sync (`context/specs/54-two-way-note-sync.md`).
--
-- An EvalOS note is now pushed once to the deal's GHL contact. Its GHL id has to be recorded
-- somewhere, and it cannot be on `opportunity_note`: V41's trigger refuses every UPDATE, and it
-- should. So the push is a second fact written beside the note, once — and this table is
-- append-only by the same mechanism, because a link rewritten later would let the timeline show a
-- pushed note twice (its echo would stop matching) or lose it.
CREATE TABLE opportunity_note_ghl_link (
    note_id     uuid        PRIMARY KEY REFERENCES opportunity_note (id),
    brand_id    uuid        NOT NULL REFERENCES brand (id),
    ghl_note_id text        NOT NULL,
    linked_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_note_link_per_brand_ghl UNIQUE (brand_id, ghl_note_id)
);

CREATE FUNCTION opportunity_note_ghl_link_is_append_only() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'opportunity_note_ghl_link is append-only: % is not permitted', tg_op;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER opportunity_note_ghl_link_no_mutation
    BEFORE UPDATE OR DELETE ON opportunity_note_ghl_link
    FOR EACH ROW EXECUTE FUNCTION opportunity_note_ghl_link_is_append_only();

-- **The backfill (open decision Q13, taken as recommended).** Every note written before this unit
-- is queued once, so a salesperson opening GHL finds the history rather than only what was written
-- after today. Queued, not sent: the drain paces it at 25 a tick against the shared GHL budget, and
-- a note on a deal GHL has not seen waits like any other. The pending-row unique index makes a
-- re-run of this statement impossible to double up, and Flyway never re-runs it anyway.
INSERT INTO sync_outbox (id, brand_id, entity_type, entity_id, intent, queued_at)
SELECT gen_random_uuid(), note.brand_id, 'OPPORTUNITY_NOTE', note.id, 'UPSERT', note.created_at
FROM opportunity_note note
ON CONFLICT DO NOTHING;
