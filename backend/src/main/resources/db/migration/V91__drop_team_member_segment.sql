-- D21 (2026-10-13): the role is the whole answer. A Sales or Marketing member is SALES or MARKETING
-- and nothing more; the attorney / employer-firm / individual split is no longer recorded in EvalOS.
--
-- **Why the column stays, VESTIGIAL, instead of being dropped.** The applied seeds V908, V909
-- (seed-local) and V960 (seed-prod) INSERT into `segment`, and on a fresh database Flyway runs them
-- AFTER this file (V9xx > V91). A DROP COLUMN here would fail every fresh build, and editing those
-- seeds would break their checksums. Same treatment `ghl_pipeline_id` got in V84. Nothing maps,
-- reads or writes it any more; drop it if the seed trees are ever squashed.
ALTER TABLE team_member DROP CONSTRAINT team_member_segment_matches_role;

UPDATE team_member SET segment = NULL WHERE segment IS NOT NULL;

COMMENT ON COLUMN team_member.segment IS 'VESTIGIAL since V91: unused; kept only so applied seeds replay.';
