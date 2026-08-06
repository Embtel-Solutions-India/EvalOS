-- The two roles V900 never seeded: Project Coordinator and Expert Network Manager.
--
-- Added because Unit 07's first acceptance criterion is "logging in as each of the six
-- roles shows the correct nav set", and four of six is not that. It also makes the
-- Coordinator scope gap reproducible instead of theoretical: this login can reach the
-- shell, and its Coordinator-gated case routes answer 403 for the reason recorded in
-- the tracker's open questions.
--
-- Same throwaway password as every other seeded login: DevPassw0rd!
-- Same team as the IE Project Manager, so team-scoped reads line up.
INSERT INTO team_member (id, brand_id, team_id, role, email, password_hash, display_name, reports_to) VALUES
    ('aaaaaaaa-0000-0000-0000-000000000006',
     '11111111-1111-1111-1111-111111111111',
     'bbbbbbbb-0000-0000-0000-000000000001', 'PROJECT_COORDINATOR', 'pc.ie@evalos.local',
     '$2a$10$r5HWTZRMQLgLPJKNHaZGgujwqeEjBbsDR8dpmh6JuZ7QdUjE1DHMW', 'Priya Chandra',
     'aaaaaaaa-0000-0000-0000-000000000004'),

    ('aaaaaaaa-0000-0000-0000-000000000007',
     '11111111-1111-1111-1111-111111111111',
     NULL, 'EXPERT_NETWORK_MANAGER', 'enm.ie@evalos.local',
     '$2a$10$r5HWTZRMQLgLPJKNHaZGgujwqeEjBbsDR8dpmh6JuZ7QdUjE1DHMW', 'Ehsan Nazari',
     'aaaaaaaa-0000-0000-0000-000000000002');
