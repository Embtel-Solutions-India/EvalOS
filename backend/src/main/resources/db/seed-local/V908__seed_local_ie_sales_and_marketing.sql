-- International Evaluations' sales and marketing desks — the five logins Units 36–41 need.
--
-- **Why this was missing until now.** Units 36–41 shipped the roles, the pipeline access model,
-- the board and both desks, and every one of them is tested — but no seeded login carried `SALES`
-- or `MARKETING`, so nobody could open the board and look at it. Built and unreachable is a
-- distinction worth closing on a laptop.
--
-- **IE only, and that is enforced rather than described.** `evalos.ghl.sales-brand` names
-- International Evaluations, and `PipelineAssignmentService` answers 400 for a pipeline-scoped
-- role on any other brand. XpertsPortal gets no sales or marketing desk here and cannot be given
-- one until it has its own GHL location (Unit 25). That is Unit 36 §4a's single-brand ceiling, and
-- these rows sit inside it rather than beside it.
--
-- **The pipeline ids are real, read from the live location on 2026-09-11.** They are ids, not
-- names, because `ghl_pipeline_id` is an access key: a key that breaks when somebody renames a
-- pipeline in GHL locks a salesperson out of their own desk. (Note `Aditya's  pipeline` carries
-- two spaces in GHL — one more reason the id is what is stored.)
--
-- Same throwaway password as every other seeded login: DevPassw0rd!

INSERT INTO team_member (id, brand_id, team_id, role, segment, ghl_pipeline_id,
                         email, password_hash, display_name, reports_to) VALUES

    -- --- Sales: three people, three personal pipelines -----------------------------------
    --
    -- One pipeline each, which is the whole access model: `ScopePredicate`'s PIPELINE tier reads
    -- `ghl_pipeline_id` and nothing else, so these three see three disjoint boards and cannot see
    -- each other's deals. `uq_team_member_pipeline` is what stops two of them being pointed at one
    -- pipeline by mistake.
    --
    -- `segment` is display and reporting ONLY. It grants nothing, filters nothing, and must never
    -- start to — the three kinds carry identical permissions, which is why they are a column and
    -- not six roles.

    -- Aditya — Attorney. The oldest and richest pipeline (9 stages, including `Invoice sent` and
    -- `Refund`), which is why it maps to the segment that invoices most.
    ('aaaaaaaa-0000-0000-0000-000000000010',
     '11111111-1111-1111-1111-111111111111',
     NULL, 'SALES', 'ATTORNEY', 'tj2agZ90S1LQgCpDAoKi',
     'sales.attorney.ie@evalos.local',
     '$2a$10$r5HWTZRMQLgLPJKNHaZGgujwqeEjBbsDR8dpmh6JuZ7QdUjE1DHMW', 'Aditya (Attorney desk)',
     'aaaaaaaa-0000-0000-0000-000000000002'),

    -- Alex — Employer / Firm.
    ('aaaaaaaa-0000-0000-0000-000000000011',
     '11111111-1111-1111-1111-111111111111',
     NULL, 'SALES', 'EMPLOYER_FIRM', 'QKoDuXSh1EMEeDgOvcub',
     'sales.employer.ie@evalos.local',
     '$2a$10$r5HWTZRMQLgLPJKNHaZGgujwqeEjBbsDR8dpmh6JuZ7QdUjE1DHMW', 'Alex (Employer/Firm desk)',
     'aaaaaaaa-0000-0000-0000-000000000002'),

    -- Junaid — Individual.
    ('aaaaaaaa-0000-0000-0000-000000000012',
     '11111111-1111-1111-1111-111111111111',
     NULL, 'SALES', 'INDIVIDUAL', '3TWonApNvGEp1f7TmRfd',
     'sales.individual.ie@evalos.local',
     '$2a$10$r5HWTZRMQLgLPJKNHaZGgujwqeEjBbsDR8dpmh6JuZ7QdUjE1DHMW', 'Junaid (Individual desk)',
     'aaaaaaaa-0000-0000-0000-000000000002'),

    -- --- Marketing: two people, because GHL has two marketing pipelines -------------------
    --
    -- **Two, not three, and the third is absent on purpose.** The programme describes three
    -- marketing users mirroring the three sales segments, but the live location has exactly two
    -- marketing funnels. Pointing two people at one pipeline would give them an identical board
    -- and quietly break "a pipeline is who you are" — and `uq_team_member_pipeline` would refuse
    -- it anyway. The `EMPLOYER_FIRM` marketing desk is seeded the day that pipeline exists in GHL.
    --
    -- **`Ayush's Professors Pipeline` and `Master Pipeline` are deliberately unassigned.** The
    -- first is expert recruitment, which is the Expert Network Manager's world and not a sales
    -- funnel; the second has no stated owner. Assigning a pipeline is a GM decision
    -- (`PUT /api/team-members/{id}/ghl-pipeline`), not a guess in a seed file.

    -- Google Ads brings individuals looking for their own evaluation.
    ('aaaaaaaa-0000-0000-0000-000000000013',
     '11111111-1111-1111-1111-111111111111',
     NULL, 'MARKETING', 'INDIVIDUAL', 'g6lo50r9Wn0qZvmp2bMP',
     'marketing.individual.ie@evalos.local',
     '$2a$10$r5HWTZRMQLgLPJKNHaZGgujwqeEjBbsDR8dpmh6JuZ7QdUjE1DHMW', 'Google Ads desk',
     'aaaaaaaa-0000-0000-0000-000000000002'),

    -- Shivangi's outbound email, mapped to the attorney segment.
    ('aaaaaaaa-0000-0000-0000-000000000014',
     '11111111-1111-1111-1111-111111111111',
     NULL, 'MARKETING', 'ATTORNEY', 'LHoIRjpypwhswqO8Ayn0',
     'marketing.attorney.ie@evalos.local',
     '$2a$10$r5HWTZRMQLgLPJKNHaZGgujwqeEjBbsDR8dpmh6JuZ7QdUjE1DHMW', 'Shivangi (Email marketing)',
     'aaaaaaaa-0000-0000-0000-000000000002');
