-- Local dev seed. Applied only under the `local` profile, which is the only
-- one whose flyway.locations lists this directory. Never seed a real
-- environment from here.
--
-- Every login below uses the password: DevPassw0rd!
-- (BCrypt cost 10; the same hash is reused because these are throwaway logins.)

INSERT INTO brand (id, name, slug, webhook_endpoint_token) VALUES
    ('11111111-1111-1111-1111-111111111111', 'International Evaluations', 'international-evaluations', 'local-ie-webhook-token'),
    ('22222222-2222-2222-2222-222222222222', 'XpertsPortal', 'xpertsportal', 'local-xp-webhook-token');

-- The GM is the one member with no brand: the only cross-brand reader.
INSERT INTO team_member (id, brand_id, team_id, role, email, password_hash, display_name, reports_to) VALUES
    ('aaaaaaaa-0000-0000-0000-000000000001', NULL,
     NULL, 'GM', 'gm@evalos.local',
     '$2a$10$r5HWTZRMQLgLPJKNHaZGgujwqeEjBbsDR8dpmh6JuZ7QdUjE1DHMW', 'Grace Moreau', NULL),

    ('aaaaaaaa-0000-0000-0000-000000000002', '11111111-1111-1111-1111-111111111111',
     NULL, 'BRAND_MANAGER', 'bm.ie@evalos.local',
     '$2a$10$r5HWTZRMQLgLPJKNHaZGgujwqeEjBbsDR8dpmh6JuZ7QdUjE1DHMW', 'Brandon Iyer', NULL),

    ('aaaaaaaa-0000-0000-0000-000000000003', '22222222-2222-2222-2222-222222222222',
     NULL, 'BRAND_MANAGER', 'bm.xp@evalos.local',
     '$2a$10$r5HWTZRMQLgLPJKNHaZGgujwqeEjBbsDR8dpmh6JuZ7QdUjE1DHMW', 'Bianca Xu', NULL),

    ('aaaaaaaa-0000-0000-0000-000000000004', '11111111-1111-1111-1111-111111111111',
     'bbbbbbbb-0000-0000-0000-000000000001', 'PROJECT_MANAGER', 'pm.ie@evalos.local',
     '$2a$10$r5HWTZRMQLgLPJKNHaZGgujwqeEjBbsDR8dpmh6JuZ7QdUjE1DHMW', 'Priya Menon',
     'aaaaaaaa-0000-0000-0000-000000000002'),

    ('aaaaaaaa-0000-0000-0000-000000000005', '11111111-1111-1111-1111-111111111111',
     'bbbbbbbb-0000-0000-0000-000000000001', 'CASE_MANAGER', 'cm.ie@evalos.local',
     '$2a$10$r5HWTZRMQLgLPJKNHaZGgujwqeEjBbsDR8dpmh6JuZ7QdUjE1DHMW', 'Chris Mabry',
     'aaaaaaaa-0000-0000-0000-000000000004');
