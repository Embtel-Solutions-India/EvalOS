-- A fresh local demo world (2026-10-02), replacing V905's cases. LOCAL PROFILE ONLY.
--
-- WHAT SURVIVES. `brand`, `team_member` (every staff login) and everything the GHL mirror owns
-- or a desk needs: `team_member_pipeline`, `pipeline`, `pipeline_stage`, `opportunity`, the
-- real mirrored contacts, `ghl_*` reference rows, sales notes / follow-ups / meetings,
-- `sync_outbox` (queued writes to the real CRM -- never dropped by a seed) and the job ledger.
--
-- WHAT IS REBUILT. Every case and everything hanging off one (documents, comments, checklist,
-- offers, payouts, chat, notifications, portal links), every expert and client, their portal
-- accounts, and the demo contacts (`ghl-demo-%`).
--
-- WHY. V905's cases had drifted from the app: stage names renamed under them (V907), drafts
-- counted with no draft rows behind them, no case in CLIENT_REVIEW, EXPERT_SIGNING, FINAL_QC or
-- DELIVERED, and only one client and one expert who could sign in. This world is consistent
-- with what the app itself writes, has every stage and every exception occupied, and every
-- client and expert signs in with the same throwaway password as the staff logins.
--
-- THE PASSWORD. The BCrypt hash of `DevPassw0rd!`, already committed in V908 and V913. It is a
-- local-only throwaway; production seeds take theirs from a Flyway placeholder (V960).
--
-- DOCUMENTS. Every seeded document points at one of five sample files under the `seed/` key:
-- `backend/seed-local-documents/` holds them, and they must sit in the local document directory
-- (`EVALOS_S3_LOCAL_DIR`, `./.local-documents` by default) -- see the README beside this file.
-- `object_key` is not unique, so sharing a sample is fine for reads; an upload through the app
-- still writes its own key.
--
-- Dates are relative to now(), so the board reads as current whenever this runs.

-- ---------------------------------------------------------------------------
-- 1. Clear, innermost first. Three tables are append-only by trigger; each trigger is off
--    for its one DELETE and straight back on, as V905 did for audit_event. Application code
--    must never do this.
-- ---------------------------------------------------------------------------
DELETE FROM message_reactions;
DELETE FROM message_reads;
DELETE FROM messages;
ALTER TABLE conversation_members DISABLE TRIGGER conversation_members_history_only;
DELETE FROM conversation_members;
ALTER TABLE conversation_members ENABLE TRIGGER conversation_members_history_only;
DELETE FROM conversations;
ALTER TABLE draft_comments DISABLE TRIGGER draft_comments_append_only;
DELETE FROM draft_comments;
ALTER TABLE draft_comments ENABLE TRIGGER draft_comments_append_only;
DELETE FROM case_document;
DELETE FROM payout_ledger;
DELETE FROM payout_payment;
DELETE FROM portal_access;
DELETE FROM client_credential_token;
DELETE FROM expert_credential_token;
DELETE FROM client_account;
DELETE FROM expert_account;
DELETE FROM document_checklist_item;
DELETE FROM expert_case_offer;
DELETE FROM notification;
DELETE FROM push_subscriptions WHERE subscriber_kind <> 'STAFF';
DELETE FROM evalos_case;
DELETE FROM contact_snapshot WHERE ghl_contact_id LIKE 'ghl-demo-%';
DELETE FROM expert;
DELETE FROM webhook_event;
DELETE FROM ghl_funnel_cache;

-- The trail of what was deleted goes with it; the GHL mirror's own audit rows stay.
ALTER TABLE audit_event DISABLE TRIGGER audit_event_no_mutation;
DELETE FROM audit_event WHERE object_type NOT IN ('GHL_OPPORTUNITY', 'GHL_CONTACT', 'GHL_NOTE', 'TEAM_MEMBER', 'PIPELINE');
ALTER TABLE audit_event ENABLE TRIGGER audit_event_no_mutation;

-- ---------------------------------------------------------------------------
-- 2. The expert roster -- V905's thirteen, same ids (CaseLiveHibernateTest reads
--    e0000000-...-0002 as "an IE expert"). All four availability states, quality scores
--    either side of the 6.0 bar and one unscored, two onboarded this month.
-- ---------------------------------------------------------------------------
INSERT INTO expert (
    id, brand_id, full_name, title, institution, email, phone,
    primary_fields, secondary_fields, letter_types,
    availability, tier, quality_score, standard_fee, performance_flags,
    agreement_status, payment_status, recruitment_source, date_onboarded, notes, created_at
) VALUES
    ('e0000000-0000-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111', 'Dr Miriam Osei', 'Professor of Mechanical Engineering', 'Rowan State University', 'm.osei@rowanstate.test', '+1-202-555-0111',
     ARRAY['MECHANICAL_ENGINEERING'], ARRAY['MATHEMATICS','PHYSICS'], ARRAY['EXPERT_OPINION_LETTER','RFE_RESPONSE'],
     'AVAILABLE', 'TIER_1', 9.2, 350.00, NULL, 'SIGNED', 'UP_TO_DATE', 'Referral', (now() - interval '29 months')::date, 'Fast on RFEs. Prefers two weeks notice for a full opinion.', now() - interval '29 months'),
    ('e0000000-0000-0000-0000-000000000002', '11111111-1111-1111-1111-111111111111', 'Dr Alan Whitcombe', 'Associate Professor of Computer Science', 'Lakeside Institute of Technology', 'a.whitcombe@lakeside.test', '+1-202-555-0112',
     ARRAY['COMPUTER_SCIENCE','DATA_SCIENCE'], ARRAY['INFORMATION_TECHNOLOGY'], ARRAY['EXPERT_OPINION_LETTER','PERM_LETTER'],
     'AT_CAPACITY', 'TIER_2', 8.0, 300.00, NULL, 'SIGNED', 'UP_TO_DATE', 'Cold outreach', (now() - interval '23 months')::date, 'Says no early rather than late.', now() - interval '23 months'),
    ('e0000000-0000-0000-0000-000000000003', '11111111-1111-1111-1111-111111111111', 'Professor Ada Nwankwo', 'Professor of Civil Engineering', 'Harbour City University', 'a.nwankwo@harbourcity.test', '+1-202-555-0113',
     ARRAY['CIVIL_ENGINEERING'], ARRAY['ARCHITECTURE'], ARRAY['EXPERT_OPINION_LETTER','RFE_RESPONSE','PERM_LETTER'],
     'AVAILABLE', 'TIER_1', 9.5, 400.00, NULL, 'SIGNED', 'UP_TO_DATE', 'Referral', (now() - interval '37 months')::date, 'The one to send an RFE with a short fuse.', now() - interval '37 months'),
    ('e0000000-0000-0000-0000-000000000004', '11111111-1111-1111-1111-111111111111', 'Dr Sofia Marchetti', 'Reader in Economics', 'Northgate School of Economics', 's.marchetti@northgate.test', '+1-202-555-0114',
     ARRAY['ECONOMICS','FINANCE'], ARRAY['BUSINESS_ADMINISTRATION'], ARRAY['CREDENTIAL_EVALUATION','EXPERT_OPINION_LETTER'],
     'ON_LEAVE', 'TIER_2', 8.4, 320.00, NULL, 'SIGNED', 'PENDING', 'Conference', (now() - interval '18 months')::date, 'On research leave until the spring; do not offer.', now() - interval '18 months'),
    ('e0000000-0000-0000-0000-000000000005', '11111111-1111-1111-1111-111111111111', 'Dr Tomas Herrera', 'Clinical Professor of Pharmacy', 'Westbrook College of Pharmacy', 't.herrera@westbrookpharm.test', NULL,
     ARRAY['PHARMACY'], ARRAY['MEDICINE'], ARRAY['CREDENTIAL_EVALUATION'],
     'INACTIVE', 'TIER_3', 5.4, 240.00, ARRAY['SLOW_RESPONSE','QUALITY_ISSUE'], 'EXPIRED', 'OVERDUE', 'Cold outreach', (now() - interval '34 months')::date, 'Agreement lapsed; not offered since.', now() - interval '34 months'),
    ('e0000000-0000-0000-0000-000000000006', '11111111-1111-1111-1111-111111111111', 'Dr Nadia Haddad', 'Associate Professor of Nursing', 'St Aubin School of Nursing', 'n.haddad@staubin.test', '+1-202-555-0116',
     ARRAY['NURSING'], ARRAY['PUBLIC_HEALTH'], ARRAY['CREDENTIAL_EVALUATION','EXPERT_OPINION_LETTER'],
     'AVAILABLE', 'TIER_1', 8.9, 330.00, NULL, 'SIGNED', 'UP_TO_DATE', 'Referral', (now() - interval '10 months')::date, NULL, now() - interval '10 months'),
    ('e0000000-0000-0000-0000-000000000007', '11111111-1111-1111-1111-111111111111', 'Dr Yusuf Karim', 'Senior Lecturer in Law', 'Kingsbridge Law School', 'y.karim@kingsbridge.test', '+1-202-555-0117',
     ARRAY['LAW'], NULL, ARRAY['EXPERT_OPINION_LETTER','RFE_RESPONSE'],
     'AVAILABLE', 'TIER_2', 7.6, 310.00, NULL, 'SIGNED', 'UP_TO_DATE', 'LinkedIn', date_trunc('month', now())::date, 'First case not yet offered.', date_trunc('month', now())),
    ('e0000000-0000-0000-0000-000000000008', '11111111-1111-1111-1111-111111111111', 'Dr Priya Raghunathan', 'Professor of Data Science', 'Calder Institute', 'p.raghunathan@calder.test', NULL,
     ARRAY['DATA_SCIENCE','COMPUTER_SCIENCE'], ARRAY['MATHEMATICS'], ARRAY['EXPERT_OPINION_LETTER','PERM_LETTER'],
     'AVAILABLE', 'TIER_1', NULL, 360.00, NULL, 'SIGNED', 'UP_TO_DATE', 'Referral', date_trunc('month', now())::date, NULL, date_trunc('month', now())),
    ('e0000000-0000-0000-0000-000000000009', '11111111-1111-1111-1111-111111111111', 'Dr Gregor Vance', 'Associate Professor of Chemical Engineering', 'Meridian Polytechnic', 'g.vance@meridianpoly.test', '+1-202-555-0119',
     ARRAY['CHEMICAL_ENGINEERING'], ARRAY['CHEMISTRY'], ARRAY['EXPERT_OPINION_LETTER'],
     'AT_CAPACITY', 'TIER_2', 7.1, 295.00, NULL, 'SIGNED', 'PENDING', 'Partner', (now() - interval '14 months')::date, NULL, now() - interval '14 months'),
    ('e0000000-0000-0000-0000-00000000000a', '11111111-1111-1111-1111-111111111111', 'Dr Helena Brandt', 'Professor of Education', 'Ferngrove University', 'h.brandt@ferngrove.test', NULL,
     ARRAY['EDUCATION'], ARRAY['PSYCHOLOGY'], ARRAY['CREDENTIAL_EVALUATION'],
     'AVAILABLE', 'TIER_3', 5.8, 220.00, ARRAY['DECLINED_CASES'], 'SENT', 'PENDING', 'Cold outreach', (now() - interval '8 months')::date, 'Declined the last two offers.', now() - interval '8 months'),
    ('e0000000-0000-0000-0000-00000000000b', '22222222-2222-2222-2222-222222222222', 'Dr Petra Lindqvist', 'Professor of Public Health', 'Nordvik School of Public Health', 'p.lindqvist@nordvik.test', '+44-20-7946-0801',
     ARRAY['PUBLIC_HEALTH'], ARRAY['BIOLOGY'], ARRAY['CREDENTIAL_EVALUATION','EXPERT_OPINION_LETTER'],
     'AVAILABLE', 'TIER_1', 9.1, 345.00, NULL, 'SIGNED', 'UP_TO_DATE', 'Conference', (now() - interval '26 months')::date, NULL, now() - interval '26 months'),
    ('e0000000-0000-0000-0000-00000000000c', '22222222-2222-2222-2222-222222222222', 'Dr Hassan Rahimi', 'Lecturer in Accounting', 'Silverpoint Business School', 'h.rahimi@silverpoint.test', NULL,
     ARRAY['ACCOUNTING','FINANCE'], ARRAY['ECONOMICS'], ARRAY['CREDENTIAL_EVALUATION'],
     'AVAILABLE', 'TIER_3', 6.9, 250.00, NULL, 'SIGNED', 'UP_TO_DATE', 'Website', (now() - interval '13 months')::date, NULL, now() - interval '13 months'),
    ('e0000000-0000-0000-0000-00000000000d', '22222222-2222-2222-2222-222222222222', 'Dr Ingrid Halvorsen', 'Professor of Nursing', 'Fjordview College of Health', 'i.halvorsen@fjordview.test', '+44-20-7946-0812',
     ARRAY['NURSING'], ARRAY['PUBLIC_HEALTH'], ARRAY['CREDENTIAL_EVALUATION','EXPERT_OPINION_LETTER'],
     'AT_CAPACITY', 'TIER_1', 9.0, 340.00, NULL, 'SIGNED', 'UP_TO_DATE', 'Referral', (now() - interval '21 months')::date, NULL, now() - interval '21 months');

-- ---------------------------------------------------------------------------
-- 3. Demo contacts (GHL's record, copied) -- 24 IE, 4 XP. Amara Okafor keeps her address:
--    she is the client login the docs and screenshots name, and she has three cases.
-- ---------------------------------------------------------------------------
INSERT INTO contact_snapshot (
    id, brand_id, ghl_contact_id, full_name, email, phone, company, client_type,
    source_channel, utm_source, utm_medium, utm_campaign, date_first_captured, synced_at, created_at
)
SELECT ('c1000000-0000-0000-0000-0000000000' || sfx)::uuid, brand::uuid, 'ghl-demo-1' || sfx, full_name, email, phone, company, client_type,
       source, utm_source, utm_medium, NULL,
       now() - (age_days || ' days')::interval, now() - interval '6 hours', now() - (age_days || ' days')::interval
  FROM (VALUES
    ('01','11111111-1111-1111-1111-111111111111','Amara Okafor',     'amara.okafor@northlightlaw.test', '+1-312-555-0201','Northlight Immigration Law','ATTORNEY',  'REFERRAL',      'referral', 'word-of-mouth', 30),
    ('02','11111111-1111-1111-1111-111111111111','Rohan Kapoor',     'r.kapoor@quantaflow.test',        '+1-408-555-0302','Quantaflow Systems',        'EMPLOYER',  'WEBSITE',       'google',   'organic',        2),
    ('03','11111111-1111-1111-1111-111111111111','Beatriz Almeida',  'b.almeida@almeidalaw.test',       '+1-305-555-0303','Almeida Law Group',         'ATTORNEY',  'LINKEDIN',      'linkedin', 'social',        15),
    ('04','11111111-1111-1111-1111-111111111111','Kenji Watanabe',   'k.watanabe@hikarihr.test',        '+1-650-555-0304','Hikari HR',                 'EMPLOYER',  'PARTNER',       'partner',  'referral',       9),
    ('05','11111111-1111-1111-1111-111111111111','Olivia Mensah',    'o.mensah@mailbox.test',           NULL,             NULL,                        'INDIVIDUAL','GOOGLE_ADS',    'google',   'cpc',           12),
    ('06','11111111-1111-1111-1111-111111111111','Daniel Okonkwo',   'd.okonkwo@okonkwolegal.test',     '+1-617-555-0306','Okonkwo Legal',             'ATTORNEY',  'REFERRAL',      'referral', 'word-of-mouth', 10),
    ('07','11111111-1111-1111-1111-111111111111','Leila Haddadi',    'l.haddadi@mailbox.test',          '+1-206-555-0307',NULL,                        'INDIVIDUAL','INSTAGRAM',     'instagram','social',        13),
    ('08','11111111-1111-1111-1111-111111111111','Marco Bianchi',    'm.bianchi@vettasoft.test',        '+1-512-555-0308','Vetta Software',            'EMPLOYER',  'EMAIL_CAMPAIGN','mailchimp','email',         14),
    ('09','11111111-1111-1111-1111-111111111111','Hannah Fischer',   'h.fischer@nordlicht.test',        '+1-212-555-0309','Nordlicht Engineering',     'EMPLOYER',  'WEBSITE',       'direct',   'none',          17),
    ('10','11111111-1111-1111-1111-111111111111','Samuel Adeyemi',   's.adeyemi@mailbox.test',          NULL,             NULL,                        'AGENT',     'FACEBOOK',      'facebook', 'social',        16),
    ('11','11111111-1111-1111-1111-111111111111','Grace Liu',        'g.liu@pacificbridge.test',        '+1-415-555-0311','Pacific Bridge Partners',   'EMPLOYER',  'PARTNER',       'partner',  'referral',      21),
    ('12','11111111-1111-1111-1111-111111111111','Thomas Reyes',     't.reyes@reyesimmigration.test',   '+1-713-555-0312','Reyes Immigration',         'ATTORNEY',  'REFERRAL',      'referral', 'word-of-mouth', 23),
    ('13','11111111-1111-1111-1111-111111111111','Aisha Bello',      'a.bello@solaris.test',            '+1-404-555-0313','Solaris Energy',            'EMPLOYER',  'LINKEDIN',      'linkedin', 'social',        20),
    ('14','11111111-1111-1111-1111-111111111111','Victor Hale',      'v.hale@halepartners.test',        '+1-202-555-0314','Hale and Partners',         'ATTORNEY',  'REFERRAL',      'referral', 'word-of-mouth', 25),
    ('15','11111111-1111-1111-1111-111111111111','Mina Park',        'm.park@mailbox.test',             NULL,             NULL,                        'INDIVIDUAL','WEBSITE',       'direct',   'none',          18),
    ('16','11111111-1111-1111-1111-111111111111','Arjun Nair',       'a.nair@kestrelhr.test',           '+1-646-555-0316','Kestrel HR',                'EMPLOYER',  'GOOGLE_ADS',    'google',   'cpc',           32),
    ('17','11111111-1111-1111-1111-111111111111','Clara Jensen',     'c.jensen@jensenlaw.test',         '+1-312-555-0317','Jensen Law',                'ATTORNEY',  'REFERRAL',      'referral', 'word-of-mouth', 52),
    ('18','11111111-1111-1111-1111-111111111111','Felix Wagner',     'f.wagner@mailbox.test',           NULL,             NULL,                        'INDIVIDUAL','WHATSAPP',      'whatsapp', 'chat',          64),
    ('19','11111111-1111-1111-1111-111111111111','Rosa Martinez',    'r.martinez@martinezlegal.test',   '+1-305-555-0319','Martinez Legal',            'ATTORNEY',  'LINKEDIN',      'linkedin', 'social',        97),
    ('20','11111111-1111-1111-1111-111111111111','David Cohen',      'd.cohen@arcadiatech.test',        '+1-617-555-0320','Arcadia Tech',              'EMPLOYER',  'WEBSITE',       'direct',   'none',         132),
    ('21','11111111-1111-1111-1111-111111111111','Nora Lindgren',    'n.lindgren@borealhr.test',        '+1-206-555-0321','Boreal HR',                 'EMPLOYER',  'PARTNER',       'partner',  'referral',     162),
    ('22','11111111-1111-1111-1111-111111111111','Ibrahim Diallo',   'i.diallo@mailbox.test',           NULL,             NULL,                        'INDIVIDUAL','GOOGLE_ADS',    'google',   'cpc',          197),
    ('23','11111111-1111-1111-1111-111111111111','Elise Moreau',     'e.moreau@moreauavocats.test',     '+1-514-555-0323','Moreau Avocats',            'ATTORNEY',  'REFERRAL',      'referral', 'word-of-mouth',232),
    ('24','11111111-1111-1111-1111-111111111111','Kwabena Owusu',    'k.owusu@mailbox.test',            '+1-678-555-0324',NULL,                        'INDIVIDUAL','EMAIL_CAMPAIGN','mailchimp','email',        272),
    ('25','22222222-2222-2222-2222-222222222222','Sigrid Nyberg',    's.nyberg@mailbox.test',           '+46-8-555-0325', NULL,                        'INDIVIDUAL','WEBSITE',       'direct',   'none',           3),
    ('26','22222222-2222-2222-2222-222222222222','Lars Eriksen',     'l.eriksen@fjordtech.test',        '+46-8-555-0326', 'Fjordtech AB',              'EMPLOYER',  'LINKEDIN',      'linkedin', 'social',        12),
    ('27','22222222-2222-2222-2222-222222222222','Maja Holm',        'm.holm@mailbox.test',             NULL,             NULL,                        'INDIVIDUAL','GOOGLE_ADS',    'google',   'cpc',           45),
    ('28','22222222-2222-2222-2222-222222222222','Erik Sandberg',    'e.sandberg@sandberglegal.test',   '+46-8-555-0328', 'Sandberg Legal',            'ATTORNEY',  'REFERRAL',      'referral', 'word-of-mouth',115)
  ) AS t(sfx, brand, full_name, email, phone, company, client_type, source, utm_source, utm_medium, age_days);

-- ---------------------------------------------------------------------------
-- 4. Portal accounts: every client and every expert signs in with the dev password.
--    Terms are not pre-accepted, so the first sign-in shows the policy screen, as it would.
-- ---------------------------------------------------------------------------
INSERT INTO client_account (id, brand_id, email, password_hash, ghl_contact_id, contact_id,
                            first_name, last_name, created_via, created_at)
SELECT gen_random_uuid(), c.brand_id, c.email,
       '$2a$10$r5HWTZRMQLgLPJKNHaZGgujwqeEjBbsDR8dpmh6JuZ7QdUjE1DHMW',
       c.ghl_contact_id, c.id, split_part(c.full_name, ' ', 1), split_part(c.full_name, ' ', 2), 'SEED', c.created_at
  FROM contact_snapshot c
 WHERE c.ghl_contact_id LIKE 'ghl-demo-1%';

INSERT INTO expert_account (id, brand_id, expert_id, password_hash, created_at)
SELECT gen_random_uuid(), e.brand_id, e.id, '$2a$10$r5HWTZRMQLgLPJKNHaZGgujwqeEjBbsDR8dpmh6JuZ7QdUjE1DHMW', e.created_at
  FROM expert e;

-- ---------------------------------------------------------------------------
-- 5. Cases: all twelve stages and all three exceptions, both brands.
--
--    IE cases are staffed by the IE team (PM ...04, CM ...05, PC ...06, team bbbb...01); XP
--    cases by the XP brand manager (...03), which is the only XP staff login. The expert is
--    offered only once a draft exists (Unit 73), so cases before drafting have none.
--    `draft_version_count` always equals the DRAFT rows seeded in section 7.
--
--    The two logins to try first:
--      client amara.okafor@northlightlaw.test -- 5101 collecting documents, 5110 in client
--        review (approve or ask for changes), 5117 delivered (signed letter to view).
--      expert m.osei@rowanstate.test -- 5112 offered (answer it), 5113 accepted (upload the
--        signed letter), 5117 delivered and 5019 closed (payouts, one to confirm).
-- ---------------------------------------------------------------------------
INSERT INTO evalos_case (
    id, brand_id, team_id, case_code, pool_status, assigned_pm, assigned_cm, assigned_coordinator,
    contact_id, applicant_name, service_type, service_subtype, client_type, deal_value, deadline,
    current_stage, exception_state, stage_entered_at, sla_status,
    expert_id, expert_sign_status, draft_version_count, pm_approval_status, client_approval_status,
    delivery_date, case_closed_date, paid, paid_at, created_at, ghl_opportunity_id
)
SELECT ('ca100000-0000-0000-0000-0000000000' || sfx)::uuid,
       CASE WHEN brand = 'IE' THEN '11111111-1111-1111-1111-111111111111'::uuid ELSE '22222222-2222-2222-2222-222222222222'::uuid END,
       CASE WHEN brand = 'IE' AND pool = 'ASSIGNED' THEN 'bbbbbbbb-0000-0000-0000-000000000001'::uuid END,
       code, pool,
       CASE WHEN pool = 'IN_POOL' THEN NULL WHEN brand = 'IE' THEN 'aaaaaaaa-0000-0000-0000-000000000004'::uuid ELSE 'aaaaaaaa-0000-0000-0000-000000000003'::uuid END,
       CASE WHEN NOT has_cm THEN NULL WHEN brand = 'IE' THEN 'aaaaaaaa-0000-0000-0000-000000000005'::uuid ELSE 'aaaaaaaa-0000-0000-0000-000000000003'::uuid END,
       CASE WHEN pool = 'ASSIGNED' AND brand = 'IE' THEN 'aaaaaaaa-0000-0000-0000-000000000006'::uuid END,
       ('c1000000-0000-0000-0000-0000000000' || contact)::uuid,
       (SELECT full_name FROM contact_snapshot WHERE id = ('c1000000-0000-0000-0000-0000000000' || contact)::uuid),
       service, subtype, client_type, value,
       now() + (deadline_days || ' days')::interval,
       stage, exception, now() - (stage_days || ' days')::interval, sla,
       CASE WHEN expert IS NULL THEN NULL ELSE ('e0000000-0000-0000-0000-0000000000' || expert)::uuid END,
       sign, drafts, pm_approval, client_approval,
       CASE WHEN delivered_days IS NULL THEN NULL ELSE now() - (delivered_days || ' days')::interval END,
       CASE WHEN closed_days IS NULL THEN NULL ELSE now() - (closed_days || ' days')::interval END,
       paid, CASE WHEN paid THEN now() - (age_days || ' days')::interval + interval '1 day' END,
       now() - (age_days || ' days')::interval,
       'ghl-opp-seed-' || sfx
  FROM (VALUES
    -- sfx contact code          brand pool       cm     service                 subtype                     clientType   value   dl  stage              exception                    sd  sla        expert sign       d  pm         client      dlv  cls  paid  age
    ('01','01','IE-2026-5101','IE','ASSIGNED',true, 'CREDENTIAL_EVALUATION','COURSE_BY_COURSE',         'INDIVIDUAL', 285.00,  9,'DOC_COLLECTION',   'NONE',                      3,'ON_TRACK', NULL,NULL,      0,NULL,      NULL,      NULL::int,NULL::int,true,  3),
    ('02','02','IE-2026-5102','IE','IN_POOL', false,'EXPERT_OPINION_LETTER',NULL,                       'EMPLOYER',  1650.00, 14,'DOC_COLLECTION',   'NONE',                      1,'ON_TRACK', NULL,NULL,      0,NULL,      NULL,      NULL,NULL,false, 1),
    ('03','03','IE-2026-5103','IE','ASSIGNED',true, 'RFE_RESPONSE',         NULL,                       'ATTORNEY',  1200.00, -1,'DOC_COLLECTION',   'ON_HOLD_AWAITING_CLIENT',  12,'OVERDUE',  NULL,NULL,      0,NULL,      NULL,      NULL,NULL,true, 14),
    ('04','04','IE-2026-5104','IE','ASSIGNED',false,'PERM',                 NULL,                       'EMPLOYER',  1450.00,  6,'PM_REVIEW',        'NONE',                      2,'ON_TRACK', NULL,NULL,      0,NULL,      NULL,      NULL,NULL,true,  8),
    ('05','05','IE-2026-5105','IE','ASSIGNED',false,'CREDENTIAL_EVALUATION','EDUCATION_PLUS_EXPERIENCE','INDIVIDUAL', 295.00,  3,'PM_REVIEW',        'REFUND_REQUESTED',          5,'AT_RISK',  NULL,NULL,      0,NULL,      NULL,      NULL,NULL,true, 11),
    ('06','06','IE-2026-5106','IE','ASSIGNED',true, 'EXPERT_OPINION_LETTER',NULL,                       'ATTORNEY',  1800.00, 10,'DRAFT_IN_PROGRESS','NONE',                      2,'ON_TRACK', NULL,NULL,      0,NULL,      NULL,      NULL,NULL,true,  9),
    ('07','07','IE-2026-5107','IE','ASSIGNED',true, 'RFE_RESPONSE',         NULL,                       'INDIVIDUAL',1250.00,  2,'DRAFT_IN_PROGRESS','NONE',                      1,'AT_RISK',  '03','PENDING',   1,'RETURNED',NULL,      NULL,NULL,true, 12),
    ('08','08','IE-2026-5108','IE','ASSIGNED',true, 'EXPERT_OPINION_LETTER',NULL,                       'EMPLOYER',  1690.00,  5,'DRAFT_REVIEW',     'NONE',                      1,'ON_TRACK', '02','PENDING',   1,'PENDING', NULL,      NULL,NULL,true, 13),
    ('09','09','IE-2026-5109','IE','ASSIGNED',true, 'PERM',                 NULL,                       'EMPLOYER',  1500.00,  4,'READY_TO_SEND',    'NONE',                      1,'ON_TRACK', '09','PENDING',   2,'APPROVED',NULL,      NULL,NULL,true, 16),
    ('10','01','IE-2026-5110','IE','ASSIGNED',true, 'EXPERT_OPINION_LETTER',NULL,                       'INDIVIDUAL',1725.00,  6,'CLIENT_REVIEW',    'NONE',                      2,'ON_TRACK', '06','PENDING',   1,'APPROVED','PENDING', NULL,NULL,true, 18),
    ('11','10','IE-2026-5111','IE','ASSIGNED',true, 'CREDENTIAL_EVALUATION','WORK_EXPERIENCE_ONLY',     'AGENT',      265.00,  3,'CLIENT_APPROVAL',  'NONE',                      1,'ON_TRACK', '03','PENDING',   1,'APPROVED','APPROVED',NULL,NULL,true, 15),
    ('12','11','IE-2026-5112','IE','ASSIGNED',true, 'EXPERT_OPINION_LETTER',NULL,                       'EMPLOYER',  1750.00,  5,'EXPERT_SIGNING',   'NONE',                      1,'ON_TRACK', '01','PENDING',   1,'APPROVED','APPROVED',NULL,NULL,true, 20),
    ('13','12','IE-2026-5113','IE','ASSIGNED',true, 'RFE_RESPONSE',         NULL,                       'ATTORNEY',  1300.00,  2,'EXPERT_SIGNING',   'NONE',                      3,'AT_RISK',  '01','PENDING',   2,'APPROVED','APPROVED',NULL,NULL,true, 22),
    ('14','13','IE-2026-5114','IE','ASSIGNED',true, 'PERM',                 NULL,                       'EMPLOYER',  1480.00,  1,'EXPERT_SIGNING',   'EXPERT_DECLINED_REMATCHING',2,'AT_RISK',  '0a','PENDING',   1,'APPROVED','APPROVED',NULL,NULL,true, 19),
    ('15','14','IE-2026-5115','IE','ASSIGNED',true, 'EXPERT_OPINION_LETTER',NULL,                       'ATTORNEY',  1800.00,  3,'FINAL_QC',         'NONE',                      1,'ON_TRACK', '03','SIGNED',    1,'APPROVED','APPROVED',NULL,NULL,true, 24),
    ('16','15','IE-2026-5116','IE','ASSIGNED',true, 'CREDENTIAL_EVALUATION','COURSE_BY_COURSE',         'INDIVIDUAL', 290.00,  2,'READY_TO_DELIVER', 'NONE',                      1,'ON_TRACK', '06','SIGNED',    1,'APPROVED','APPROVED',NULL,NULL,true, 17),
    ('17','01','IE-2026-5117','IE','ASSIGNED',true, 'RFE_RESPONSE',         NULL,                       'INDIVIDUAL',1250.00, -3,'DELIVERED',        'NONE',                      2,'ON_TRACK', '01','SIGNED',    1,'APPROVED','APPROVED',   2,NULL,true, 26),
    ('18','16','IE-2026-5118','IE','ASSIGNED',true, 'PERM',                 NULL,                       'EMPLOYER',  1450.00, -6,'DELIVERED',        'NONE',                      5,'ON_TRACK', '02','SIGNED',    1,'APPROVED','APPROVED',   5,NULL,true, 31),
    ('19','17','IE-2026-5019','IE','ASSIGNED',true, 'EXPERT_OPINION_LETTER',NULL,                       'ATTORNEY',  1725.00,-21,'CLOSED',           'NONE',                     21,'ON_TRACK', '01','SIGNED',    1,'APPROVED','APPROVED',  23,  21,true, 50),
    ('20','18','IE-2026-5020','IE','ASSIGNED',true, 'CREDENTIAL_EVALUATION','COURSE_BY_COURSE',         'INDIVIDUAL', 275.00,-40,'CLOSED',           'NONE',                     40,'ON_TRACK', '06','SIGNED',    1,'APPROVED','APPROVED',  42,  40,true, 62),
    ('21','19','IE-2026-5021','IE','ASSIGNED',true, 'RFE_RESPONSE',         NULL,                       'ATTORNEY',  1180.00,-70,'CLOSED',           'NONE',                     70,'ON_TRACK', '03','SIGNED',    1,'APPROVED','APPROVED',  72,  70,true, 95),
    ('22','20','IE-2026-4922','IE','ASSIGNED',true, 'EXPERT_OPINION_LETTER',NULL,                       'EMPLOYER',  1690.00,-100,'CLOSED',          'NONE',                    100,'ON_TRACK', '02','SIGNED',    1,'APPROVED','APPROVED', 102, 100,true,130),
    ('23','21','IE-2026-4923','IE','ASSIGNED',true, 'PERM',                 NULL,                       'EMPLOYER',  1425.00,-135,'CLOSED',          'NONE',                    135,'ON_TRACK', '09','SIGNED',    1,'APPROVED','APPROVED', 137, 135,true,160),
    ('24','22','IE-2026-4924','IE','ASSIGNED',true, 'CREDENTIAL_EVALUATION','WORK_EXPERIENCE_ONLY',     'INDIVIDUAL', 260.00,-170,'CLOSED',          'NONE',                    170,'ON_TRACK', '06','SIGNED',    1,'APPROVED','APPROVED', 172, 170,true,195),
    ('25','23','IE-2026-4825','IE','ASSIGNED',true, 'EXPERT_OPINION_LETTER',NULL,                       'ATTORNEY',  1800.00,-200,'CLOSED',          'NONE',                    200,'ON_TRACK', '01','SIGNED',    1,'APPROVED','APPROVED', 202, 200,true,230),
    ('26','24','IE-2026-4826','IE','ASSIGNED',true, 'RFE_RESPONSE',         NULL,                       'INDIVIDUAL',1210.00,-240,'CLOSED',          'NONE',                    240,'ON_TRACK', '03','SIGNED',    1,'APPROVED','APPROVED', 242, 240,true,270),
    ('27','25','XP-2026-5127','XP','ASSIGNED',true, 'CREDENTIAL_EVALUATION','COURSE_BY_COURSE',         'INDIVIDUAL', 240.00,  7,'DOC_COLLECTION',   'NONE',                      2,'ON_TRACK', NULL,NULL,      0,NULL,      NULL,      NULL,NULL,true,  3),
    ('28','26','XP-2026-5128','XP','ASSIGNED',true, 'EXPERT_OPINION_LETTER',NULL,                       'EMPLOYER',  1600.00,  6,'DRAFT_REVIEW',     'NONE',                      1,'ON_TRACK', '0b','PENDING',   1,'PENDING', NULL,      NULL,NULL,true, 12),
    ('29','27','XP-2026-5029','XP','ASSIGNED',true, 'CREDENTIAL_EVALUATION','COURSE_BY_COURSE',         'INDIVIDUAL', 255.00,-25,'CLOSED',           'NONE',                     25,'ON_TRACK', '0d','SIGNED',    1,'APPROVED','APPROVED',  27,  25,true, 45),
    ('30','28','XP-2026-4930','XP','ASSIGNED',true, 'EXPERT_OPINION_LETTER',NULL,                       'ATTORNEY',  1580.00,-90,'CLOSED',           'NONE',                     90,'ON_TRACK', '0b','SIGNED',    1,'APPROVED','APPROVED',  92,  90,true,115)
  ) AS t(sfx, contact, code, brand, pool, has_cm, service, subtype, client_type, value, deadline_days,
         stage, exception, stage_days, sla, expert, sign, drafts, pm_approval, client_approval,
         delivered_days, closed_days, paid, age_days);

-- ---------------------------------------------------------------------------
-- 6. Checklists: sent (D60 -- the client sees an item only once it is sent), mixed statuses on
--    the cases still collecting, complete on the two waiting for the PM.
-- ---------------------------------------------------------------------------
INSERT INTO document_checklist_item (brand_id, case_id, label, status, sent_at, sent_by, updated_at, created_at)
SELECT c.brand_id, c.id, d.label, d.status,
       c.created_at + interval '2 hours',
       CASE WHEN c.brand_id = '22222222-2222-2222-2222-222222222222'::uuid THEN 'aaaaaaaa-0000-0000-0000-000000000003'::uuid
            ELSE 'aaaaaaaa-0000-0000-0000-000000000006'::uuid END,
       now() - (d.age || ' days')::interval, c.created_at
  FROM (VALUES
    ('01','Degree certificate',            'APPROVED',  2),
    ('01','Academic transcript',           'UPLOADED',  1),
    ('01','Passport bio page',             'REQUIRED',  3),
    ('03','RFE notice',                    'APPROVED', 11),
    ('03','Prior petition copy',           'INCORRECT', 6),
    ('03','Supporting evidence bundle',    'MISSING',   4),
    ('04','Employment verification letter','APPROVED',  3),
    ('04','Job description',               'APPROVED',  3),
    ('05','Degree certificate',            'APPROVED',  6),
    ('05','Experience letters',            'APPROVED',  6),
    ('27','Degree certificate',            'REQUIRED',  2),
    ('27','Academic transcript',           'UPLOADED',  1)
  ) AS d(sfx, label, status, age)
  JOIN evalos_case c ON c.id = ('ca100000-0000-0000-0000-0000000000' || d.sfx)::uuid;

-- ---------------------------------------------------------------------------
-- 7. Documents, each on a sample file under `seed/` (see the header).
--    Drafts: Word + PDF per version, uploaded by the CM. A later version supersedes nothing
--    here because every earlier one was RETURNED. The expert's letter is the newest
--    CLIENT_APPROVED draft, so that status stays through signing and delivery.
-- ---------------------------------------------------------------------------
INSERT INTO case_document (id, brand_id, case_id, kind, version, object_key, filename, content_type, size_bytes,
                           uploaded_by, uploaded_by_type, uploaded_at, notes, status, created_at, review_comment,
                           pdf_object_key, pdf_filename, pdf_size_bytes)
SELECT gen_random_uuid(), c.brand_id, c.id, 'DRAFT', d.version,
       'seed/draft.docx', 'Draft v' || d.version || '.docx',
       'application/vnd.openxmlformats-officedocument.wordprocessingml.document', 1027,
       CASE WHEN c.brand_id = '22222222-2222-2222-2222-222222222222'::uuid THEN 'aaaaaaaa-0000-0000-0000-000000000003'::uuid
            ELSE 'aaaaaaaa-0000-0000-0000-000000000005'::uuid END,
       'STAFF', c.stage_entered_at - ((3 - d.version) || ' days')::interval, NULL, d.status,
       c.stage_entered_at - ((3 - d.version) || ' days')::interval, d.comment,
       'seed/draft.pdf', 'Draft v' || d.version || '.pdf', 786
  FROM (VALUES
    ('07',1,'RETURNED',       'Tighten the section on prior roles and cite the RFE paragraph.'),
    ('08',1,'SUBMITTED',      NULL),
    ('09',1,'RETURNED',       'Wrong job code on page 2.'),
    ('09',2,'PM_APPROVED',    NULL),
    ('10',1,'PM_APPROVED',    NULL),
    ('11',1,'CLIENT_APPROVED',NULL),
    ('12',1,'CLIENT_APPROVED',NULL),
    ('13',1,'RETURNED',       'Add the second publication.'),
    ('13',2,'CLIENT_APPROVED',NULL),
    ('14',1,'CLIENT_APPROVED',NULL),
    ('15',1,'CLIENT_APPROVED',NULL),
    ('16',1,'CLIENT_APPROVED',NULL),
    ('17',1,'CLIENT_APPROVED',NULL),
    ('18',1,'CLIENT_APPROVED',NULL),
    ('19',1,'CLIENT_APPROVED',NULL),
    ('20',1,'CLIENT_APPROVED',NULL),
    ('21',1,'CLIENT_APPROVED',NULL),
    ('22',1,'CLIENT_APPROVED',NULL),
    ('23',1,'CLIENT_APPROVED',NULL),
    ('24',1,'CLIENT_APPROVED',NULL),
    ('25',1,'CLIENT_APPROVED',NULL),
    ('26',1,'CLIENT_APPROVED',NULL),
    ('28',1,'SUBMITTED',      NULL),
    ('29',1,'CLIENT_APPROVED',NULL),
    ('30',1,'CLIENT_APPROVED',NULL)
  ) AS d(sfx, version, status, comment)
  JOIN evalos_case c ON c.id = ('ca100000-0000-0000-0000-0000000000' || d.sfx)::uuid;

-- Signed letters: every case past signing.
INSERT INTO case_document (id, brand_id, case_id, kind, version, object_key, filename, content_type, size_bytes,
                           uploaded_by, uploaded_by_type, uploaded_at, status, created_at, attestation, attested_name)
SELECT gen_random_uuid(), c.brand_id, c.id, 'SIGNED_LETTER', 1,
       'seed/signed-letter.pdf', 'Signed letter.pdf', 'application/pdf', 787,
       NULL, 'EXPERT', c.stage_entered_at - interval '1 day', 'SUBMITTED', c.stage_entered_at - interval '1 day',
       'I signed this letter myself.', e.full_name
  FROM evalos_case c
  JOIN expert e ON e.id = c.expert_id
 WHERE c.id IN (SELECT ('ca100000-0000-0000-0000-0000000000' || s)::uuid
                  FROM unnest(ARRAY['15','16','17','18','19','20','21','22','23','24','25','26','29','30']) AS s);

-- Client uploads: `notes` is the checklist label the upload answered (as the portal writes it).
INSERT INTO case_document (id, brand_id, case_id, kind, version, object_key, filename, content_type, size_bytes,
                           uploaded_by, uploaded_by_type, uploaded_at, notes, status, created_at)
SELECT gen_random_uuid(), c.brand_id, c.id, 'CLIENT_UPLOAD', u.version, u.object_key, u.filename, u.content_type, u.size_bytes,
       NULL, 'CLIENT', c.created_at + (u.after_days || ' days')::interval, u.label, 'SUBMITTED', c.created_at + (u.after_days || ' days')::interval
  FROM (VALUES
    ('01',1,'seed/client-document.pdf','Academic transcript.pdf','application/pdf',785,  'Academic transcript',1),
    ('03',1,'seed/client-document.pdf','RFE notice.pdf',         'application/pdf',785,  'RFE notice',         1),
    ('03',2,'seed/client-photo.png',   'Prior petition photo.png','image/png',     54872,'Prior petition copy',2),
    ('12',1,'seed/client-document.pdf','CV and publications.pdf','application/pdf',785,  'CV and publications',1),
    ('12',2,'seed/client-photo.png',   'Passport photo.png',     'image/png',      54872,'Passport bio page',  1),
    ('13',1,'seed/client-document.pdf','RFE notice.pdf',         'application/pdf',785,  'RFE notice',         1),
    ('27',1,'seed/client-document.pdf','Transcript.pdf',         'application/pdf',785,  'Academic transcript',1)
  ) AS u(sfx, version, object_key, filename, content_type, size_bytes, label, after_days)
  JOIN evalos_case c ON c.id = ('ca100000-0000-0000-0000-0000000000' || u.sfx)::uuid;

-- ---------------------------------------------------------------------------
-- 8. Offers, priced at the expert's standard fee (Unit 65) and carrying a note (Unit 71).
--    Answered only in EXPERT_SIGNING, so earlier ones are still OFFERED.
-- ---------------------------------------------------------------------------
INSERT INTO expert_case_offer (id, brand_id, case_id, expert_id, offered_at, outcome, outcome_at, decline_reason,
                               created_at, fee, fee_set_by, fee_set_at, note)
SELECT gen_random_uuid(), c.brand_id, c.id, e.id,
       now() - (o.offered || ' days')::interval, o.outcome,
       CASE WHEN o.outcome = 'OFFERED' THEN NULL ELSE now() - (o.resolved || ' days')::interval END,
       o.reason, now() - (o.offered || ' days')::interval,
       e.standard_fee,
       CASE WHEN c.brand_id = '22222222-2222-2222-2222-222222222222'::uuid THEN 'aaaaaaaa-0000-0000-0000-000000000003'::uuid
            ELSE 'aaaaaaaa-0000-0000-0000-000000000004'::uuid END,
       now() - (o.offered || ' days')::interval,
       'Please review the approved letter and the client''s documents before signing.'
  FROM (VALUES
    ('07','03','OFFERED',   1,  NULL::int, NULL),
    ('08','02','OFFERED',   2,  NULL, NULL),
    ('09','09','OFFERED',   3,  NULL, NULL),
    ('10','06','OFFERED',   4,  NULL, NULL),
    ('11','03','OFFERED',   2,  NULL, NULL),
    ('12','01','OFFERED',   1,  NULL, NULL),
    ('13','01','ACCEPTED',  4,     3, NULL),
    ('14','0a','DECLINED',  4,     2, 'Outside my field; an engineering opinion would serve better.'),
    ('15','03','ACCEPTED',  6,     5, NULL),
    ('16','06','ACCEPTED',  5,     4, NULL),
    ('17','01','ACCEPTED',  8,     7, NULL),
    ('18','02','ACCEPTED', 11,    10, NULL),
    ('19','01','ACCEPTED', 30,    29, NULL),
    ('20','06','ACCEPTED', 48,    47, NULL),
    ('21','03','ACCEPTED', 78,    77, NULL),
    ('22','0a','DECLINED',112,   111, 'Away that month.'),
    ('22','02','ACCEPTED',110,   109, NULL),
    ('23','09','ACCEPTED',145,   144, NULL),
    ('24','06','ACCEPTED',180,   179, NULL),
    ('25','01','ACCEPTED',210,   209, NULL),
    ('26','03','ACCEPTED',250,   249, NULL),
    ('28','0b','OFFERED',   2,  NULL, NULL),
    ('29','0d','ACCEPTED', 32,    31, NULL),
    ('30','0b','ACCEPTED', 98,    97, NULL)
  ) AS o(sfx, expert, outcome, offered, resolved, reason)
  JOIN evalos_case c ON c.id = ('ca100000-0000-0000-0000-0000000000' || o.sfx)::uuid
  JOIN expert e ON e.id = ('e0000000-0000-0000-0000-0000000000' || o.expert)::uuid;

-- ---------------------------------------------------------------------------
-- 9. Payouts. A payout opens at delivery (PENDING), so only DELIVERED and CLOSED cases have
--    one. Payments group an expert's settled work; one payment to Dr Osei is recorded but not
--    yet confirmed by her (PAID), which is the "Confirm received" button in her portal.
-- ---------------------------------------------------------------------------
INSERT INTO payout_payment (id, brand_id, expert_id, amount, currency, method, reference, paid_date, notes, confirmed_at, recorded_by, created_at)
VALUES
    ('b2000000-0000-0000-0000-000000000001','11111111-1111-1111-1111-111111111111','e0000000-0000-0000-0000-000000000001', 350.00,'USD','Wire','TRF-2026-1001', now() - interval '12 days', 'IE-2026-5019.', NULL,                     'aaaaaaaa-0000-0000-0000-000000000007', now() - interval '12 days'),
    ('b2000000-0000-0000-0000-000000000002','11111111-1111-1111-1111-111111111111','e0000000-0000-0000-0000-000000000006', 660.00,'USD','ACH', 'TRF-2026-0981', now() - interval '35 days', 'Two evaluations.', now() - interval '33 days','aaaaaaaa-0000-0000-0000-000000000007', now() - interval '35 days'),
    ('b2000000-0000-0000-0000-000000000003','11111111-1111-1111-1111-111111111111','e0000000-0000-0000-0000-000000000003', 800.00,'USD','Wire','TRF-2026-0955', now() - interval '65 days', 'Two RFE letters.', now() - interval '63 days','aaaaaaaa-0000-0000-0000-000000000007', now() - interval '65 days'),
    ('b2000000-0000-0000-0000-000000000004','11111111-1111-1111-1111-111111111111','e0000000-0000-0000-0000-000000000002', 300.00,'USD','PayPal','PP-2026-7710', now() - interval '95 days', NULL,           now() - interval '93 days','aaaaaaaa-0000-0000-0000-000000000007', now() - interval '95 days'),
    ('b2000000-0000-0000-0000-000000000005','11111111-1111-1111-1111-111111111111','e0000000-0000-0000-0000-000000000001', 350.00,'USD','Wire','TRF-2026-0610', now() - interval '195 days', NULL,          now() - interval '193 days','aaaaaaaa-0000-0000-0000-000000000007', now() - interval '195 days'),
    ('b2000000-0000-0000-0000-000000000006','22222222-2222-2222-2222-222222222222','e0000000-0000-0000-0000-00000000000d', 340.00,'USD','Zelle','TRF-2026-0991', now() - interval '20 days', NULL,           now() - interval '18 days','aaaaaaaa-0000-0000-0000-000000000003', now() - interval '20 days'),
    ('b2000000-0000-0000-0000-000000000007','22222222-2222-2222-2222-222222222222','e0000000-0000-0000-0000-00000000000b', 345.00,'USD','Zelle','TRF-2026-0870', now() - interval '85 days', NULL,           now() - interval '83 days','aaaaaaaa-0000-0000-0000-000000000003', now() - interval '85 days');

INSERT INTO payout_ledger (id, brand_id, case_id, expert_id, amount, currency, status, due_date, recorded_by, payment_id, created_at)
SELECT gen_random_uuid(), c.brand_id, c.id, c.expert_id, e.standard_fee, 'USD', p.status,
       c.delivery_date + interval '14 days',
       CASE WHEN c.brand_id = '22222222-2222-2222-2222-222222222222'::uuid THEN 'aaaaaaaa-0000-0000-0000-000000000003'::uuid
            ELSE 'aaaaaaaa-0000-0000-0000-000000000007'::uuid END,
       p.payment::uuid, c.delivery_date
  FROM (VALUES
    ('17','PENDING',   NULL),
    ('18','PENDING',   NULL),
    ('19','PAID',      'b2000000-0000-0000-0000-000000000001'),
    ('20','CONFIRMED', 'b2000000-0000-0000-0000-000000000002'),
    ('21','CONFIRMED', 'b2000000-0000-0000-0000-000000000003'),
    ('22','CONFIRMED', 'b2000000-0000-0000-0000-000000000004'),
    ('23','CONFIRMED', NULL),
    ('24','CONFIRMED', 'b2000000-0000-0000-0000-000000000002'),
    ('25','CONFIRMED', 'b2000000-0000-0000-0000-000000000005'),
    ('26','CONFIRMED', 'b2000000-0000-0000-0000-000000000003'),
    ('29','CONFIRMED', 'b2000000-0000-0000-0000-000000000006'),
    ('30','CONFIRMED', 'b2000000-0000-0000-0000-000000000007')
  ) AS p(sfx, status, payment)
  JOIN evalos_case c ON c.id = ('ca100000-0000-0000-0000-0000000000' || p.sfx)::uuid
  JOIN expert e ON e.id = c.expert_id;

-- ---------------------------------------------------------------------------
-- 10. A few bell notifications, some unread.
-- ---------------------------------------------------------------------------
INSERT INTO notification (brand_id, recipient_id, type, case_id, body, read, created_at)
SELECT c.brand_id, n.recipient::uuid, n.type, c.id, n.body, n.read, now() - (n.hours || ' hours')::interval
  FROM (VALUES
    ('03','aaaaaaaa-0000-0000-0000-000000000004','SLA_OVERDUE',     'IE-2026-5103 is past its deadline and on hold awaiting the client.', false,  4),
    ('02','aaaaaaaa-0000-0000-0000-000000000004','NEW_CASE_IN_POOL','IE-2026-5102 has arrived and is unclaimed.',                         false, 20),
    ('05','aaaaaaaa-0000-0000-0000-000000000001','EXCEPTION_RAISED','A refund was requested on IE-2026-5105.',                            false,  9),
    ('08','aaaaaaaa-0000-0000-0000-000000000004','STAGE_CHANGED',   'A draft of IE-2026-5108 is waiting for your review.',                false,  6),
    ('07','aaaaaaaa-0000-0000-0000-000000000005','STAGE_CHANGED',   'IE-2026-5107 came back from review: tighten the prior-roles section.',false, 12),
    ('14','aaaaaaaa-0000-0000-0000-000000000007','EXCEPTION_RAISED','Dr Helena Brandt declined IE-2026-5114; it needs another expert.',    false,  8),
    ('16','aaaaaaaa-0000-0000-0000-000000000006','STAGE_CHANGED',   'IE-2026-5116 is ready to deliver.',                                  true,  22),
    ('27','aaaaaaaa-0000-0000-0000-000000000003','NEW_CASE_IN_POOL','XP-2026-5127 has arrived.',                                          false, 40)
  ) AS n(sfx, recipient, type, body, read, hours)
  JOIN evalos_case c ON c.id = ('ca100000-0000-0000-0000-0000000000' || n.sfx)::uuid;

-- ---------------------------------------------------------------------------
-- 11. One "case created" row per case, so every timeline opens with where the case came from.
-- ---------------------------------------------------------------------------
INSERT INTO audit_event (id, brand_id, object_type, object_id, action, actor_id, actor_type, after_snapshot, created_at)
SELECT gen_random_uuid(), c.brand_id, 'CASE', c.id, 'CREATED', NULL, 'SYSTEM',
       jsonb_build_object('caseCode', c.case_code, 'source', 'seed (V915)'), c.created_at
  FROM evalos_case c
 WHERE c.id::text LIKE 'ca100000-%';
