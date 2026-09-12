-- Unit 39 — the note stream on a GHL opportunity.
--
-- **The one thing in this programme EvalOS genuinely owns.** Everything else about an
-- opportunity is GHL's and EvalOS holds a droppable copy (V40). Notes are the exception, and
-- they are the exception because GHL has nowhere to put them: its only note endpoints are
-- POST/PUT /contacts/{contactId}/notes, so a note hangs off the CONTACT. Verified against the
-- live API 2026-09-10.
--
-- Spec: context/specs/39-marketing-lead-desk.md; the decision is 00b §1.4.

CREATE TABLE opportunity_note (
    id                 uuid        PRIMARY KEY DEFAULT gen_random_uuid(),

    -- **Keyed on the OPPORTUNITY, not the contact, and this is the decision most likely to be
    -- "simplified" back into a bug.**
    --
    -- It was put to the business that "an opportunity is itself a contact", which is true of
    -- every contact today. Invariant 7 exists because it stops being true: a repeat client is
    -- ONE contact and TWO opportunities, and a note stream keyed on the contact would merge two
    -- deals' histories with no way to separate them afterwards. Keying on the opportunity costs
    -- nothing now and survives that.
    --
    -- No foreign key, deliberately: the opportunity lives in GHL. `ghl_opportunity_cache` is a
    -- droppable copy (V40) and a FK into it would make truncating the cache delete real notes.
    ghl_opportunity_id text        NOT NULL,

    -- The note is an EvalOS row, so it carries a brand like every other EvalOS row — even though
    -- the opportunity it describes belongs to a GHL location rather than a brand.
    brand_id           uuid        NOT NULL REFERENCES brand (id),

    -- Denormalised so Tier.PIPELINE can scope a note WITHOUT joining the cache. The cache is
    -- droppable; a scope predicate that depends on a droppable table is a scope that fails OPEN
    -- the moment the table is empty. That is the whole reason this column is here.
    ghl_pipeline_id    text        NOT NULL,

    author_id          uuid        NOT NULL REFERENCES team_member (id),
    body               text        NOT NULL,
    created_at         timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT opportunity_note_body_not_blank CHECK (btrim(body) <> '')
);

-- The stream for one deal, newest first — the only read a screen makes.
CREATE INDEX idx_opportunity_note_opportunity
    ON opportunity_note (ghl_opportunity_id, created_at DESC);

-- The scope predicate's columns.
CREATE INDEX idx_opportunity_note_scope ON opportunity_note (brand_id, ghl_pipeline_id);

-- **Append-only, enforced in the database rather than by convention** — the same reasoning V10
-- gives for `audit_event`, and the same mechanism, because a GRANT cannot do this job: the
-- application connects as the table owner and an owner is not subject to REVOKE. A trigger holds
-- for every role, including the owner.
--
-- Why a note and not just an audit row: this IS the client conversation, not a record of it. A
-- correction is a new note. **P3 (00b §5) is settled by the same property** — if the opportunity
-- is deleted in GHL the note row survives, orphaned and readable, because append-only truth
-- outranks tidiness and a deleted opportunity is exactly when the history matters.
CREATE FUNCTION opportunity_note_is_append_only() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'opportunity_note is append-only: % is not permitted', tg_op;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER opportunity_note_no_mutation
    BEFORE UPDATE OR DELETE ON opportunity_note
    FOR EACH ROW EXECUTE FUNCTION opportunity_note_is_append_only();
