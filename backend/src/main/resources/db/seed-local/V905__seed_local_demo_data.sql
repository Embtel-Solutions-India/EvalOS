-- A coherent EvalOS world for a local demo: a roster with history, a case board with
-- something in every column, and enough closed work behind it that the figures on the
-- dashboard have a past to be measured against.
--
-- WHY THIS FILE DELETES FIRST. A dev database accumulates. This one held 69 experts,
-- 165 cases and 33 contacts left behind by integration tests that ran in `public`
-- before they were moved to the `evalos_test` schema, plus a scatter of hand-made
-- probe rows ("Jane Doe", "Probe Two"). None of it was anybody's data and all of it
-- was on screen. Seeding around it would have left the demo showing both.
--
-- ONLY `brand` and `team_member` survive. The seven logins from V900/V902 are the way
-- in and are never touched; everything hanging off them is rebuilt here.
--
-- THIS TREE NEVER SHIPS. `db/seed-local` is a sibling of `db/migration`, not a child,
-- because Flyway scans recursively and has no exclude filter -- see the README beside
-- this file. A production boot lists `classpath:db/migration` alone and never sees it.
--
-- Dates are relative to `now()`, so the board still reads as current next month: SLA
-- colours, the "onboarded this month" count and the payment history move with the
-- clock instead of ageing into a fixed calendar.

-- ---------------------------------------------------------------------------
-- 1. Clear. FK order, innermost first: every constraint here is NO ACTION, so
--    nothing cascades and the order is the correctness.
-- ---------------------------------------------------------------------------
DELETE FROM payout_ledger;
DELETE FROM payout_payment;
DELETE FROM portal_access;
DELETE FROM document_checklist_item;
DELETE FROM expert_case_offer;
DELETE FROM notification;
DELETE FROM evalos_case;
DELETE FROM contact_snapshot;
DELETE FROM expert;
DELETE FROM webhook_event;
DELETE FROM ghl_funnel_cache;

-- `audit_event` is append-only and enforces it with a BEFORE DELETE trigger, which is
-- right for the application and wrong for a dev reset: these rows describe cases that
-- no longer exist. Disabled for one statement and turned straight back on. This is the
-- only place in the codebase that does it and it is not a precedent -- application
-- code must never delete an audit row.
ALTER TABLE audit_event DISABLE TRIGGER audit_event_no_mutation;
DELETE FROM audit_event;
ALTER TABLE audit_event ENABLE TRIGGER audit_event_no_mutation;

-- ---------------------------------------------------------------------------
-- 2. The roster.
--
--    Availability covers all four states, so the board has no empty column and the
--    health tile has a figure in every row. Two experts are onboarded inside the
--    current month, so "Onboarded this month" is a live number against its target
--    rather than a zero. Quality scores include two below the 6.0 bar and one NULL:
--    unscored is unassessed, not bad, and the low-quality tile has to keep them apart.
--
--    `payment_detail` stays NULL on every row. It is AES-GCM ciphertext written only
--    by PaymentDetailConverter, so a hand-written value would fail to decrypt the
--    first time a screen touched it. The roster reads "no payment detail on file",
--    which is true.
--
--    `total_cases_completed`, `current_active_count` and `total_payments_pending` keep
--    their V7 defaults. Load and money owed are derived from evalos_case and
--    payout_ledger; filling them here would only make a dead column look alive.
-- ---------------------------------------------------------------------------
INSERT INTO expert (
    id, brand_id, full_name, title, institution, email, phone,
    primary_fields, secondary_fields, letter_types,
    availability, tier, quality_score, standard_fee, performance_flags,
    agreement_status, payment_status, recruitment_source, date_onboarded, notes, created_at
) VALUES
    -- International Evaluations
    ('e0000000-0000-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111',
     'Dr Miriam Osei', 'Professor of Mechanical Engineering', 'Rowan State University',
     'm.osei@rowanstate.test', '+1-202-555-0111',
     ARRAY['MECHANICAL_ENGINEERING'], ARRAY['MATHEMATICS','PHYSICS'],
     ARRAY['EXPERT_OPINION_LETTER','RFE_RESPONSE'],
     'AVAILABLE', 'TIER_1', 9.2, 350.00, NULL,
     'SIGNED', 'UP_TO_DATE', 'Referral', (now() - interval '29 months')::date,
     'Fast on RFEs. Prefers two weeks notice for a full opinion.', now() - interval '29 months'),

    ('e0000000-0000-0000-0000-000000000002', '11111111-1111-1111-1111-111111111111',
     'Dr Alan Whitcombe', 'Associate Professor of Computer Science', 'Lakeside Institute of Technology',
     'a.whitcombe@lakeside.test', '+1-202-555-0112',
     ARRAY['COMPUTER_SCIENCE','DATA_SCIENCE'], ARRAY['INFORMATION_TECHNOLOGY'],
     ARRAY['EXPERT_OPINION_LETTER','PERM_LETTER'],
     'AT_CAPACITY', 'TIER_2', 8.0, 300.00, NULL,
     'SIGNED', 'UP_TO_DATE', 'Cold outreach', (now() - interval '23 months')::date,
     'Says no early rather than late, which is worth more than saying yes.', now() - interval '23 months'),

    ('e0000000-0000-0000-0000-000000000003', '11111111-1111-1111-1111-111111111111',
     'Professor Ada Nwankwo', 'Professor of Civil Engineering', 'Harbour City University',
     'a.nwankwo@harbourcity.test', '+1-202-555-0113',
     ARRAY['CIVIL_ENGINEERING'], ARRAY['ARCHITECTURE'],
     ARRAY['EXPERT_OPINION_LETTER','RFE_RESPONSE','PERM_LETTER'],
     'AVAILABLE', 'TIER_1', 9.5, 400.00, NULL,
     'SIGNED', 'UP_TO_DATE', 'Referral', (now() - interval '37 months')::date,
     'The one to send an RFE with a short fuse.', now() - interval '37 months'),

    ('e0000000-0000-0000-0000-000000000004', '11111111-1111-1111-1111-111111111111',
     'Dr Sofia Marchetti', 'Reader in Economics', 'Northgate School of Economics',
     's.marchetti@northgate.test', '+1-202-555-0114',
     ARRAY['ECONOMICS','FINANCE'], ARRAY['BUSINESS_ADMINISTRATION'],
     ARRAY['CREDENTIAL_EVALUATION','EXPERT_OPINION_LETTER'],
     'ON_LEAVE', 'TIER_2', 8.4, 320.00, NULL,
     'SIGNED', 'PENDING', 'Conference', (now() - interval '18 months')::date,
     'On research leave until the spring; do not offer.', now() - interval '18 months'),

    ('e0000000-0000-0000-0000-000000000005', '11111111-1111-1111-1111-111111111111',
     'Dr Tomas Herrera', 'Clinical Professor of Pharmacy', 'Westbrook College of Pharmacy',
     't.herrera@westbrookpharm.test', NULL,
     ARRAY['PHARMACY'], ARRAY['MEDICINE'],
     ARRAY['CREDENTIAL_EVALUATION'],
     'INACTIVE', 'TIER_3', 5.4, 240.00, ARRAY['SLOW_RESPONSE','QUALITY_ISSUE'],
     'EXPIRED', 'OVERDUE', 'Cold outreach', (now() - interval '34 months')::date,
     'Agreement lapsed. Two drafts came back needing rework; not offered since.', now() - interval '34 months'),

    ('e0000000-0000-0000-0000-000000000006', '11111111-1111-1111-1111-111111111111',
     'Dr Nadia Haddad', 'Associate Professor of Nursing', 'St Aubin School of Nursing',
     'n.haddad@staubin.test', '+1-202-555-0116',
     ARRAY['NURSING'], ARRAY['PUBLIC_HEALTH'],
     ARRAY['CREDENTIAL_EVALUATION','EXPERT_OPINION_LETTER'],
     'AVAILABLE', 'TIER_1', 8.9, 330.00, NULL,
     'SIGNED', 'UP_TO_DATE', 'Referral', (now() - interval '10 months')::date,
     NULL, now() - interval '10 months'),

    -- Onboarded inside the current month: the ENM onboarding tile counts these two.
    ('e0000000-0000-0000-0000-000000000007', '11111111-1111-1111-1111-111111111111',
     'Dr Yusuf Karim', 'Senior Lecturer in Law', 'Kingsbridge Law School',
     'y.karim@kingsbridge.test', '+1-202-555-0117',
     ARRAY['LAW'], NULL,
     ARRAY['EXPERT_OPINION_LETTER','RFE_RESPONSE'],
     'AVAILABLE', 'TIER_2', 7.6, 310.00, NULL,
     'SIGNED', 'UP_TO_DATE', 'LinkedIn', (date_trunc('month', now()) + interval '11 days')::date,
     'First case not yet offered.', date_trunc('month', now()) + interval '11 days'),

    ('e0000000-0000-0000-0000-000000000008', '11111111-1111-1111-1111-111111111111',
     'Dr Priya Raghunathan', 'Professor of Data Science', 'Calder Institute',
     'p.raghunathan@calder.test', NULL,
     ARRAY['DATA_SCIENCE','COMPUTER_SCIENCE'], ARRAY['MATHEMATICS'],
     ARRAY['EXPERT_OPINION_LETTER','PERM_LETTER'],
     -- No quality score: onboarded this month, nothing to score yet. The low-quality
     -- tile has to read this as unassessed rather than as a zero.
     'AVAILABLE', 'TIER_1', NULL, 360.00, NULL,
     'SIGNED', 'UP_TO_DATE', 'Referral', (date_trunc('month', now()) + interval '4 days')::date,
     NULL, date_trunc('month', now()) + interval '4 days'),

    ('e0000000-0000-0000-0000-000000000009', '11111111-1111-1111-1111-111111111111',
     'Dr Gregor Vance', 'Associate Professor of Chemical Engineering', 'Meridian Polytechnic',
     'g.vance@meridianpoly.test', '+1-202-555-0119',
     ARRAY['CHEMICAL_ENGINEERING'], ARRAY['CHEMISTRY'],
     ARRAY['EXPERT_OPINION_LETTER'],
     'AT_CAPACITY', 'TIER_2', 7.1, 295.00, NULL,
     'SIGNED', 'PENDING', 'Partner', (now() - interval '14 months')::date,
     NULL, now() - interval '14 months'),

    ('e0000000-0000-0000-0000-00000000000a', '11111111-1111-1111-1111-111111111111',
     'Dr Helena Brandt', 'Professor of Education', 'Ferngrove University',
     'h.brandt@ferngrove.test', NULL,
     ARRAY['EDUCATION'], ARRAY['PSYCHOLOGY'],
     ARRAY['CREDENTIAL_EVALUATION'],
     'AVAILABLE', 'TIER_3', 5.8, 220.00, ARRAY['DECLINED_CASES'],
     'SENT', 'PENDING', 'Cold outreach', (now() - interval '8 months')::date,
     'Agreement sent, not yet returned. Declined the last three offers.', now() - interval '8 months'),

    -- XpertsPortal
    ('e0000000-0000-0000-0000-00000000000b', '22222222-2222-2222-2222-222222222222',
     'Dr Petra Lindqvist', 'Professor of Public Health', 'Nordvik School of Public Health',
     'p.lindqvist@nordvik.test', '+44-20-7946-0801',
     ARRAY['PUBLIC_HEALTH'], ARRAY['BIOLOGY'],
     ARRAY['CREDENTIAL_EVALUATION','EXPERT_OPINION_LETTER'],
     'AVAILABLE', 'TIER_1', 9.1, 345.00, NULL,
     'SIGNED', 'UP_TO_DATE', 'Conference', (now() - interval '26 months')::date,
     NULL, now() - interval '26 months'),

    ('e0000000-0000-0000-0000-00000000000c', '22222222-2222-2222-2222-222222222222',
     'Dr Hassan Rahimi', 'Lecturer in Accounting', 'Silverpoint Business School',
     'h.rahimi@silverpoint.test', NULL,
     ARRAY['ACCOUNTING','FINANCE'], ARRAY['ECONOMICS'],
     ARRAY['CREDENTIAL_EVALUATION'],
     'AVAILABLE', 'TIER_3', 6.9, 250.00, NULL,
     'SIGNED', 'UP_TO_DATE', 'Website', (now() - interval '13 months')::date,
     NULL, now() - interval '13 months'),

    ('e0000000-0000-0000-0000-00000000000d', '22222222-2222-2222-2222-222222222222',
     'Dr Ingrid Halvorsen', 'Professor of Nursing', 'Fjordview College of Health',
     'i.halvorsen@fjordview.test', '+44-20-7946-0812',
     ARRAY['NURSING'], ARRAY['PUBLIC_HEALTH'],
     ARRAY['CREDENTIAL_EVALUATION','EXPERT_OPINION_LETTER'],
     'AT_CAPACITY', 'TIER_1', 9.0, 340.00, NULL,
     'SIGNED', 'UP_TO_DATE', 'Referral', (now() - interval '21 months')::date,
     NULL, now() - interval '21 months');

-- ---------------------------------------------------------------------------
-- 3. Contacts.
--
--    A snapshot is GHL's record, copied. Nothing in EvalOS edits one (invariant 7),
--    so `synced_at` is the only field that moves and it moves wholesale.
--    `ghl_contact_id` is the real identity -- V27 demoted email to a fallback -- so
--    every contact carries one, which is also what stops a redelivered webhook from
--    duplicating them.
--
--    One contact per case. `uq_case_open_per_contact_service` allows a contact only
--    one *open* case per service type, and giving each its own keeps the seed honest
--    about that rather than quietly relying on it.
-- ---------------------------------------------------------------------------
INSERT INTO contact_snapshot (
    id, brand_id, ghl_contact_id, full_name, email, phone, company, client_type,
    source_channel, utm_source, utm_medium, utm_campaign, date_first_captured, synced_at, created_at
)
-- The suffix is spelled out rather than taken from row_number(): the case rows below
-- address these contacts by it, and row_number() over a VALUES list has no promised
-- order to hand out. A wrong-but-valid UUID would wire a case to the wrong client.
SELECT ('c0000000-0000-0000-0000-0000000000' || suffix)::uuid,
       brand_id, ghl_id, full_name, email, phone, company, client_type, source_channel,
       utm_source, utm_medium, utm_campaign,
       now() - (age_days || ' days')::interval - interval '3 days',
       now() - interval '6 hours',
       now() - (age_days || ' days')::interval - interval '3 days'
  FROM (VALUES
    -- International Evaluations
    ('01', '11111111-1111-1111-1111-111111111111'::uuid, 'ghl-demo-0001', 'Amara Okafor',    'amara.okafor@northlightlaw.test',  '+1-312-555-0201', 'Northlight Immigration Law', 'ATTORNEY',   'REFERRAL',       'referral',  'word-of-mouth', NULL,             4),
    ('02', '11111111-1111-1111-1111-111111111111'::uuid, 'ghl-demo-0002', 'Rajiv Menon',     'r.menon@vertexsystems.test',       '+1-408-555-0202', 'Vertex Systems',             'EMPLOYER',   'WEBSITE',        'google',    'organic',       NULL,             9),
    ('03', '11111111-1111-1111-1111-111111111111'::uuid, 'ghl-demo-0003', 'Lucia Ferreira',  'lucia.ferreira@brightpath.test',   '+1-305-555-0203', 'Brightpath Consulting',      'AGENT',      'GOOGLE_ADS',     'google',    'cpc',           'h1b-eval-2026',  2),
    ('04', '11111111-1111-1111-1111-111111111111'::uuid, 'ghl-demo-0004', 'Daniel Whitfield','d.whitfield@whitfieldlegal.test',  '+1-617-555-0204', 'Whitfield Legal',            'ATTORNEY',   'LINKEDIN',       'linkedin',  'social',        NULL,            14),
    ('05', '11111111-1111-1111-1111-111111111111'::uuid, 'ghl-demo-0005', 'Mei-Ling Chen',   'meiling.chen@mailbox.test',        '+1-206-555-0205', NULL,                         'INDIVIDUAL', 'WEBSITE',        'direct',    'none',          NULL,             8),
    ('06', '11111111-1111-1111-1111-111111111111'::uuid, 'ghl-demo-0006', 'Ibrahim Sesay',   'i.sesay@atlascorp.test',           '+1-713-555-0206', 'Atlas Corp',                 'EMPLOYER',   'PARTNER',        'partner',   'referral',      NULL,             6),
    ('07', '11111111-1111-1111-1111-111111111111'::uuid, 'ghl-demo-0007', 'Sarah Kowalski',  's.kowalski@kowalskilaw.test',      '+1-312-555-0207', 'Kowalski Immigration',       'ATTORNEY',   'REFERRAL',       'referral',  'word-of-mouth', NULL,            16),
    ('08', '11111111-1111-1111-1111-111111111111'::uuid, 'ghl-demo-0008', 'Victor Nkemelu',  'v.nkemelu@mailbox.test',           NULL,              NULL,                         'INDIVIDUAL', 'GOOGLE_ADS',     'google',    'cpc',           'rfe-help-2026', 12),
    ('09', '11111111-1111-1111-1111-111111111111'::uuid, 'ghl-demo-0009', 'Elena Popescu',   'e.popescu@danubetech.test',        '+1-512-555-0209', 'Danube Technologies',        'EMPLOYER',   'EMAIL_CAMPAIGN', 'mailchimp', 'email',         'q3-employers',  18),
    ('10', '11111111-1111-1111-1111-111111111111'::uuid, 'ghl-demo-0010', 'Tunde Adeyinka',  't.adeyinka@mailbox.test',          '+1-404-555-0210', NULL,                         'INDIVIDUAL', 'INSTAGRAM',      'instagram', 'social',        NULL,            21),
    ('11', '11111111-1111-1111-1111-111111111111'::uuid, 'ghl-demo-0011', 'Sanjana Iyer',    's.iyer@meridianhr.test',           '+1-646-555-0211', 'Meridian HR',                'EMPLOYER',   'WEBSITE',        'direct',    'none',          NULL,            25),
    ('12', '11111111-1111-1111-1111-111111111111'::uuid, 'ghl-demo-0012', 'Marcus Bellamy',  'm.bellamy@bellamypartners.test',   '+1-202-555-0212', 'Bellamy and Partners',       'ATTORNEY',   'REFERRAL',       'referral',  'word-of-mouth', NULL,            27),
    ('13', '11111111-1111-1111-1111-111111111111'::uuid, 'ghl-demo-0013', 'Fatima Zahra',    'f.zahra@mailbox.test',             NULL,              NULL,                         'INDIVIDUAL', 'FACEBOOK',       'facebook',  'social',        NULL,            19),
    ('14', '11111111-1111-1111-1111-111111111111'::uuid, 'ghl-demo-0014', 'Nkechi Balogun',  'n.balogun@mailbox.test',           '+1-773-555-0214', NULL,                         'INDIVIDUAL', 'WEBSITE',        'direct',    'none',          NULL,            48),
    ('15', '11111111-1111-1111-1111-111111111111'::uuid, 'ghl-demo-0015', 'Hiroshi Tanaka',  'h.tanaka@sakuraglobal.test',       '+1-650-555-0215', 'Sakura Global',              'EMPLOYER',   'PARTNER',        'partner',   'referral',      NULL,            52),
    ('16', '11111111-1111-1111-1111-111111111111'::uuid, 'ghl-demo-0016', 'Priya Deshmukh',  'p.deshmukh@deshmukhlaw.test',      '+1-408-555-0216', 'Deshmukh Law',               'ATTORNEY',   'REFERRAL',       'referral',  'word-of-mouth', NULL,            82),
    ('17', '11111111-1111-1111-1111-111111111111'::uuid, 'ghl-demo-0017', 'Andre Dubois',    'a.dubois@lumierecorp.test',        '+1-514-555-0217', 'Lumiere Corp',               'EMPLOYER',   'LINKEDIN',       'linkedin',  'social',        NULL,            88),
    ('18', '11111111-1111-1111-1111-111111111111'::uuid, 'ghl-demo-0018', 'Grace Mwangi',    'g.mwangi@mailbox.test',            NULL,              NULL,                         'INDIVIDUAL', 'GOOGLE_ADS',     'google',    'cpc',           'eval-2026-q1', 112),
    ('19', '11111111-1111-1111-1111-111111111111'::uuid, 'ghl-demo-0019', 'Omar Farouk',     'o.farouk@harmattanhr.test',        '+1-832-555-0219', 'Harmattan HR',               'EMPLOYER',   'WEBSITE',        'direct',    'none',          NULL,           118),
    ('20', '11111111-1111-1111-1111-111111111111'::uuid, 'ghl-demo-0020', 'Isabel Navarro',  'i.navarro@navarrolegal.test',      '+1-305-555-0220', 'Navarro Legal',              'ATTORNEY',   'REFERRAL',       'referral',  'word-of-mouth', NULL,           146),
    ('21', '11111111-1111-1111-1111-111111111111'::uuid, 'ghl-demo-0021', 'Chen Wei',        'chen.wei@mailbox.test',            NULL,              NULL,                         'INDIVIDUAL', 'WHATSAPP',       'whatsapp',  'chat',          NULL,           176),
    ('22', '11111111-1111-1111-1111-111111111111'::uuid, 'ghl-demo-0022', 'Rebecca Stone',   'r.stone@stoneimmigration.test',    '+1-215-555-0222', 'Stone Immigration',          'ATTORNEY',   'REFERRAL',       'referral',  'word-of-mouth', NULL,           206),
    ('23', '11111111-1111-1111-1111-111111111111'::uuid, 'ghl-demo-0023', 'Kwame Asante',    'k.asante@mailbox.test',            '+1-678-555-0223', NULL,                         'INDIVIDUAL', 'EMAIL_CAMPAIGN', 'mailchimp', 'email',         'winter-2025',  236),
    ('24', '11111111-1111-1111-1111-111111111111'::uuid, 'ghl-demo-0024', 'Sofia Ramirez',   's.ramirez@mailbox.test',           NULL,              NULL,                         'INDIVIDUAL', 'INSTAGRAM',      'instagram', 'social',        NULL,           266),
    -- XpertsPortal
    ('25', '22222222-2222-2222-2222-222222222222'::uuid, 'ghl-demo-0025', 'Linnea Berg',     'l.berg@mailbox.test',              '+46-8-555-0225',  NULL,                         'INDIVIDUAL', 'WEBSITE',        'direct',    'none',          NULL,             3),
    ('26', '22222222-2222-2222-2222-222222222222'::uuid, 'ghl-demo-0026', 'Jonas Falk',      'j.falk@nordkraft.test',            '+46-8-555-0226',  'Nordkraft AB',               'EMPLOYER',   'LINKEDIN',       'linkedin',  'social',        NULL,            11),
    ('27', '22222222-2222-2222-2222-222222222222'::uuid, 'ghl-demo-0027', 'Astrid Holm',     'a.holm@mailbox.test',              NULL,              NULL,                         'INDIVIDUAL', 'GOOGLE_ADS',     'google',    'cpc',           'nordic-2026',   44),
    ('28', '22222222-2222-2222-2222-222222222222'::uuid, 'ghl-demo-0028', 'Mikael Sund',     'm.sund@sundlegal.test',            '+46-8-555-0228',  'Sund Legal',                 'ATTORNEY',   'REFERRAL',       'referral',  'word-of-mouth', NULL,           104),
    ('29', '22222222-2222-2222-2222-222222222222'::uuid, 'ghl-demo-0029', 'Freya Lund',      'f.lund@mailbox.test',              NULL,              NULL,                         'INDIVIDUAL', 'WEBSITE',        'direct',    'none',          NULL,           164)
  ) AS t(suffix, brand_id, ghl_id, full_name, email, phone, company, client_type, source_channel,
         utm_source, utm_medium, utm_campaign, age_days);

-- ---------------------------------------------------------------------------
-- 4. The case board.
--
--    Every stage carries work, so no column of the board is empty and the PM's
--    figures have something to divide by. The SLA mix is deliberate: one case is
--    already overdue, two are inside their at-risk window, and the rest are on
--    track -- a board that is entirely green demonstrates nothing about the colours.
--
--    Twelve cases are CLOSED and spread back over eight months. That is the "past to
--    measure against": completed counts per expert, revenue history, and a payment
--    ledger with settled months behind it rather than one week of activity.
--
--    XpertsPortal cases are staffed by the XP brand manager and IE cases by IE staff.
--    Crossing that would be a brand leak in seed form, and the roster screen would
--    show a name the reader is not allowed to see.
-- ---------------------------------------------------------------------------
INSERT INTO evalos_case (
    id, brand_id, case_code, pool_status, assigned_pm, assigned_cm, assigned_coordinator,
    contact_id, service_type, service_subtype, client_type, deal_value, deadline,
    current_stage, exception_state, stage_entered_at, sla_status,
    expert_id, expert_sign_status, draft_version_count, pm_approval_status, client_approval_status,
    delivery_date, case_closed_date, paid, paid_at, created_at, ghl_opportunity_id
)
SELECT ('ca000000-0000-0000-0000-0000000000' || code_suffix)::uuid,
       brand_id, case_code, pool_status, assigned_pm, assigned_cm, assigned_coordinator,
       ('c0000000-0000-0000-0000-0000000000' || contact_suffix)::uuid,
       service_type, service_subtype, client_type, deal_value,
       now() + (deadline_days || ' days')::interval,
       stage, exception_state,
       now() - (stage_days || ' days')::interval,
       sla_status, expert_id, sign_status, drafts, pm_approval, client_approval,
       CASE WHEN closed_days IS NULL THEN NULL ELSE now() - (closed_days || ' days')::interval END,
       CASE WHEN closed_days IS NULL THEN NULL ELSE now() - (closed_days || ' days')::interval END,
       paid,
       CASE WHEN paid THEN now() - (age_days || ' days')::interval + interval '1 day' ELSE NULL END,
       now() - (age_days || ' days')::interval,
       'ghl-opp-' || code_suffix
  FROM (VALUES
    -- suffix, contact, code,            pool,        pm, cm, pc, service, subtype, clientType, value, deadlineDays, stage, exception, stageDays, sla, expert, sign, drafts, pmApp, clientApp, closedDays, paid, ageDays
    -- ---- IE: in flight -----------------------------------------------------
    ('01','01','IE-2026-4801','ASSIGNED','aaaaaaaa-0000-0000-0000-000000000004'::uuid,'aaaaaaaa-0000-0000-0000-000000000005'::uuid,'aaaaaaaa-0000-0000-0000-000000000006'::uuid,'11111111-1111-1111-1111-111111111111'::uuid,'CREDENTIAL_EVALUATION','COURSE_BY_COURSE','ATTORNEY',285.00,   6,'DOC_COLLECTION',   'NONE',                    4,'ON_TRACK',NULL::uuid,                                          NULL,0,NULL,        NULL,        NULL::int, true,   4),
    ('02','02','IE-2026-4802','ASSIGNED','aaaaaaaa-0000-0000-0000-000000000004'::uuid,'aaaaaaaa-0000-0000-0000-000000000005'::uuid,'aaaaaaaa-0000-0000-0000-000000000006'::uuid,'11111111-1111-1111-1111-111111111111'::uuid,'EXPERT_OPINION_LETTER',NULL,             'EMPLOYER',1650.00,  2,'DOC_COLLECTION',   'NONE',                    9,'AT_RISK', NULL::uuid,                                          NULL,0,NULL,        NULL,        NULL::int, true,   9),
    -- Unclaimed: still in the pool, so the board shows what nobody has picked up yet.
    ('03','03','IE-2026-4803','IN_POOL', NULL::uuid,                                  NULL::uuid,                                  NULL::uuid,                                  '11111111-1111-1111-1111-111111111111'::uuid,'CREDENTIAL_EVALUATION','WORK_EXPERIENCE_ONLY','AGENT',265.00,  11,'DOC_COLLECTION',   'NONE',                    2,'ON_TRACK',NULL::uuid,                                          NULL,0,NULL,        NULL,        NULL::int, true,   2),
    -- Overdue and waiting on the client: the exception state and the SLA are different
    -- facts about the same case, and the board has to be able to show both at once.
    ('04','04','IE-2026-4804','ASSIGNED','aaaaaaaa-0000-0000-0000-000000000004'::uuid,'aaaaaaaa-0000-0000-0000-000000000005'::uuid,NULL::uuid,                                  '11111111-1111-1111-1111-111111111111'::uuid,'RFE_RESPONSE',        NULL,              'ATTORNEY',1200.00, -1,'DOC_COLLECTION',   'ON_HOLD_AWAITING_CLIENT',14,'OVERDUE', NULL::uuid,                                         NULL,0,NULL,        NULL,        NULL::int, true,  14),
    ('05','05','IE-2026-4805','ASSIGNED','aaaaaaaa-0000-0000-0000-000000000004'::uuid,'aaaaaaaa-0000-0000-0000-000000000005'::uuid,'aaaaaaaa-0000-0000-0000-000000000006'::uuid,'11111111-1111-1111-1111-111111111111'::uuid,'EXPERT_OPINION_LETTER',NULL,             'INDIVIDUAL',1725.00, 5,'EXPERT_ASSIGNMENT','NONE',                    3,'ON_TRACK',NULL::uuid,                                          NULL,0,NULL,        NULL,        NULL::int, true,   8),
    ('06','06','IE-2026-4806','ASSIGNED','aaaaaaaa-0000-0000-0000-000000000004'::uuid,'aaaaaaaa-0000-0000-0000-000000000005'::uuid,NULL::uuid,                                  '11111111-1111-1111-1111-111111111111'::uuid,'PERM',                NULL,              'EMPLOYER',1450.00,  8,'EXPERT_ASSIGNMENT','NONE',                    2,'ON_TRACK',NULL::uuid,                                          NULL,0,NULL,        NULL,        NULL::int, true,   6),
    ('07','07','IE-2026-4807','ASSIGNED','aaaaaaaa-0000-0000-0000-000000000004'::uuid,'aaaaaaaa-0000-0000-0000-000000000005'::uuid,'aaaaaaaa-0000-0000-0000-000000000006'::uuid,'11111111-1111-1111-1111-111111111111'::uuid,'EXPERT_OPINION_LETTER',NULL,             'ATTORNEY',1800.00,  4,'DRAFT_GENERATION', 'NONE',                    5,'ON_TRACK','e0000000-0000-0000-0000-000000000001'::uuid,'PENDING',2,'PENDING',   NULL,        NULL::int, true,  16),
    ('08','08','IE-2026-4808','ASSIGNED','aaaaaaaa-0000-0000-0000-000000000004'::uuid,'aaaaaaaa-0000-0000-0000-000000000005'::uuid,NULL::uuid,                                  '11111111-1111-1111-1111-111111111111'::uuid,'RFE_RESPONSE',        NULL,              'INDIVIDUAL',1250.00, 7,'DRAFT_GENERATION', 'NONE',                    3,'ON_TRACK','e0000000-0000-0000-0000-000000000003'::uuid,'PENDING',1,'PENDING',   NULL,        NULL::int, true,  12),
    ('09','09','IE-2026-4809','ASSIGNED','aaaaaaaa-0000-0000-0000-000000000004'::uuid,'aaaaaaaa-0000-0000-0000-000000000005'::uuid,'aaaaaaaa-0000-0000-0000-000000000006'::uuid,'11111111-1111-1111-1111-111111111111'::uuid,'EXPERT_OPINION_LETTER',NULL,             'EMPLOYER',1690.00,  3,'DRAFT_GENERATION', 'NONE',                    7,'AT_RISK', 'e0000000-0000-0000-0000-000000000002'::uuid,'PENDING',3,'RETURNED',  NULL,        NULL::int, true,  18),
    ('0a','10','IE-2026-4810','ASSIGNED','aaaaaaaa-0000-0000-0000-000000000004'::uuid,'aaaaaaaa-0000-0000-0000-000000000005'::uuid,NULL::uuid,                                  '11111111-1111-1111-1111-111111111111'::uuid,'EXPERT_OPINION_LETTER',NULL,             'INDIVIDUAL',1750.00, 2,'EXPERT_SIGNING',   'NONE',                    2,'ON_TRACK','e0000000-0000-0000-0000-000000000001'::uuid,'PENDING',3,'APPROVED',  NULL,        NULL::int, true,  21),
    -- The expert has had it past the signing window: sign status OVERDUE is a different
    -- alarm from the case SLA, and the ENM screen is where it has to surface.
    ('0b','11','IE-2026-4811','ASSIGNED','aaaaaaaa-0000-0000-0000-000000000004'::uuid,'aaaaaaaa-0000-0000-0000-000000000005'::uuid,'aaaaaaaa-0000-0000-0000-000000000006'::uuid,'11111111-1111-1111-1111-111111111111'::uuid,'PERM',                NULL,              'EMPLOYER',1500.00, -2,'EXPERT_SIGNING',  'NONE',                    6,'OVERDUE', 'e0000000-0000-0000-0000-000000000009'::uuid,'OVERDUE',2,'APPROVED',  NULL,        NULL::int, true,  25),
    ('0c','12','IE-2026-4812','ASSIGNED','aaaaaaaa-0000-0000-0000-000000000004'::uuid,'aaaaaaaa-0000-0000-0000-000000000005'::uuid,'aaaaaaaa-0000-0000-0000-000000000006'::uuid,'11111111-1111-1111-1111-111111111111'::uuid,'EXPERT_OPINION_LETTER',NULL,             'ATTORNEY',1725.50,  1,'FINAL_DELIVERY',   'NONE',                    1,'ON_TRACK','e0000000-0000-0000-0000-000000000003'::uuid,'SIGNED', 3,'APPROVED',  'PENDING',   NULL::int, true,  27),
    ('0d','13','IE-2026-4813','ASSIGNED','aaaaaaaa-0000-0000-0000-000000000004'::uuid,'aaaaaaaa-0000-0000-0000-000000000005'::uuid,NULL::uuid,                                  '11111111-1111-1111-1111-111111111111'::uuid,'CREDENTIAL_EVALUATION','EDUCATION_PLUS_EXPERIENCE','INDIVIDUAL',295.00,3,'FINAL_DELIVERY','NONE',                    2,'ON_TRACK','e0000000-0000-0000-0000-000000000006'::uuid,'SIGNED', 1,'APPROVED',  'APPROVED',  NULL::int, true,  19),
    -- ---- IE: closed, eight months of history -------------------------------
    ('0e','14','IE-2026-4714','ASSIGNED','aaaaaaaa-0000-0000-0000-000000000004'::uuid,'aaaaaaaa-0000-0000-0000-000000000005'::uuid,NULL::uuid,                                  '11111111-1111-1111-1111-111111111111'::uuid,'CREDENTIAL_EVALUATION','COURSE_BY_COURSE','INDIVIDUAL',275.00,-24,'CLOSED',          'NONE',                   26,'ON_TRACK','e0000000-0000-0000-0000-000000000006'::uuid,'SIGNED', 1,'APPROVED',  'APPROVED',   26, true,  48),
    ('0f','15','IE-2026-4715','ASSIGNED','aaaaaaaa-0000-0000-0000-000000000004'::uuid,'aaaaaaaa-0000-0000-0000-000000000005'::uuid,'aaaaaaaa-0000-0000-0000-000000000006'::uuid,'11111111-1111-1111-1111-111111111111'::uuid,'EXPERT_OPINION_LETTER',NULL,             'EMPLOYER',1725.00,-28,'CLOSED',          'NONE',                   31,'ON_TRACK','e0000000-0000-0000-0000-000000000001'::uuid,'SIGNED', 2,'APPROVED',  'APPROVED',   31, true,  52),
    ('10','16','IE-2026-4616','ASSIGNED','aaaaaaaa-0000-0000-0000-000000000004'::uuid,'aaaaaaaa-0000-0000-0000-000000000005'::uuid,NULL::uuid,                                  '11111111-1111-1111-1111-111111111111'::uuid,'RFE_RESPONSE',        NULL,              'ATTORNEY',1180.00,-58,'CLOSED',          'NONE',                   60,'ON_TRACK','e0000000-0000-0000-0000-000000000003'::uuid,'SIGNED', 1,'APPROVED',  'APPROVED',   60, true,  82),
    ('11','17','IE-2026-4617','ASSIGNED','aaaaaaaa-0000-0000-0000-000000000004'::uuid,'aaaaaaaa-0000-0000-0000-000000000005'::uuid,'aaaaaaaa-0000-0000-0000-000000000006'::uuid,'11111111-1111-1111-1111-111111111111'::uuid,'EXPERT_OPINION_LETTER',NULL,             'EMPLOYER',1690.00,-64,'CLOSED',          'NONE',                   66,'ON_TRACK','e0000000-0000-0000-0000-000000000002'::uuid,'SIGNED', 2,'APPROVED',  'APPROVED',   66, true,  88),
    ('12','18','IE-2026-4518','ASSIGNED','aaaaaaaa-0000-0000-0000-000000000004'::uuid,'aaaaaaaa-0000-0000-0000-000000000005'::uuid,NULL::uuid,                                  '11111111-1111-1111-1111-111111111111'::uuid,'CREDENTIAL_EVALUATION','WORK_EXPERIENCE_ONLY','INDIVIDUAL',260.00,-88,'CLOSED',      'NONE',                   90,'ON_TRACK','e0000000-0000-0000-0000-000000000006'::uuid,'SIGNED', 1,'APPROVED',  'APPROVED',   90, true, 112),
    ('13','19','IE-2026-4519','ASSIGNED','aaaaaaaa-0000-0000-0000-000000000004'::uuid,'aaaaaaaa-0000-0000-0000-000000000005'::uuid,'aaaaaaaa-0000-0000-0000-000000000006'::uuid,'11111111-1111-1111-1111-111111111111'::uuid,'PERM',                NULL,              'EMPLOYER',1425.00,-94,'CLOSED',          'NONE',                   96,'ON_TRACK','e0000000-0000-0000-0000-000000000009'::uuid,'SIGNED', 2,'APPROVED',  'APPROVED',   96, true, 118),
    ('14','20','IE-2026-4420','ASSIGNED','aaaaaaaa-0000-0000-0000-000000000004'::uuid,'aaaaaaaa-0000-0000-0000-000000000005'::uuid,NULL::uuid,                                  '11111111-1111-1111-1111-111111111111'::uuid,'EXPERT_OPINION_LETTER',NULL,             'ATTORNEY',1750.00,-122,'CLOSED',         'NONE',                  124,'ON_TRACK','e0000000-0000-0000-0000-000000000001'::uuid,'SIGNED', 3,'APPROVED',  'APPROVED',  124, true, 146),
    ('15','21','IE-2026-4321','ASSIGNED','aaaaaaaa-0000-0000-0000-000000000004'::uuid,'aaaaaaaa-0000-0000-0000-000000000005'::uuid,NULL::uuid,                                  '11111111-1111-1111-1111-111111111111'::uuid,'CREDENTIAL_EVALUATION','COURSE_BY_COURSE','INDIVIDUAL',285.00,-152,'CLOSED',         'NONE',                  154,'ON_TRACK','e0000000-0000-0000-0000-000000000006'::uuid,'SIGNED', 1,'APPROVED',  'APPROVED',  154, true, 176),
    ('16','22','IE-2026-4222','ASSIGNED','aaaaaaaa-0000-0000-0000-000000000004'::uuid,'aaaaaaaa-0000-0000-0000-000000000005'::uuid,'aaaaaaaa-0000-0000-0000-000000000006'::uuid,'11111111-1111-1111-1111-111111111111'::uuid,'EXPERT_OPINION_LETTER',NULL,             'ATTORNEY',1800.00,-182,'CLOSED',         'NONE',                  184,'ON_TRACK','e0000000-0000-0000-0000-000000000003'::uuid,'SIGNED', 2,'APPROVED',  'APPROVED',  184, true, 206),
    ('17','23','IE-2026-4123','ASSIGNED','aaaaaaaa-0000-0000-0000-000000000004'::uuid,'aaaaaaaa-0000-0000-0000-000000000005'::uuid,NULL::uuid,                                  '11111111-1111-1111-1111-111111111111'::uuid,'RFE_RESPONSE',        NULL,              'INDIVIDUAL',1210.00,-212,'CLOSED',        'NONE',                  214,'ON_TRACK','e0000000-0000-0000-0000-000000000001'::uuid,'SIGNED', 1,'APPROVED',  'APPROVED',  214, true, 236),
    ('18','24','IE-2025-4024','ASSIGNED','aaaaaaaa-0000-0000-0000-000000000004'::uuid,'aaaaaaaa-0000-0000-0000-000000000005'::uuid,NULL::uuid,                                  '11111111-1111-1111-1111-111111111111'::uuid,'CREDENTIAL_EVALUATION','COURSE_BY_COURSE','INDIVIDUAL',270.00,-242,'CLOSED',         'NONE',                  244,'ON_TRACK','e0000000-0000-0000-0000-000000000006'::uuid,'SIGNED', 1,'APPROVED',  'APPROVED',  244, true, 266),
    -- ---- XpertsPortal ------------------------------------------------------
    ('19','25','XP-2026-4825','ASSIGNED','aaaaaaaa-0000-0000-0000-000000000003'::uuid,'aaaaaaaa-0000-0000-0000-000000000003'::uuid,NULL::uuid,                                  '22222222-2222-2222-2222-222222222222'::uuid,'CREDENTIAL_EVALUATION','COURSE_BY_COURSE','INDIVIDUAL',240.00,  7,'DOC_COLLECTION',  'NONE',                    3,'ON_TRACK',NULL::uuid,                                          NULL,0,NULL,        NULL,        NULL::int, true,   3),
    ('1a','26','XP-2026-4826','ASSIGNED','aaaaaaaa-0000-0000-0000-000000000003'::uuid,'aaaaaaaa-0000-0000-0000-000000000003'::uuid,NULL::uuid,                                  '22222222-2222-2222-2222-222222222222'::uuid,'EXPERT_OPINION_LETTER',NULL,             'EMPLOYER',1600.00,  6,'DRAFT_GENERATION','NONE',                    4,'ON_TRACK','e0000000-0000-0000-0000-00000000000b'::uuid,'PENDING',1,'PENDING',   NULL,        NULL::int, true,  11),
    ('1b','27','XP-2026-4727','ASSIGNED','aaaaaaaa-0000-0000-0000-000000000003'::uuid,'aaaaaaaa-0000-0000-0000-000000000003'::uuid,NULL::uuid,                                  '22222222-2222-2222-2222-222222222222'::uuid,'CREDENTIAL_EVALUATION','COURSE_BY_COURSE','INDIVIDUAL',255.00,-20,'CLOSED',          'NONE',                   22,'ON_TRACK','e0000000-0000-0000-0000-00000000000d'::uuid,'SIGNED', 1,'APPROVED',  'APPROVED',   22, true,  44),
    ('1c','28','XP-2026-4528','ASSIGNED','aaaaaaaa-0000-0000-0000-000000000003'::uuid,'aaaaaaaa-0000-0000-0000-000000000003'::uuid,NULL::uuid,                                  '22222222-2222-2222-2222-222222222222'::uuid,'EXPERT_OPINION_LETTER',NULL,             'ATTORNEY',1580.00,-80,'CLOSED',          'NONE',                   82,'ON_TRACK','e0000000-0000-0000-0000-00000000000b'::uuid,'SIGNED', 2,'APPROVED',  'APPROVED',   82, true, 104),
    ('1d','29','XP-2026-4329','ASSIGNED','aaaaaaaa-0000-0000-0000-000000000003'::uuid,'aaaaaaaa-0000-0000-0000-000000000003'::uuid,NULL::uuid,                                  '22222222-2222-2222-2222-222222222222'::uuid,'CREDENTIAL_EVALUATION','COURSE_BY_COURSE','INDIVIDUAL',245.00,-140,'CLOSED',         'NONE',                  142,'ON_TRACK','e0000000-0000-0000-0000-00000000000d'::uuid,'SIGNED', 1,'APPROVED',  'APPROVED',  142, true, 164)
  ) AS t(code_suffix, contact_suffix, case_code, pool_status, assigned_pm, assigned_cm, assigned_coordinator,
         brand_id, service_type, service_subtype, client_type, deal_value, deadline_days,
         stage, exception_state, stage_days, sla_status, expert_id, sign_status, drafts,
         pm_approval, client_approval, closed_days, paid, age_days);

-- ---------------------------------------------------------------------------
-- 5. Document checklists for the cases still collecting.
--
--    Mixed statuses on purpose: a checklist where every row is APPROVED tells the
--    coordinator nothing, and MISSING is the state the chase list is built from.
-- ---------------------------------------------------------------------------
INSERT INTO document_checklist_item (brand_id, case_id, label, status, updated_at, created_at)
SELECT c.brand_id, c.id, d.label, d.status,
       now() - (d.age || ' days')::interval, c.created_at
  FROM evalos_case c
  JOIN (VALUES
    ('01','Degree certificate',          'APPROVED', 3),
    ('01','Academic transcript',         'UPLOADED', 1),
    ('01','Passport bio page',           'REQUIRED', 4),
    ('02','Employment verification letter','APPROVED',6),
    ('02','Detailed job description',    'UPLOADED', 2),
    ('02','Degree certificate',          'MISSING',  8),
    ('03','Degree certificate',          'REQUIRED', 2),
    ('03','Academic transcript',         'REQUIRED', 2),
    ('04','RFE notice',                  'APPROVED', 12),
    ('04','Prior petition copy',         'INCORRECT',5),
    ('04','Supporting evidence bundle',  'MISSING',  3),
    ('19','Degree certificate',          'UPLOADED', 2),
    ('19','Academic transcript',         'REQUIRED', 3)
  ) AS d(case_suffix, label, status, age)
    ON c.id = ('ca000000-0000-0000-0000-0000000000' || d.case_suffix)::uuid;

-- ---------------------------------------------------------------------------
-- 6. The offer ledger -- what acceptance rate and the declining list are built from.
--
--    `uq`-style CHECK on this table: outcome = 'OFFERED' exactly when outcome_at is
--    NULL, so a pending offer has no resolution timestamp and a resolved one must.
--
--    Two experts decline repeatedly (Herrera, Brandt). That is what puts them on the
--    "declining two or more" list, and it is also why the fleet acceptance rate is a
--    number worth showing rather than 100%.
-- ---------------------------------------------------------------------------
INSERT INTO expert_case_offer (id, brand_id, case_id, expert_id, offered_at, outcome, outcome_at, decline_reason, created_at)
SELECT ('f0000000-0000-0000-0000-0000000000' || lpad((row_number() OVER ())::text, 2, '0'))::uuid,
       c.brand_id, c.id, ('e0000000-0000-0000-0000-0000000000' || o.expert_suffix)::uuid,
       now() - (o.offered || ' days')::interval,
       o.outcome,
       CASE WHEN o.outcome = 'OFFERED' THEN NULL ELSE now() - (o.resolved || ' days')::interval END,
       o.reason,
       now() - (o.offered || ' days')::interval
  FROM (VALUES
    -- In flight: case 05 is out to two experts, one already declined.
    ('05','0a','DECLINED', 3, 2,'Outside my field; suggest an engineering opinion instead.'),
    ('05','01','OFFERED',  1, 0, NULL),
    ('06','05','DECLINED', 2, 1,'Agreement has lapsed.'),
    ('06','03','OFFERED',  1, 0, NULL),
    -- Resolved offers behind the cases now in draft or beyond.
    ('07','01','ACCEPTED',16,15, NULL),
    ('08','03','ACCEPTED',12,11, NULL),
    ('09','0a','DECLINED',19,18,'Too close to the deadline.'),
    ('09','02','ACCEPTED',18,17, NULL),
    ('0a','01','ACCEPTED',21,20, NULL),
    ('0b','05','TIMED_OUT',27,25, NULL),
    ('0b','09','ACCEPTED',25,24, NULL),
    ('0c','03','ACCEPTED',27,26, NULL),
    ('0d','06','ACCEPTED',19,18, NULL),
    -- History, so the rate has a denominator worth dividing by.
    ('0e','06','ACCEPTED',48,47, NULL),
    ('0f','01','ACCEPTED',52,51, NULL),
    ('10','03','ACCEPTED',82,81, NULL),
    ('11','0a','DECLINED',89,88,'Away that month.'),
    ('11','02','ACCEPTED',88,87, NULL),
    ('12','06','ACCEPTED',112,111,NULL),
    ('13','09','ACCEPTED',118,117,NULL),
    ('14','05','DECLINED',148,147,'No capacity.'),
    ('14','01','ACCEPTED',146,145,NULL),
    ('15','06','ACCEPTED',176,175,NULL),
    ('16','03','ACCEPTED',206,205,NULL),
    ('17','01','ACCEPTED',236,235,NULL),
    ('18','06','ACCEPTED',266,265,NULL),
    ('1a','0b','ACCEPTED',11,10, NULL),
    ('1b','0d','ACCEPTED',44,43, NULL),
    ('1c','0b','ACCEPTED',104,103,NULL),
    ('1d','0d','ACCEPTED',164,163,NULL)
  ) AS o(case_suffix, expert_suffix, outcome, offered, resolved, reason)
  JOIN evalos_case c ON c.id = ('ca000000-0000-0000-0000-0000000000' || o.case_suffix)::uuid;

-- ---------------------------------------------------------------------------
-- 7. Payments, then the payout ledger that points at them.
--
--    Payments come first because `payout_ledger.payment_id` references them. Each
--    payment settles a month of one expert's work, which is how the real thing is
--    done -- one transfer, one reference, several cases.
-- ---------------------------------------------------------------------------
INSERT INTO payout_payment (id, brand_id, expert_id, amount, currency, method, reference, paid_date, notes, confirmed_at, recorded_by, created_at)
VALUES
    ('b1000000-0000-0000-0000-000000000001','11111111-1111-1111-1111-111111111111','e0000000-0000-0000-0000-000000000001', 700.00,'USD','Wire',    'TRF-2026-0417', now() - interval '26 days', 'Two opinion letters.',      now() - interval '24 days','aaaaaaaa-0000-0000-0000-000000000007', now() - interval '26 days'),
    ('b1000000-0000-0000-0000-000000000002','11111111-1111-1111-1111-111111111111','e0000000-0000-0000-0000-000000000006', 660.00,'USD','ACH',     'TRF-2026-0392', now() - interval '58 days', NULL,                        now() - interval '56 days','aaaaaaaa-0000-0000-0000-000000000007', now() - interval '58 days'),
    ('b1000000-0000-0000-0000-000000000003','11111111-1111-1111-1111-111111111111','e0000000-0000-0000-0000-000000000003', 800.00,'USD','Wire',    'TRF-2026-0355', now() - interval '88 days', 'RFE plus one opinion.',     now() - interval '86 days','aaaaaaaa-0000-0000-0000-000000000007', now() - interval '88 days'),
    -- Paid but not yet acknowledged: PAID and CONFIRMED are different states and the
    -- payouts screen has to be able to show one waiting on the other.
    ('b1000000-0000-0000-0000-000000000004','11111111-1111-1111-1111-111111111111','e0000000-0000-0000-0000-000000000002', 600.00,'USD','PayPal',  'PP-2026-8814', now() - interval '12 days', 'Awaiting acknowledgement.', NULL,                      'aaaaaaaa-0000-0000-0000-000000000007', now() - interval '12 days'),
    ('b1000000-0000-0000-0000-000000000005','22222222-2222-2222-2222-222222222222','e0000000-0000-0000-0000-00000000000b', 690.00,'USD','Zelle',   'TRF-2026-0301', now() - interval '78 days', NULL,                        now() - interval '76 days','aaaaaaaa-0000-0000-0000-000000000003', now() - interval '78 days');

-- One ledger row per case that has an expert on it. `uq_payout_per_case` allows exactly
-- one non-VOIDED row per case, so this is a per-case fee and not a running tally.
INSERT INTO payout_ledger (id, brand_id, case_id, expert_id, amount, currency, status, due_date, recorded_by, payment_id, created_at)
SELECT ('b0000000-0000-0000-0000-0000000000' || lpad((row_number() OVER ())::text, 2, '0'))::uuid,
       c.brand_id, c.id, c.expert_id, p.amount, 'USD', p.status,
       now() - (p.due || ' days')::interval,
       CASE WHEN c.brand_id = '22222222-2222-2222-2222-222222222222'::uuid
            THEN 'aaaaaaaa-0000-0000-0000-000000000003'::uuid
            ELSE 'aaaaaaaa-0000-0000-0000-000000000007'::uuid END,
       p.payment_id::uuid,
       now() - (p.due || ' days')::interval - interval '2 days'
  FROM (VALUES
    -- Still owed: work in flight, nothing transferred yet. This is the ENM "pending" total.
    ('07',350.00,'PENDING',  -14, NULL),
    ('08',400.00,'PENDING',   -9, NULL),
    ('09',300.00,'PENDING',   -7, NULL),
    ('0a',350.00,'PENDING',   -4, NULL),
    ('0b',295.00,'PENDING',   -2, NULL),
    ('0c',400.00,'PENDING',    3, NULL),
    ('0d',330.00,'PENDING',    5, NULL),
    ('1a',345.00,'PENDING',   -6, NULL),
    -- Settled and acknowledged.
    ('0e',330.00,'CONFIRMED', 26,'b1000000-0000-0000-0000-000000000002'),
    ('0f',350.00,'CONFIRMED', 28,'b1000000-0000-0000-0000-000000000001'),
    ('10',400.00,'CONFIRMED', 60,'b1000000-0000-0000-0000-000000000003'),
    ('12',330.00,'CONFIRMED', 90,'b1000000-0000-0000-0000-000000000002'),
    ('14',350.00,'CONFIRMED',124,'b1000000-0000-0000-0000-000000000001'),
    ('16',400.00,'CONFIRMED',184,'b1000000-0000-0000-0000-000000000003'),
    ('1c',345.00,'CONFIRMED', 82,'b1000000-0000-0000-0000-000000000005'),
    -- Transferred, acknowledgement outstanding.
    ('11',300.00,'PAID',      66,'b1000000-0000-0000-0000-000000000004'),
    ('17',300.00,'PAID',     214,'b1000000-0000-0000-0000-000000000004'),
    -- Settled outside the grouped payments above; still legitimately CONFIRMED.
    ('13',295.00,'CONFIRMED', 96, NULL),
    ('15',330.00,'CONFIRMED',154, NULL),
    ('18',330.00,'CONFIRMED',244, NULL),
    ('1b',340.00,'CONFIRMED', 22, NULL),
    ('1d',340.00,'CONFIRMED',142, NULL)
  ) AS p(case_suffix, amount, status, due, payment_id)
  JOIN evalos_case c ON c.id = ('ca000000-0000-0000-0000-0000000000' || p.case_suffix)::uuid;

-- ---------------------------------------------------------------------------
-- 8. A few notifications, some unread, so the bell has a count on it.
-- ---------------------------------------------------------------------------
INSERT INTO notification (brand_id, recipient_id, type, case_id, body, read, created_at)
SELECT c.brand_id, n.recipient::uuid, n.type, c.id, n.body, n.read, now() - (n.age || ' hours')::interval
  FROM (VALUES
    ('04','aaaaaaaa-0000-0000-0000-000000000004','SLA_OVERDUE',    'IE-2026-4804 is past its deadline and on hold awaiting the client.', false,  5),
    ('0b','aaaaaaaa-0000-0000-0000-000000000004','SLA_OVERDUE',    'IE-2026-4811 is past its deadline; the expert has not signed.',       false,  9),
    ('02','aaaaaaaa-0000-0000-0000-000000000005','SLA_AT_RISK',    'IE-2026-4802 is inside its at-risk window.',                         false, 14),
    ('09','aaaaaaaa-0000-0000-0000-000000000005','SLA_AT_RISK',    'IE-2026-4809 is inside its at-risk window.',                         false, 20),
    ('03','aaaaaaaa-0000-0000-0000-000000000004','NEW_CASE_IN_POOL','IE-2026-4803 has arrived and is unclaimed.',                        false, 30),
    ('05','aaaaaaaa-0000-0000-0000-000000000007','EXCEPTION_RAISED','Dr Helena Brandt declined IE-2026-4805.',                            true,  52),
    ('0c','aaaaaaaa-0000-0000-0000-000000000006','STAGE_CHANGED',  'IE-2026-4812 moved to Final delivery.',                              true,  26),
    ('0d','aaaaaaaa-0000-0000-0000-000000000006','STAGE_CHANGED',  'IE-2026-4813 moved to Final delivery.',                              true,  44),
    ('19','aaaaaaaa-0000-0000-0000-000000000003','NEW_CASE_IN_POOL','XP-2026-4825 has arrived.',                                         false, 62)
  ) AS n(case_suffix, recipient, type, body, read, age)
  JOIN evalos_case c ON c.id = ('ca000000-0000-0000-0000-0000000000' || n.case_suffix)::uuid;

-- ---------------------------------------------------------------------------
-- 9. Enough audit trail that opening a case shows a history rather than an empty tab.
--    Append-only: written once here and never touched again.
-- ---------------------------------------------------------------------------
INSERT INTO audit_event (id, brand_id, object_type, object_id, action, actor_id, actor_type, after_snapshot, created_at)
SELECT gen_random_uuid(), c.brand_id, 'CASE', c.id, a.action, a.actor::uuid, 'STAFF',
       a.snapshot::jsonb, now() - (a.age || ' days')::interval
  FROM (VALUES
    ('0c','ASSIGNED',     'aaaaaaaa-0000-0000-0000-000000000004','{"assignedCm":"Case Manager (IE)"}',      27),
    ('0c','STAGE_CHANGED','aaaaaaaa-0000-0000-0000-000000000005','{"currentStage":"DRAFT_GENERATION"}',     18),
    ('0c','STAGE_CHANGED','aaaaaaaa-0000-0000-0000-000000000005','{"currentStage":"EXPERT_SIGNING"}',        6),
    ('0c','STAGE_CHANGED','aaaaaaaa-0000-0000-0000-000000000005','{"currentStage":"FINAL_DELIVERY"}',        1),
    ('04','FLAGGED',      'aaaaaaaa-0000-0000-0000-000000000005','{"exceptionState":"ON_HOLD_AWAITING_CLIENT"}', 5),
    ('04','CHASED',       'aaaaaaaa-0000-0000-0000-000000000006','{"note":"Second chase for the evidence bundle."}', 3),
    ('0b','ASSIGNED',     'aaaaaaaa-0000-0000-0000-000000000007','{"expert":"Dr Gregor Vance"}',            25),
    ('0e','PAYOUT_SETTLED','aaaaaaaa-0000-0000-0000-000000000007','{"status":"CONFIRMED"}',                  24)
  ) AS a(case_suffix, action, actor, snapshot, age)
  JOIN evalos_case c ON c.id = ('ca000000-0000-0000-0000-0000000000' || a.case_suffix)::uuid;
