-- A handful of experts per brand, so Unit 11's roster, availability board and Unit 08's
-- picker all have something to draw on a fresh dev database.
--
-- Every tag here is a legal FieldTag / LetterType value: V18's CHECKs would refuse this
-- file otherwise, which is exactly what those constraints are for — a mis-seeded row is
-- the case a Java enum cannot catch.
--
-- `payment_detail` is deliberately left NULL on every row. It is AES-GCM ciphertext
-- written only by PaymentDetailConverter, so a seed cannot produce a readable value, and
-- a fake one would fail to decrypt the first time a screen touched it. The roster shows
-- "no payment detail on file" for these experts, which is true.
--
-- The two case counters and total_payments_pending are left at their V7 defaults of 0.
-- Nothing reads them: load is derived from evalos_case by ExpertLoadService. Setting them
-- here would only make a dead column look alive.
INSERT INTO expert (
    id, brand_id, full_name, title, institution, email, phone,
    primary_fields, secondary_fields, letter_types,
    availability, tier, quality_score, standard_fee,
    agreement_status, payment_status, recruitment_source, date_onboarded, notes
) VALUES
    -- International Evaluations
    ('cccccccc-0000-0000-0000-000000000001',
     '11111111-1111-1111-1111-111111111111',
     'Dr Miriam Osei', 'Professor of Mechanical Engineering', 'Rowan State University',
     'm.osei@rowanstate.test', '+1-202-555-0111',
     ARRAY['MECHANICAL_ENGINEERING'], ARRAY['MATHEMATICS', 'PHYSICS'],
     ARRAY['EXPERT_OPINION_LETTER', 'RFE_RESPONSE'],
     'AVAILABLE', 'TIER_1', 9.2, 350.00,
     'SIGNED', 'UP_TO_DATE', 'Referral', DATE '2024-03-11',
     'Fast on RFEs. Prefers two weeks notice for a full opinion.'),

    ('cccccccc-0000-0000-0000-000000000002',
     '11111111-1111-1111-1111-111111111111',
     'Dr Alan Whitcombe', 'Associate Professor of Computer Science', 'Lakeside Institute of Technology',
     'a.whitcombe@lakeside.test', '+1-202-555-0112',
     ARRAY['COMPUTER_SCIENCE', 'DATA_SCIENCE'], ARRAY['INFORMATION_TECHNOLOGY'],
     ARRAY['EXPERT_OPINION_LETTER', 'PERM_LETTER'],
     'AT_CAPACITY', 'TIER_2', 8.0, 300.00,
     'SIGNED', 'UP_TO_DATE', 'Cold outreach', DATE '2024-09-02',
     NULL),

    ('cccccccc-0000-0000-0000-000000000003',
     '11111111-1111-1111-1111-111111111111',
     'Dr Sofia Marchetti', 'Clinical Professor of Nursing', 'St Brendan College of Health',
     's.marchetti@stbrendan.test', NULL,
     ARRAY['NURSING'], ARRAY['PUBLIC_HEALTH'],
     ARRAY['CREDENTIAL_EVALUATION'],
     'ON_LEAVE', 'TIER_2', 7.5, 275.00,
     'SIGNED', 'UP_TO_DATE', 'Conference', DATE '2025-01-20',
     'On sabbatical until the autumn.'),

    ('cccccccc-0000-0000-0000-000000000004',
     '11111111-1111-1111-1111-111111111111',
     'Professor Ada Nwankwo', 'Chair of Business Administration', 'Harborview Business School',
     'a.nwankwo@harborview.test', '+1-202-555-0114',
     ARRAY['BUSINESS_ADMINISTRATION', 'FINANCE'], ARRAY['ECONOMICS', 'ACCOUNTING'],
     ARRAY['EXPERT_OPINION_LETTER', 'CREDENTIAL_EVALUATION', 'PERM_LETTER'],
     'AVAILABLE', 'TIER_1', 9.6, 400.00,
     'SIGNED', 'UP_TO_DATE', 'Referral', DATE '2023-11-05',
     NULL),

    -- XpertsPortal
    ('cccccccc-0000-0000-0000-000000000005',
     '22222222-2222-2222-2222-222222222222',
     'Dr Petra Lindqvist', 'Professor of Civil Engineering', 'Northgate Polytechnic',
     'p.lindqvist@northgate.test', '+44-20-7946-0991',
     ARRAY['CIVIL_ENGINEERING'], ARRAY['ARCHITECTURE'],
     ARRAY['EXPERT_OPINION_LETTER', 'RFE_RESPONSE'],
     'AVAILABLE', 'TIER_1', 8.8, 320.00,
     'SIGNED', 'UP_TO_DATE', 'Referral', DATE '2024-06-18',
     NULL),

    ('cccccccc-0000-0000-0000-000000000006',
     '22222222-2222-2222-2222-222222222222',
     'Dr Hassan Rahimi', 'Reader in Law', 'Queen''s Chambers Faculty of Law',
     'h.rahimi@queenschambers.test', NULL,
     ARRAY['LAW'], ARRAY['HUMAN_RESOURCES'],
     ARRAY['CREDENTIAL_EVALUATION', 'TRANSLATION_CERTIFICATION'],
     'AVAILABLE', 'TIER_3', 7.0, 250.00,
     'SENT', 'PENDING', 'Cold outreach', DATE '2025-05-30',
     'Agreement still out for signature.');
