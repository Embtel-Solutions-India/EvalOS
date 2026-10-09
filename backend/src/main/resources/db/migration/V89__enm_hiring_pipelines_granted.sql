-- An Expert Network Manager's hiring pipelines become a per-person grant (2026-10-09).
--
-- UNTIL NOW IT WAS DERIVED. Unit 63 gave every active ENM of a brand every live EXPERT_HIRING pipeline of that
-- brand, read straight off the purpose tag (`TeamMemberPipelineRepository.ghlIdsFor`). That cannot say "this ENM
-- owns Hiring A and that one owns Hiring B", which is what the business asked for, so an ENM now holds exactly
-- the rows in `team_member_pipeline`, like a Sales or Marketing desk.
--
-- NOBODY LOSES ANYTHING ON DEPLOY. The derived rule is removed in the same release, so without this every
-- ENM would be left with no hiring pipeline and an empty board with no error. Each active ENM is granted each live
-- hiring pipeline of their own brand -- precisely what they held a moment ago. The Administrator then trims it
-- per person on the Staff screen.
--
-- `granted_by` is NULL: this is the system carrying existing access across, not a person granting it. It is
-- idempotent -- ON CONFLICT DO NOTHING leaves any row an Administrator already wrote (or revoked) untouched, so a
-- revoke is never undone by re-running it. No team_member_pipeline CHECK names a role, so the table already
-- accepts these rows.
INSERT INTO team_member_pipeline (team_member_id, pipeline_id)
SELECT m.id, p.id
  FROM team_member m
  JOIN pipeline p ON p.brand_id = m.brand_id
 WHERE m.role = 'EXPERT_NETWORK_MANAGER'
   AND m.active
   AND p.purpose = 'EXPERT_HIRING'
   AND p.missing_since IS NULL
ON CONFLICT DO NOTHING;
