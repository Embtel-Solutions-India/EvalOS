-- Test-production seed: the two brands and the staff logins, and nothing else.
--
-- A REAL environment is seeded from here, so it follows two rules the local seed does
-- not. First, it lives in `db/seed-testprod` — a SIBLING of `db/migration`, never a
-- child, because Flyway scans a location and every sub-directory below it and there is
-- no exclude filter (see db/seed-local/README.md; MigrationTreeTest enforces the
-- layout). Only `application-testprod.yml` lists this directory, so a plain `prod` boot
-- never sees it. Second, NO CREDENTIAL IS WRITTEN HERE. The three values that are
-- credentials arrive as Flyway placeholders resolved from the environment at migrate
-- time, so this file can be read by anyone with the repository and still grants nothing.
--
-- Deliberately NOT reusing the local seed's rows or ids: those brands, logins and the
-- BCrypt hash behind them are committed to this repository, so a test-production
-- database seeded from them would be readable by anyone who can clone it.
--
-- No experts, no cases, no payouts. Cases arrive through Handoff A (the GHL webhook)
-- and the roster arrives through Unit 11's sheet upload; seeding either would put rows
-- in front of the paths this environment exists to test.

-- Both brands. `webhook_endpoint_token` IS the whole webhook credential — there is no
-- signature step, because GHL's Custom Webhook action cannot compute one (see
-- WebhookGateway) — so it is unguessable and comes from the environment. It also travels
-- in the URL path (POST /api/webhooks/ghl/{token}), which is why the generated value is
-- hex.
--
-- `ghl_webhook_secret` is left NULL on purpose and that is not an omission: no Java code
-- reads it today, and a NULL fails closed if something starts to.
--
-- `currency` is set inline rather than by a follow-up script. V904 exists only because
-- the local seed predates the column; a new seed has no such history to work around.
INSERT INTO brand (id, name, slug, webhook_endpoint_token, currency) VALUES
    ('33333333-3333-3333-3333-333333333333', 'International Evaluations',
     'international-evaluations', '${ie-webhook-token}', 'USD'),
    ('44444444-4444-4444-4444-444444444444', 'XpertsPortal',
     'xpertsportal', '${xp-webhook-token}', 'USD');

-- One login per role, on BOTH brands, because brand scoping is the invariant this
-- environment is here to exercise and a brand with no staff cannot exercise it. The GM
-- is the exception and must stay one row: brand_id NULL is the single cross-brand
-- reader, which V3's team_member_brand_required CHECK allows for this role alone.
--
-- Every row shares ONE BCrypt hash supplied from the environment. That is the same
-- shape as the local seed and the same trade — these are throwaway test logins — with
-- the one difference that matters: the hash is not in this file, so rotating it is an
-- environment change and reading this repository does not hand anyone an account.
INSERT INTO team_member (id, brand_id, team_id, role, email, password_hash, display_name, reports_to) VALUES
    ('eeeeeeee-0000-0000-0000-000000000001', NULL, NULL,
     'GM', 'gm@testprod.evalos.local', '${seed-password-hash}', 'Test GM', NULL),

    -- International Evaluations
    ('eeeeeeee-0000-0000-0000-000000000002', '33333333-3333-3333-3333-333333333333', NULL,
     'BRAND_MANAGER', 'bm.ie@testprod.evalos.local', '${seed-password-hash}', 'Test BM (IE)', NULL),
    ('eeeeeeee-0000-0000-0000-000000000003', '33333333-3333-3333-3333-333333333333',
     'dddddddd-0000-0000-0000-000000000001',
     'PROJECT_MANAGER', 'pm.ie@testprod.evalos.local', '${seed-password-hash}', 'Test PM (IE)',
     'eeeeeeee-0000-0000-0000-000000000002'),
    ('eeeeeeee-0000-0000-0000-000000000004', '33333333-3333-3333-3333-333333333333',
     'dddddddd-0000-0000-0000-000000000001',
     'PROJECT_COORDINATOR', 'pc.ie@testprod.evalos.local', '${seed-password-hash}', 'Test PC (IE)',
     'eeeeeeee-0000-0000-0000-000000000003'),
    ('eeeeeeee-0000-0000-0000-000000000005', '33333333-3333-3333-3333-333333333333',
     'dddddddd-0000-0000-0000-000000000001',
     'CASE_MANAGER', 'cm.ie@testprod.evalos.local', '${seed-password-hash}', 'Test CM (IE)',
     'eeeeeeee-0000-0000-0000-000000000003'),
    ('eeeeeeee-0000-0000-0000-000000000006', '33333333-3333-3333-3333-333333333333', NULL,
     'EXPERT_NETWORK_MANAGER', 'enm.ie@testprod.evalos.local', '${seed-password-hash}', 'Test ENM (IE)',
     'eeeeeeee-0000-0000-0000-000000000002'),

    -- XpertsPortal. Its own team id, so a team-scoped read that leaked across brands
    -- would show up as wrong rows rather than as no rows.
    ('eeeeeeee-0000-0000-0000-000000000007', '44444444-4444-4444-4444-444444444444', NULL,
     'BRAND_MANAGER', 'bm.xp@testprod.evalos.local', '${seed-password-hash}', 'Test BM (XP)', NULL),
    ('eeeeeeee-0000-0000-0000-000000000008', '44444444-4444-4444-4444-444444444444',
     'dddddddd-0000-0000-0000-000000000002',
     'PROJECT_MANAGER', 'pm.xp@testprod.evalos.local', '${seed-password-hash}', 'Test PM (XP)',
     'eeeeeeee-0000-0000-0000-000000000007'),
    ('eeeeeeee-0000-0000-0000-000000000009', '44444444-4444-4444-4444-444444444444',
     'dddddddd-0000-0000-0000-000000000002',
     'PROJECT_COORDINATOR', 'pc.xp@testprod.evalos.local', '${seed-password-hash}', 'Test PC (XP)',
     'eeeeeeee-0000-0000-0000-000000000008'),
    ('eeeeeeee-0000-0000-0000-00000000000a', '44444444-4444-4444-4444-444444444444',
     'dddddddd-0000-0000-0000-000000000002',
     'CASE_MANAGER', 'cm.xp@testprod.evalos.local', '${seed-password-hash}', 'Test CM (XP)',
     'eeeeeeee-0000-0000-0000-000000000008'),
    ('eeeeeeee-0000-0000-0000-00000000000b', '44444444-4444-4444-4444-444444444444', NULL,
     'EXPERT_NETWORK_MANAGER', 'enm.xp@testprod.evalos.local', '${seed-password-hash}', 'Test ENM (XP)',
     'eeeeeeee-0000-0000-0000-000000000007');
