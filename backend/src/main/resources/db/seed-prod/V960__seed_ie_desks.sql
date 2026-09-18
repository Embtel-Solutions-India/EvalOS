-- The six International Evaluations desk logins: sales-1..3 and bde-1..3.
--
-- This replaces `docs/seed-desks.sql`, which was an operator script run by hand. The argument for
-- running it by hand was that a migration "runs itself on every environment that boots -- including
-- ones these six should not exist on -- and could not be given a different password per environment
-- without committing one to the repository." Both halves are answered by the tree this file sits
-- in rather than by not being a migration at all:
--
--   * `db/seed-prod` is named ONLY by `application-prod.yml`. Flyway has no exclude filter, so a
--     sibling directory is the whole mechanism keeping a seed out of the environments it does not
--     belong to -- the same mechanism `db/seed-local` and `db/seed-testprod` already rely on, and
--     the one `MigrationTreeTest` exists to hold in place.
--   * The password arrives as the `desk-password-hash` placeholder, resolved from `DESK_PASSWORD_HASH`
--     at migrate time with NO DEFAULT. Nothing about the credential is in this file or in git, and an
--     environment that forgets the variable fails to migrate rather than seeding a published hash.
--
-- WHY NOT `db/migration`. Two reasons, either one sufficient. These six are PEOPLE AT ONE COMPANY,
-- not schema -- the local tree already seeds its own six at `*.ie@evalos.local` (V908/V909/V911) and
-- a shared script would give a laptop twelve desks. And `uq_team_member_pipeline` is a GLOBAL partial
-- unique index on `ghl_pipeline_id` for active rows (V39), so the second set would not merely be
-- untidy: the insert would fail outright against the first.
--
-- WHY V960 AND WHAT IT COSTS. Seeds are numbered above every real migration so they cannot be
-- mistaken for one, which means prod now needs `out-of-order: true` to accept the next V-N -- the
-- same allowance local and testprod already carry, and the reason `application-prod.yml` no longer
-- claims to keep the strict default. That is the price of a seed tree; it is stated there in full.

-- PREREQUISITE, CHECKED RATHER THAN ASSUMED.
--
-- The brand is resolved by SLUG below, never hardcoded: a brand id differs per database and a wrong
-- one writes six people into somebody else's tenant. But a slug that resolves to nothing makes the
-- INSERT a silent zero-row no-op, and Flyway then records V960 as applied -- so the desks would be
-- permanently missing with nothing to show for it. That is the failure this block exists to convert
-- into a loud one.
--
-- An EMPTY `brand` table is the exception and is not an error: that is a database being bootstrapped,
-- where brands have not been created yet and there is nothing for these six to belong to. A
-- POPULATED `brand` table missing this slug is a misconfiguration, and a deploy should stop.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM brand)
       AND NOT EXISTS (SELECT 1 FROM brand WHERE slug = 'international-evaluations') THEN
        RAISE EXCEPTION
            'V960 expected a brand with slug `international-evaluations` and found none, in a '
            'database that already holds other brands. Create the brand first, or correct the slug; '
            'seeding nothing here would mark this migration applied with the six desks missing.';
    END IF;
END
$$;

INSERT INTO team_member (id, brand_id, team_id, role, segment, ghl_pipeline_id,
                         email, password_hash, display_name, reports_to, active)
SELECT gen_random_uuid(), b.id, NULL, d.role, d.segment, d.pipeline, d.email,
       -- Resolved from DESK_PASSWORD_HASH. Never a literal -- see the header.
       '${desk-password-hash}',
       d.display_name,
       -- LEFT NULL DELIBERATELY. `docs/seed-desks.sql` resolved this from `bm.ie@evalos.local`, which
       -- is a LOCAL seed address that does not exist in production -- so it resolved to NULL there
       -- anyway, just silently. `reports_to` is nullable and nothing reads it for these roles; a GM
       -- sets it once the real manager has a login.
       NULL, true
  FROM (VALUES
        -- --- Sales: one service pipeline each ------------------------------------------------
        ('SALES',     'ATTORNEY',      'Eu5LMyN0ioAVXJgW1JGT', 'sales-1@internationalevaluations.com', 'Sales 1'),
        ('SALES',     'EMPLOYER_FIRM', '0kT0EC0zVKdP1fklmrFC', 'sales-2@internationalevaluations.com', 'Sales 2'),
        ('SALES',     'INDIVIDUAL',    'EoVInfnvXguEkg7wWktg', 'sales-3@internationalevaluations.com', 'Sales 3'),

        -- --- BDE: the three BDE pipelines ----------------------------------------------------
        --
        -- MARKETING rather than SALES. In this system that is not a label: MARKETING opens a LEAD
        -- (`MarketingLeadService.openLead`, an upsert on contact + pipeline, so a repeat enquiry
        -- updates the open one) and SALES opens a DEAL (`SalesDeskService.createDeal`, a true
        -- create) and gets meetings, follow-ups and close. Business development is the first.
        ('MARKETING', 'INDIVIDUAL',    'CYcHdfPovQpxxNlIIto8', 'bde-1@internationalevaluations.com',   'BDE 1'),
        ('MARKETING', 'ATTORNEY',      'q9z45efXKXM3VjhtBnwZ', 'bde-2@internationalevaluations.com',   'BDE 2'),
        ('MARKETING', 'EMPLOYER_FIRM', 'ih8dMuHEiFfzSi2rVBrN', 'bde-3@internationalevaluations.com',   'BDE 3')
       ) AS d(role, segment, pipeline, email, display_name)
  -- The pipeline ids were read from GHL location WY6bW2xUCI8Tz8gw7aLJ on 2026-09-19. They are ACCESS
  -- KEYS, opaque and per-location: if that sub-account is ever replaced, all six change and this
  -- script seeds six people who can see nothing. V909 is the precedent for repointing them.
  CROSS JOIN (SELECT id FROM brand WHERE slug = 'international-evaluations') AS b
 -- Idempotent: a second migrate against a restored database adds nobody. `team_member.email` is
 -- UNIQUE, so the duplicate would be an error rather than a duplicate row; this makes it a no-op.
 WHERE NOT EXISTS (
        SELECT 1 FROM team_member t WHERE t.brand_id = b.id AND lower(t.email) = lower(d.email));

-- The GRANT that actually decides what they can see, and it is NOT the column above.
--
-- V54 moved the authority to `team_member_pipeline`, a real foreign key into the mirrored `pipeline`
-- table; `team_member.ghl_pipeline_id` survives as a legacy column that the CHECK above still
-- requires. This is V54's own backfill, run again for the rows just written.
--
-- ON A FRESH DATABASE THIS MATCHES NOTHING, and that is correct rather than a gap: `pipeline` is
-- populated by the PIPELINE_MIRROR sweep, which has not run at migrate time. The six then hold a
-- pipeline id and no grant -- they sign in and see an empty board until a GM assigns them from the
-- mirrored list (`PUT /api/team-members/{id}/pipelines`), which is the flow V54 introduced. Running
-- the sweep and re-running this statement by hand is the other way there.
INSERT INTO team_member_pipeline (team_member_id, pipeline_id)
SELECT m.id, p.id
  FROM team_member m
  JOIN pipeline p ON p.ghl_id = m.ghl_pipeline_id AND p.brand_id = m.brand_id
 WHERE m.ghl_pipeline_id IS NOT NULL
   AND m.email LIKE '%@internationalevaluations.com'
ON CONFLICT DO NOTHING;
