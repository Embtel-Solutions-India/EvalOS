-- Which of the four shared fields a desk actually edited -- the fix for Unit 46's stale-stage push.
--
-- `local_updated_at` says "EvalOS holds an edit GHL has not confirmed" but not WHICH field it is
-- about, and the outbox stores an id rather than a payload, so the drain read the whole row and
-- sent all of it. A rename therefore re-sent the mirror's stage too -- and the mirror's stage is up
-- to one MIRROR_DELTA behind, so a GHL automation's stage move made inside that window was dragged
-- backwards in GHL and then read back as truth. FieldOwnership calls the stage SHARED, which is
-- exactly why it cannot be sent unasked: GHL writes it as often as a desk does.
--
-- Comma-joined entity property names, the same vocabulary `sync_drift.field` and FieldOwnership
-- already use. Nullable because "no local edit outstanding" is the resting state of every row.
ALTER TABLE opportunity ADD COLUMN locally_edited_fields TEXT;

COMMENT ON COLUMN opportunity.locally_edited_fields IS
	'Comma-joined FieldOwnership property names a desk edited and GHL has not confirmed. Cleared together with local_updated_at.';
