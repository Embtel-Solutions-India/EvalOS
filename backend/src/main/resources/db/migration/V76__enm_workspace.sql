-- Unit 63: the Expert Network Manager's workspace.
--
-- 1. A GHL pipeline may be tagged EXPERT_HIRING. The ENM's desk is every live pipeline with that
--    purpose in their own brand (TeamMemberPipelineRepository.ghlIdsFor), so the tag is the grant.
--    V50 declared the CHECK inline, so Postgres named it pipeline_purpose_check.
ALTER TABLE pipeline DROP CONSTRAINT pipeline_purpose_check;
ALTER TABLE pipeline ADD CONSTRAINT pipeline_purpose_check
    CHECK (purpose IN ('MARKETING', 'SALES', 'DELIVERY', 'INTAKE', 'EXPERT_HIRING', 'UNASSIGNED'));

-- 2. When an expert's credentials were checked. NULL = not verified yet. Who checked is on the
--    audit row (CREDENTIALS_VERIFIED), which is where every "who" in EvalOS lives.
ALTER TABLE expert ADD COLUMN credentials_verified_at timestamptz;
