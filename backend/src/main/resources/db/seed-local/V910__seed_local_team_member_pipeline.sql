-- Give the seeded desks their pipelines in the table that now owns them (Unit 44b).
--
-- WHY A NEW FILE RATHER THAN AN EDIT. `V908` and `V909` set `team_member.ghl_pipeline_id`, and
-- editing an applied migration is a checksum mismatch that refuses the boot -- the seed README says
-- so, and it is the check that protects the schema. Adding a file is allowed; editing one is not.
--
-- `V54` backfills from the column, but on a FRESH database V54 runs before V908/V909, so the
-- backfill finds nothing. This is the same statement, ordered after the rows exist.
--
-- IT IS ALSO A NO-OP UNTIL THE PIPELINES ARE MIRRORED. `pipeline` is filled by the PIPELINE_MIRROR
-- sweep against real GHL, not by a seed, so on a laptop with no GHL token this inserts zero rows --
-- correctly. Run `POST /api/jobs/PIPELINE_MIRROR/run` as the GM, then re-run this statement, or
-- assign the desks through the team screen.
INSERT INTO team_member_pipeline (team_member_id, pipeline_id)
SELECT m.id, p.id
  FROM team_member m
  JOIN pipeline p ON p.ghl_id = m.ghl_pipeline_id AND p.brand_id = m.brand_id
 WHERE m.ghl_pipeline_id IS NOT NULL
ON CONFLICT DO NOTHING;
