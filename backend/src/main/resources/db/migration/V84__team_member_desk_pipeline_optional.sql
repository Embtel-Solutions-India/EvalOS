-- Unit 68: the GM creates staff in EvalOS, and a new SALES / MARKETING desk has no single pipeline.
-- V54 replaced `team_member.ghl_pipeline_id` with the `team_member_pipeline` set, but V39's CHECK
-- still REQUIRED the old column for those two roles, so a desk could only be created by inventing a
-- value. Keep the half that matters — no other role may carry one — and drop the requirement.
ALTER TABLE team_member
    DROP CONSTRAINT team_member_pipeline_matches_role,
    ADD CONSTRAINT team_member_pipeline_matches_role CHECK (
        role IN ('SALES', 'MARKETING') OR ghl_pipeline_id IS NULL);
