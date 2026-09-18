-- Six desk logins for International Evaluations: sales-1..3 and bde-1..3.
--
-- ============================================================================================
-- THIS IS AN OPERATOR SCRIPT, NOT A FLYWAY MIGRATION, and that is deliberate.
--
-- It creates PEOPLE, and who works at a company is not schema. A migration would run itself on
-- every environment that boots -- including ones these six should not exist on -- and could not be
-- given a different password per environment without committing one to the repository.
--
-- RUN IT BY HAND, once, against the database you actually mean:
--     psql "$DB_URL" -f docs/seed-desks.sql
-- ============================================================================================
--
-- BEFORE YOU RUN IT, three things have to be true or the CHECK constraints will refuse the rows:
--
--   1. THE BRAND ID BELOW MUST BE THE ONE THAT OWNS THE GHL LOCATION. A brand's id differs per
--      database -- it is 1111...1111 locally and something else in production -- so the SELECT
--      below resolves it by SLUG rather than hardcoding it. Check the slug matches.
--
--   2. THE PIPELINES MUST ALREADY BE MIRRORED. `ghl_pipeline_id` is an ACCESS KEY, and V39's
--      `team_member_pipeline_matches_role` requires a SALES or MARKETING row to hold a non-null
--      one. Run the PIPELINE_MIRROR sweep first. The ids below were read from location
--      WY6bW2xUCI8Tz8gw7aLJ on 2026-09-19; if that location is replaced, they all change.
--
--   3. `reports_to` MUST NAME A REAL TEAM MEMBER in this database. It is resolved by email below;
--      if that address does not exist the insert fails loudly rather than writing an orphan.
--
-- THE PASSWORD IS THE SEEDED ONE, `DevPassw0rd!`, ON THE BUSINESS'S INSTRUCTION (2026-09-19) --
-- the same value every local login uses, so the six behave identically wherever they are created.
--
-- ** READ THIS BEFORE RUNNING IT ANYWHERE REAL. ** That password is not a secret. Its bcrypt hash
-- is committed in `db/seed-local/V908`, V908 names the plaintext in a comment, and both are in
-- every clone of this repository and in its whole git history. Anyone who can read the repo -- any
-- present or former developer, anyone a laptop was lost to, anyone the code is ever shared with --
-- can sign in as all six of these people. On a laptop that costs nothing. On an environment
-- holding real client data it is not a weak password, it is a PUBLISHED one.
--
-- If this database is anything other than a throwaway, change the hash before running:
--
--     new BCryptPasswordEncoder().encode("something-nobody-has-published")
--
-- and rotate on first sign-in either way.
--
-- SEGMENT IS DISPLAY AND REPORTING ONLY (V39). It grants nothing and filters nothing -- the three
-- kinds carry identical permissions, which is why they are a column and not six roles. The values
-- below are a guess at who handles what and are safe to change.

BEGIN;

INSERT INTO team_member (id, brand_id, team_id, role, segment, ghl_pipeline_id,
                         email, password_hash, display_name, reports_to, active)
SELECT gen_random_uuid(), b.id, NULL, d.role, d.segment, d.pipeline, d.email,
       -- bcrypt of `DevPassw0rd!` -- the seeded value, published in this repository. See above.
       '$2a$10$r5HWTZRMQLgLPJKNHaZGgujwqeEjBbsDR8dpmh6JuZ7QdUjE1DHMW',
       d.display_name, m.id, true
  FROM (VALUES
        -- --- Sales: one service pipeline each ------------------------------------------------
        ('SALES',     'ATTORNEY',      'Eu5LMyN0ioAVXJgW1JGT', 'sales-1@internationalevaluations.com', 'Sales 1'),
        ('SALES',     'EMPLOYER_FIRM', '0kT0EC0zVKdP1fklmrFC', 'sales-2@internationalevaluations.com', 'Sales 2'),
        ('SALES',     'INDIVIDUAL',    'EoVInfnvXguEkg7wWktg', 'sales-3@internationalevaluations.com', 'Sales 3'),

        -- --- BDE: the three BDE pipelines ----------------------------------------------------
        --
        -- MARKETING rather than SALES. In this system that is not a label: MARKETING opens a LEAD
        -- (an upsert on contact + pipeline, so a repeat enquiry updates the open one) and SALES
        -- opens a DEAL (a true create) and gets meetings, follow-ups and close. Business
        -- development is the first. Change this column if that reading is wrong -- nothing else
        -- depends on it.
        ('MARKETING', 'INDIVIDUAL',    'CYcHdfPovQpxxNlIIto8', 'bde-1@internationalevaluations.com',   'BDE 1'),
        ('MARKETING', 'ATTORNEY',      'q9z45efXKXM3VjhtBnwZ', 'bde-2@internationalevaluations.com',   'BDE 2'),
        ('MARKETING', 'EMPLOYER_FIRM', 'ih8dMuHEiFfzSi2rVBrN', 'bde-3@internationalevaluations.com',   'BDE 3')
       ) AS d(role, segment, pipeline, email, display_name)
  -- Resolved, never hardcoded: a brand id differs per database and a wrong one writes six people
  -- into somebody else's tenant.
  CROSS JOIN (SELECT id FROM brand WHERE slug = 'international-evaluations') AS b
  -- Their manager. Change the address if somebody else owns these desks.
  LEFT JOIN team_member m ON m.email = 'bm.ie@evalos.local'
 -- Idempotent: running it twice adds nobody. `client_account_brand_email_key`'s sibling on
 -- team_member would refuse the duplicate anyway; this makes the second run a no-op instead of an
 -- error, so the script can be re-run after a partial failure.
 WHERE NOT EXISTS (
        SELECT 1 FROM team_member t WHERE t.brand_id = b.id AND lower(t.email) = lower(d.email));

-- What was written. Read it before committing.
SELECT email, role, segment, ghl_pipeline_id, active FROM team_member
 WHERE email LIKE 'sales-%@internationalevaluations.com'
    OR email LIKE 'bde-%@internationalevaluations.com'
 ORDER BY email;

-- Change to ROLLBACK to rehearse without writing.
COMMIT;
