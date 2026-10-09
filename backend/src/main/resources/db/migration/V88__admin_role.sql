-- Spec 78 (D78): the separate admin account.
--
-- `ADMIN` joins the role list and, like the GM, is cross-brand: NULL brand means "every brand"
-- (`Tier.ALL`). Both CHECKs are rewritten rather than appended to, because a CHECK cannot be widened
-- in place. No other table lists roles, and `team_member_pipeline_matches_role` /
-- `team_member_segment_matches_role` already say the right thing for it: an Admin owns no pipeline and
-- has no segment, because they only allow SALES and MARKETING to.
--
-- What this does NOT do is create an Admin. The first one is seeded (`seed-local` V916, `seed-prod`
-- V961, `seed-testprod` V954) and later ones are created by an Admin. Because the GM no longer
-- administers staff, a production database must hold an Admin before this ships.
ALTER TABLE team_member
    DROP CONSTRAINT team_member_role_valid,
    ADD CONSTRAINT team_member_role_valid CHECK (role IN (
        'GM', 'ADMIN', 'BRAND_MANAGER', 'PROJECT_MANAGER',
        'PROJECT_COORDINATOR', 'CASE_MANAGER', 'EXPERT_NETWORK_MANAGER',
        'SALES', 'MARKETING'
    ));

ALTER TABLE team_member
    DROP CONSTRAINT team_member_brand_required,
    ADD CONSTRAINT team_member_brand_required CHECK (
        role IN ('GM', 'ADMIN') OR brand_id IS NOT NULL
    );
