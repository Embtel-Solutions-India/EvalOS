-- Unit 36 — pipeline-scoped access.
--
-- Two roles come back, SALES and MARKETING, and `team_member` gains the two columns that describe
-- one: `ghl_pipeline_id`, which is the entire access predicate, and `segment`, which is not an
-- access key at all. Spec: context/specs/36-pipeline-scoped-access.md; the programme decisions it
-- inherits are in context/specs/00b-ghl-operational-programme.md.
--
-- Nothing here reads GHL. This migration decides who *may* see a pipeline; Unit 38 is the first
-- thing that fetches one, and invariant 2 stays live until Unit 37 adds the first write verb.

-- The list is rewritten for the third time (V3 six, V29 seven, V30 back to six, now eight). It has
-- to be: this CHECK is the writer the enum cannot reach — a seed script, a hand-run UPDATE — and a
-- row holding a role `Role.valueOf` cannot express breaks everything that reads it.
ALTER TABLE team_member
    DROP CONSTRAINT team_member_role_valid,
    ADD CONSTRAINT team_member_role_valid CHECK (role IN (
        'GM', 'BRAND_MANAGER', 'PROJECT_MANAGER',
        'PROJECT_COORDINATOR', 'CASE_MANAGER', 'EXPERT_NETWORK_MANAGER',
        'SALES', 'MARKETING'
    ));

-- The one GHL pipeline this employee owns.
--
-- **The id, not the name**, and this is the one place that departs from `evalos.ghl.*`. Those
-- properties match a pipeline by name so a rename breaks a dashboard loudly, which is right for a
-- dashboard and wrong for an access key: an access key that breaks on a rename locks an employee
-- out of their own work. A 502 on the GM's funnel chart is a bad afternoon; a salesperson who
-- cannot see their pipeline is a stopped desk. The name is never stored — the board shows whatever
-- GHL returns for the id.
ALTER TABLE team_member ADD COLUMN ghl_pipeline_id text;

-- Which kind of client this employee handles. Display and reporting only.
--
-- **This is not an access key and must never become one.** The three kinds carry identical
-- permissions, which is exactly why they are a column instead of six more roles: six enum values
-- would have grown every `switch (role)`, this CHECK, the nav tests and the permission matrix to
-- express a distinction that changes no permission. A `segment` that starts filtering rows is an
-- org chart smuggled into a security model — the day one needs a different permission it is a new
-- role, argued in the programme spec first.
ALTER TABLE team_member ADD COLUMN segment text;

-- Both CHECKs say both halves, deliberately.
--
-- A sales row without a pipeline is a person who can see nothing; a Case Manager *with* one is a
-- column whose meaning has started to drift. **That second half is the mistake V29 made and V30
-- recorded**: V29 widened `team_member_brand_required` for a role whose NULL meant the opposite of
-- the existing NULL, leaving one column with two readings and no way to tell them apart.
ALTER TABLE team_member
    ADD CONSTRAINT team_member_pipeline_matches_role CHECK (
        (role IN ('SALES', 'MARKETING') AND ghl_pipeline_id IS NOT NULL)
        OR (role NOT IN ('SALES', 'MARKETING') AND ghl_pipeline_id IS NULL)),
    ADD CONSTRAINT team_member_segment_matches_role CHECK (
        (role IN ('SALES', 'MARKETING')
            -- **`IS NOT NULL` before the IN, and it is load-bearing rather than belt-and-braces.**
            -- `NULL IN ('ATTORNEY', ...)` evaluates to NULL, not FALSE, and a CHECK that evaluates
            -- to NULL *passes* in Postgres. Without this clause the whole expression is
            -- `NULL OR FALSE` = NULL for a SALES row with no segment — so the constraint would
            -- have silently permitted the one case it exists to forbid.
            --
            -- The pipeline CHECK above escapes the same trap only because `IS NOT NULL` /
            -- `IS NULL` never yield NULL. This was caught by LocalPostgresIntegrationTest and by
            -- nothing else: it is invisible in review and invisible to every test that does not
            -- run against a real Postgres.
            AND segment IS NOT NULL
            AND segment IN ('ATTORNEY', 'EMPLOYER_FIRM', 'INDIVIDUAL'))
        OR (role NOT IN ('SALES', 'MARKETING') AND segment IS NULL));

-- `team_member_brand_required` needs no change: only the GM may have a null brand, and neither new
-- role is cross-brand. One fewer constraint rewrite than V29 needed, and that is not luck — it is
-- what picking a tier whose NULL means the same thing as every other tier's NULL buys you.

-- One live pipeline per employee, and one employee per pipeline.
--
-- **Globally unique, and this is the one index in the schema deliberately not led by `brand_id`.**
-- Everything else here leads with the brand because EvalOS rows belong to a brand. A GHL pipeline
-- does not: `evalos.ghl.location-id` is a single global setting with no link to a brand
-- (architecture.md invariant 1's one stated exception). One location means one pipeline namespace,
-- so a pipeline id appearing under two brands would not be two pipelines — it would be **the same
-- pipeline read by two people who cannot see each other**. Adding `brand_id` here would make that
-- silently legal.
--
-- Partial on `active` so a leaver's row does not block their replacement inheriting the pipeline.
CREATE UNIQUE INDEX uq_team_member_pipeline
    ON team_member (ghl_pipeline_id)
    WHERE ghl_pipeline_id IS NOT NULL AND active;
