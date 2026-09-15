-- Repoint the seeded SALES and MARKETING desks at the pipelines that actually exist.
--
-- WHY THIS IS A NEW FILE AND NOT AN EDIT TO V908. V908 is already applied on every laptop that
-- has booted this profile. Flyway's checksum check would refuse the next boot, and this profile
-- only relaxes `*:missing` — `application-local.yml` says so in as many words: "a CHECKSUM
-- mismatch (an *edited* applied migration) still fails the boot, which is the check that actually
-- protects the schema."
--
-- WHAT BROKE. IE replaced its GoHighLevel sub-account on 2026-09-11 (kBumF0uUOmMBB5bneYjx ->
-- WY6bW2xUCI8Tz8gw7aLJ) with no migration. V908's five ids name pipelines in the abandoned
-- account, and `ghl_pipeline_id` is an ACCESS KEY: `PipelineScope.mine()` hands it to
-- `GET /opportunities/search`, GHL returns nothing for a pipeline it does not have, and the desk
-- draws **zero columns with no error at all**. An empty board and a wrong key look identical,
-- which is why this had to be found by comparison rather than by a failure.
--
-- THE SHAPE CHANGED TOO, not just the ids. The live account's sales pipelines are split by
-- SERVICE LINE, not by client segment:
--
--     Evaluation & Translational Pipeline            Eu5LMyN0ioAVXJgW1JGT
--     PERM + Immigration business plan + Pre filing   0kT0EC0zVKdP1fklmrFC
--     Expert Opinion Letter Pipeline + RFE            EoVInfnvXguEkg7wWktg
--
-- V908 named its three sales desks after segments (Attorney / Employer-Firm / Individual) because
-- that was the old account's split. `segment` stays as it is: `00b` §1.1 makes it display-and-
-- reporting only, with no access meaning, and nothing in the codebase branches on it — so leaving
-- it while the pipeline moves is correct rather than sloppy. Only the display names move, so a
-- developer reading the desk can tell which pipeline they are looking at.
--
-- MARKETING gets a third desk. The live account has three BDE pipelines and V908 seeded two
-- marketing logins, so one pipeline had no owner and `uq_team_member_pipeline` had nothing to
-- enforce. The third login uses the same throwaway BCrypt hash as every other seeded account
-- (password `DevPassw0rd!`).
--
-- NOT PRODUCTION. This is the local seed. The live desks are repointed through
-- `PUT /api/team-members/{id}/ghl-pipeline` as the GM, which is cutover item C4 in
-- `00d-implementation-runbook.md` §2a — and that route still has no UI, so it is a curl today.

-- Sales: by service line.
UPDATE team_member SET ghl_pipeline_id = 'Eu5LMyN0ioAVXJgW1JGT',
                       display_name    = 'Aditya (Evaluation & Translation)'
 WHERE id = 'aaaaaaaa-0000-0000-0000-000000000010';

UPDATE team_member SET ghl_pipeline_id = '0kT0EC0zVKdP1fklmrFC',
                       display_name    = 'Alex (PERM + IBP + Prefiling)'
 WHERE id = 'aaaaaaaa-0000-0000-0000-000000000011';

UPDATE team_member SET ghl_pipeline_id = 'EoVInfnvXguEkg7wWktg',
                       display_name    = 'Junaid (Expert Opinion Letter + RFE)'
 WHERE id = 'aaaaaaaa-0000-0000-0000-000000000012';

-- Marketing: the three BDE pipelines.
UPDATE team_member SET ghl_pipeline_id = 'CYcHdfPovQpxxNlIIto8',
                       display_name    = 'BDE-1 desk'
 WHERE id = 'aaaaaaaa-0000-0000-0000-000000000013';

UPDATE team_member SET ghl_pipeline_id = 'q9z45efXKXM3VjhtBnwZ',
                       display_name    = 'BDE-2 desk'
 WHERE id = 'aaaaaaaa-0000-0000-0000-000000000014';

-- The third BDE desk. `ON CONFLICT DO NOTHING` so a re-run is harmless; the partial unique index
-- on `ghl_pipeline_id` is what actually stops two live owners of one pipeline.
INSERT INTO team_member (id, brand_id, team_id, role, segment, ghl_pipeline_id,
                         email, password_hash, display_name, reports_to)
VALUES ('aaaaaaaa-0000-0000-0000-000000000015',
        '11111111-1111-1111-1111-111111111111',
        NULL, 'MARKETING', 'EMPLOYER_FIRM', 'ih8dMuHEiFfzSi2rVBrN',
        'marketing.bde3.ie@evalos.local',
        '$2a$10$r5HWTZRMQLgLPJKNHaZGgujwqeEjBbsDR8dpmh6JuZ7QdUjE1DHMW',
        'BDE-3 desk', NULL)
ON CONFLICT (id) DO NOTHING;
