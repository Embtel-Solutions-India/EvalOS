-- A revoke that survives the backfill -- Unit 44b's loose end, found in review.
--
-- THE TWO STATEMENTS WERE FIGHTING EACH OTHER. `TeamMemberPipelineRepository.revoke` DELETEd the
-- join row, and `backfillFromLegacyColumn` re-derives join rows from `team_member.ghl_pipeline_id`
-- after EVERY PIPELINE_MIRROR pass -- so a revoked pipeline came back within the sweep interval,
-- through no role check, no selling-brand check and no audit event. Every gate in
-- `PipelineAssignmentService.grant`, bypassed by a background job.
--
-- AND THE OBVIOUS FIX IS FORBIDDEN BY V39. Clearing the legacy column on revoke would have done it,
-- except `team_member_pipeline_matches_role` requires a SALES or MARKETING row to hold a non-null
-- `ghl_pipeline_id` -- "a sales row without a pipeline is a person who can see nothing", in V39's
-- own words. The column cannot be emptied for exactly the roles that can hold pipelines, so the
-- backfill's source can never be retired. The revocation has to be recorded where the backfill can
-- see it: on the join row itself.
--
-- SO THE ROW SURVIVES A REVOKE. `ON CONFLICT DO NOTHING` then skips it on every later pass, because
-- the row is still there -- the backfill stops being able to resurrect anything it did not create.
--
-- AND THIS IS THE RIGHT SHAPE ANYWAY. Assignment history is append-only (CLAUDE.md), and a DELETE
-- of a grant was already the wrong verb for it: "who could work this pipeline in August" is a
-- question a support conversation asks, and a deleted row cannot answer it.
ALTER TABLE team_member_pipeline ADD COLUMN revoked_at timestamptz;

COMMENT ON COLUMN team_member_pipeline.revoked_at IS
    'When this grant was taken away. NULL means live. A revoked row is KEPT so that '
    'backfillFromLegacyColumn cannot re-create it from team_member.ghl_pipeline_id, which V39 '
    'forbids emptying for SALES and MARKETING.';

-- Every read filters on this, so the index carries it rather than being re-checked per row.
DROP INDEX IF EXISTS idx_team_member_pipeline_member;
CREATE INDEX idx_team_member_pipeline_member_live
    ON team_member_pipeline (team_member_id) WHERE revoked_at IS NULL;
