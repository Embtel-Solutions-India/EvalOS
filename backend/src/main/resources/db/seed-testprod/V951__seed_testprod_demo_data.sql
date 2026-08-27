-- Demo data for the test-production environment: a small, internally consistent
-- slice of the business, so the portal can be walked end to end on a database
-- nobody has used yet.
--
-- WHY THIS IS A SEPARATE FILE, and not more of V950. V950 has already been
-- applied, and an applied migration is never edited (invariant 9) — Flyway
-- checksums it, so the edit would fail the next migrate on every environment
-- that already ran it. V950 seeds the brands and the logins, which is what an
-- environment needs to boot; this one seeds what it needs to be *looked at*.
-- On a fresh database the two run back to back and the result is the same.
--
-- WHAT THIS DOES NOT DO. It does not stand in for the paths this environment
-- exists to exercise. Handoff A still creates real cases from the GHL webhook,
-- and Unit 11's sheet upload still upserts the roster on `lower(email)` — these
-- experts simply give it something to update. Nothing below is a credential:
-- no portal token is minted here (a `portal_access` row is only useful if the
-- token behind its hash is known, and a known token in this repository is a
-- live bearer credential), and `expert.payment_detail` stays NULL because it is
-- AES-GCM ciphertext that only PaymentDetailConverter can write.
--
-- Ids sit in their own ranges, so `DELETE FROM evalos_case WHERE id::text LIKE
-- '66666666-%'` clears the demo without touching a real case:
--   ffffffff… experts   55555555… contacts   66666666… cases
--   77777777… payouts   88888888… payments   99999999… expert offers
--
-- Times are relative (`now() - INTERVAL …`), so the data reads as current
-- whenever the environment is built rather than aging into a wall of overdue.
--
-- What it comes to: 5 experts, 7 contacts, 8 cases across every stage, 6 expert
-- offers (one still open, one declined and rematched), 2 payouts (one pending,
-- one settled by 1 payment), 6 notifications, and a checklist and timeline on
-- every case.

-- Five experts: three on International Evaluations, two on XpertsPortal. Both
-- brands get a roster, because one that exists on a single brand cannot show
-- that the other brand's is correctly empty of it.
--
-- `payment_detail` is NULL on every row and must stay that way: it is AES-GCM
-- ciphertext written only by PaymentDetailConverter, so a hand-written value
-- would fail to decrypt the first time a screen touched it. The roster reads
-- "no payment detail on file", which is true.
--
-- `total_cases_completed`, `current_active_count` and `total_payments_pending`
-- are left at their V7 defaults. Load is derived from `evalos_case` by
-- ExpertLoadService; setting them here would only make a dead column look alive.
INSERT INTO expert (
    id, brand_id, full_name, title, institution, email, phone,
    primary_fields, secondary_fields, letter_types,
    availability, tier, quality_score, standard_fee,
    agreement_status, payment_status, recruitment_source, date_onboarded, notes
) VALUES
    -- International Evaluations
    ('ffffffff-0000-0000-0000-000000000001', '33333333-3333-3333-3333-333333333333',
     'Dr Elena Vargas', 'Professor of Electrical Engineering', 'Cascade Technical University',
     'e.vargas@cascadetech.test', '+1-206-555-0177',
     ARRAY['ELECTRICAL_ENGINEERING'], ARRAY['COMPUTER_SCIENCE', 'PHYSICS'],
     ARRAY['EXPERT_OPINION_LETTER', 'RFE_RESPONSE'],
     'AVAILABLE', 'TIER_1', 9.4, 375.00,
     'SIGNED', 'PENDING', 'Referral', DATE '2024-04-08',
     'Two open cases. Turns an RFE around inside a week.'),

    ('ffffffff-0000-0000-0000-000000000002', '33333333-3333-3333-3333-333333333333',
     'Dr Samuel Adeyemi', 'Associate Professor of Computer Science', 'Meridian State University',
     's.adeyemi@meridianstate.test', '+1-312-555-0164',
     ARRAY['COMPUTER_SCIENCE', 'DATA_SCIENCE'], ARRAY['INFORMATION_TECHNOLOGY'],
     ARRAY['EXPERT_OPINION_LETTER', 'PERM_LETTER'],
     'AVAILABLE', 'TIER_2', 8.3, 300.00,
     'SIGNED', 'UP_TO_DATE', 'Cold outreach', DATE '2025-02-17',
     'Declines hardware-side opinions; says so quickly, which is worth more than saying yes.'),

    -- XpertsPortal
    ('ffffffff-0000-0000-0000-000000000003', '44444444-4444-4444-4444-444444444444',
     'Dr Ingrid Halvorsen', 'Professor of Nursing', 'Fjordview College of Health',
     'i.halvorsen@fjordview.test', '+44-20-7946-0812',
     ARRAY['NURSING'], ARRAY['PUBLIC_HEALTH'],
     ARRAY['CREDENTIAL_EVALUATION', 'EXPERT_OPINION_LETTER'],
     'AVAILABLE', 'TIER_1', 9.0, 340.00,
     'SIGNED', 'UP_TO_DATE', 'Conference', DATE '2024-10-22',
     NULL),

    ('ffffffff-0000-0000-0000-000000000004', '44444444-4444-4444-4444-444444444444',
     'Dr Rafael Costa', 'Reader in Finance', 'Anchorline Business School',
     'r.costa@anchorline.test', NULL,
     ARRAY['FINANCE', 'ACCOUNTING'], ARRAY['ECONOMICS'],
     ARRAY['CREDENTIAL_EVALUATION', 'EXPERT_OPINION_LETTER'],
     'AVAILABLE', 'TIER_2', 7.9, 290.00,
     'SIGNED', 'UP_TO_DATE', 'Referral', DATE '2025-06-03',
     NULL),

    -- One expert with an unusable state, on purpose: the roster filters and the
    -- Unit 12 matcher both have to exclude somebody, and a roster where every
    -- row is offerable never proves they do.
    ('ffffffff-0000-0000-0000-000000000005', '33333333-3333-3333-3333-333333333333',
     'Dr Wilhelmina Croft', 'Clinical Professor of Pharmacy', 'Harlow School of Pharmacy',
     'w.croft@harlowpharm.test', NULL,
     ARRAY['PHARMACY'], ARRAY['MEDICINE'],
     ARRAY['CREDENTIAL_EVALUATION'],
     'ON_LEAVE', 'TIER_3', 7.2, 260.00,
     'EXPIRED', 'UP_TO_DATE', 'Cold outreach', DATE '2023-08-14',
     'On sabbatical; agreement lapsed while she was away.');

-- The contact snapshots the cases hang off. A snapshot is GHL's record, copied;
-- nothing in EvalOS edits one (invariant 7), so `synced_at` is the only field
-- that moves and it moves wholesale. `ghl_contact_id` is the real identity —
-- V27 demoted email to a fallback — so every demo contact carries one, which is
-- also what stops a redelivered webhook from duplicating them.
INSERT INTO contact_snapshot (
    id, brand_id, ghl_contact_id, full_name, email, phone, company, client_type,
    source_channel, utm_source, utm_medium, utm_campaign, date_first_captured, synced_at
) VALUES
    -- International Evaluations
    ('55555555-0000-0000-0000-000000000001', '33333333-3333-3333-3333-333333333333',
     'ghl-tp-ie-0001', 'Anjali Rao', 'anjali.rao@northwind.test', '+1-415-555-0142', NULL,
     'INDIVIDUAL', 'WEBSITE', 'google', 'organic', NULL,
     now() - INTERVAL '46 days', now() - INTERVAL '2 days'),

    ('55555555-0000-0000-0000-000000000002', '33333333-3333-3333-3333-333333333333',
     'ghl-tp-ie-0002', 'Marcus Feld', 'marcus.feld@feldlawgroup.test', '+1-212-555-0198',
     'Feld Law Group', 'ATTORNEY', 'REFERRAL', NULL, NULL, NULL,
     now() - INTERVAL '30 days', now() - INTERVAL '9 days'),

    ('55555555-0000-0000-0000-000000000003', '33333333-3333-3333-3333-333333333333',
     'ghl-tp-ie-0003', 'Dana Whitfield', 'dana.whitfield@lumenworks.test', '+1-503-555-0121',
     'Lumen Works Inc', 'EMPLOYER', 'GOOGLE_ADS', 'google', 'cpc', 'ie-eb2-niw-2026',
     now() - INTERVAL '21 days', now() - INTERVAL '4 days'),

    ('55555555-0000-0000-0000-000000000004', '33333333-3333-3333-3333-333333333333',
     'ghl-tp-ie-0004', 'Tomas Herrera', 'tomas.herrera@bridgeport.test', '+1-786-555-0155', NULL,
     'INDIVIDUAL', 'LINKEDIN', 'linkedin', 'social', 'ie-rfe-h1b-2026',
     now() - INTERVAL '25 days', now() - INTERVAL '3 days'),

    ('55555555-0000-0000-0000-000000000007', '33333333-3333-3333-3333-333333333333',
     'ghl-tp-ie-0007', 'Rahul Deshmukh', 'rahul.deshmukh@vertexrobotics.test', '+1-408-555-0136',
     NULL, 'INDIVIDUAL', 'REFERRAL', NULL, NULL, NULL,
     now() - INTERVAL '9 days', now() - INTERVAL '1 day'),

    -- XpertsPortal
    ('55555555-0000-0000-0000-000000000005', '44444444-4444-4444-4444-444444444444',
     'ghl-tp-xp-0005', 'Nadia Farouk', 'nadia.farouk@meridianclinic.test', '+44-161-555-0173', NULL,
     'INDIVIDUAL', 'WEBSITE', 'direct', 'none', NULL,
     now() - INTERVAL '14 days', now() - INTERVAL '2 days'),

    ('55555555-0000-0000-0000-000000000006', '44444444-4444-4444-4444-444444444444',
     'ghl-tp-xp-0006', 'Oliver Brandt', 'oliver.brandt@brandtpartners.test', '+44-20-7946-0288',
     'Brandt & Partners', 'ATTORNEY', 'PARTNER', NULL, NULL, NULL,
     now() - INTERVAL '8 days', now() - INTERVAL '3 days');

-- Eight cases, one per interesting position in the lifecycle, so that every
-- screen has a row that belongs on it and no screen is empty:
--
--   1  DOC_COLLECTION  IN_POOL   unclaimed  -- the pool, and Assign PM
--   2  DOC_COLLECTION  ASSIGNED  on hold    -- the exception banner, the chase
--   3  DRAFT_GENERATION          v2, PM approval pending -- draft review
--   4  FINAL_DELIVERY            delivered  -- an unsettled payout this week
--   5  CLOSED                    delivered and settled -- the payment detail
--   6  EXPERT_SIGNING  (XP)      awaiting the signature
--   7  DOC_COLLECTION  (XP)      freshly assigned
--   8  EXPERT_ASSIGNMENT         docs in, one offer out and unanswered
--
-- Nothing is delivered on XpertsPortal, so its payouts week is empty. That is a
-- true state rather than a hole: no delivery, no money owed. International
-- Evaluations carries the payout rows.
--
-- `paid` is true on all of them, and that is not laziness: Handoff A fires on
-- opportunity Won, which GHL only marks after it has invoiced and collected, so
-- a case that exists is a case that was paid for. The state machine refuses to
-- send an unpaid case to an expert, which is why an unpaid demo case could not
-- have reached five of these seven stages anyway.
--
-- `team_id` is set on every claimed case and NULL on the one still in the pool:
-- a TEAM-tier read that matched a case nobody has picked up would be wrong.
INSERT INTO evalos_case (
    id, brand_id, team_id, case_code, pool_status, assigned_pm, assigned_cm, assigned_coordinator,
    contact_id, service_type, service_subtype, visa_category, client_type, deal_value, deadline,
    current_stage, exception_state, stage_entered_at, sla_status, pm_strategy_notes,
    expert_id, expert_sign_status, draft_version_count, pm_approval_status, client_approval_status,
    client_portal_read_at, drive_link, draft_link, invoice_ref, campaign_attribution,
    delivery_date, case_closed_date, google_review_requested, google_review_requested_at,
    ghl_opportunity_id, paid, paid_at, created_at
) VALUES
    -- 1. In the pool. No PM, no team, no strategy notes yet: this is what a case
    --    looks like ten minutes after the webhook lands.
    ('66666666-0000-0000-0000-000000000001', '33333333-3333-3333-3333-333333333333', NULL,
     'IE-2026-7A31C4', 'IN_POOL', NULL, NULL, NULL,
     '55555555-0000-0000-0000-000000000001', 'CREDENTIAL_EVALUATION', 'COURSE_BY_COURSE', 'OTHER',
     'INDIVIDUAL', 295.00, now() + INTERVAL '12 days',
     'DOC_COLLECTION', 'NONE', now() - INTERVAL '2 days', 'ON_TRACK', NULL,
     NULL, NULL, 0, NULL, NULL,
     NULL, NULL, NULL, 'INV-IE-20418', NULL,
     NULL, NULL, false, NULL,
     'ghl-opp-tp-ie-0001', true, now() - INTERVAL '2 days', now() - INTERVAL '2 days'),

    -- 2. Held awaiting the client. The exception is the whole point of the row:
    --    every action except RESUME_FROM_HOLD, ASSIGN_* and REQUEST_REFUND is
    --    refused while it stands, so this is the case that proves the guard.
    ('66666666-0000-0000-0000-000000000002', '33333333-3333-3333-3333-333333333333',
     'dddddddd-0000-0000-0000-000000000001',
     'IE-2026-B0E2F9', 'ASSIGNED', 'eeeeeeee-0000-0000-0000-000000000003', NULL,
     'eeeeeeee-0000-0000-0000-000000000004',
     '55555555-0000-0000-0000-000000000002', 'EXPERT_OPINION_LETTER', NULL, 'H1B',
     'ATTORNEY', 1200.00, now() + INTERVAL '6 days',
     'DOC_COLLECTION', 'ON_HOLD_AWAITING_CLIENT', now() - INTERVAL '9 days', 'AT_RISK',
     'Attorney is collecting the employment letters. Chased twice; hold until they land.',
     NULL, NULL, 0, NULL, NULL,
     NULL, 'https://drive.google.com/drive/folders/tp-ie-b0e2f9', NULL, 'INV-IE-20402', NULL,
     NULL, NULL, false, NULL,
     'ghl-opp-tp-ie-0002', true, now() - INTERVAL '9 days', now() - INTERVAL '9 days'),

    -- 3. Draft v2 sitting with the PM. The first expert declined and was
    --    rematched, which is why the offer table below holds two rows for it and
    --    the exception state is back to NONE.
    ('66666666-0000-0000-0000-000000000003', '33333333-3333-3333-3333-333333333333',
     'dddddddd-0000-0000-0000-000000000001',
     'IE-2026-4C8D17', 'ASSIGNED', 'eeeeeeee-0000-0000-0000-000000000003',
     'eeeeeeee-0000-0000-0000-000000000005', 'eeeeeeee-0000-0000-0000-000000000004',
     '55555555-0000-0000-0000-000000000003', 'EXPERT_OPINION_LETTER', NULL, 'EB2_NIW',
     'EMPLOYER', 1450.00, now() + INTERVAL '5 days',
     'DRAFT_GENERATION', 'NONE', now() - INTERVAL '4 days', 'ON_TRACK',
     'Lead on the national-interest argument; the employer wants the hardware work foregrounded.',
     'ffffffff-0000-0000-0000-000000000001', NULL, 2, 'PENDING', NULL,
     NULL, 'https://drive.google.com/drive/folders/tp-ie-4c8d17',
     'https://docs.google.com/document/d/tp-draft-4c8d17-v2', 'INV-IE-20391', 'ie-eb2-niw-2026',
     NULL, NULL, false, NULL,
     'ghl-opp-tp-ie-0003', true, now() - INTERVAL '14 days', now() - INTERVAL '14 days'),

    -- 4. Delivered, not yet confirmed by the client. Delivery is what opened
    --    payout 7777...0001, the unsettled row on the payouts week.
    ('66666666-0000-0000-0000-000000000004', '33333333-3333-3333-3333-333333333333',
     'dddddddd-0000-0000-0000-000000000001',
     'IE-2026-D91A05', 'ASSIGNED', 'eeeeeeee-0000-0000-0000-000000000003',
     'eeeeeeee-0000-0000-0000-000000000005', 'eeeeeeee-0000-0000-0000-000000000004',
     '55555555-0000-0000-0000-000000000004', 'RFE_RESPONSE', NULL, 'H1B',
     'INDIVIDUAL', 950.00, now() + INTERVAL '2 days',
     'FINAL_DELIVERY', 'NONE', now() - INTERVAL '3 days', 'ON_TRACK',
     'Specialty-occupation RFE. Vargas has the degree-equivalence argument from the first filing.',
     'ffffffff-0000-0000-0000-000000000001', 'SIGNED', 3, 'APPROVED', 'APPROVED',
     now() - INTERVAL '5 days', 'https://drive.google.com/drive/folders/tp-ie-d91a05',
     'https://docs.google.com/document/d/tp-draft-d91a05-v3', 'INV-IE-20377', 'ie-rfe-h1b-2026',
     now() - INTERVAL '3 days', NULL, false, NULL,
     'ghl-opp-tp-ie-0004', true, now() - INTERVAL '21 days', now() - INTERVAL '21 days'),

    -- 5. Closed and settled, and the same contact as case 1: a client who comes
    --    back is normal business, and it is what V15's partial index has to
    --    allow. The review request is recorded; the 30-day retention touch is
    --    not, so the retention timers still have something to do.
    ('66666666-0000-0000-0000-000000000005', '33333333-3333-3333-3333-333333333333',
     'dddddddd-0000-0000-0000-000000000001',
     'IE-2026-2F60BB', 'ASSIGNED', 'eeeeeeee-0000-0000-0000-000000000003',
     'eeeeeeee-0000-0000-0000-000000000005', 'eeeeeeee-0000-0000-0000-000000000004',
     '55555555-0000-0000-0000-000000000001', 'EXPERT_OPINION_LETTER', NULL, 'O1',
     'INDIVIDUAL', 1100.00, now() - INTERVAL '15 days',
     'CLOSED', 'NONE', now() - INTERVAL '10 days', 'ON_TRACK',
     'Extraordinary-ability letter for a research engineer. Clean file, no RFE expected.',
     'ffffffff-0000-0000-0000-000000000002', 'SIGNED', 2, 'APPROVED', 'APPROVED',
     now() - INTERVAL '13 days', 'https://drive.google.com/drive/folders/tp-ie-2f60bb',
     'https://docs.google.com/document/d/tp-draft-2f60bb-v2', 'INV-IE-20344', NULL,
     now() - INTERVAL '12 days', now() - INTERVAL '10 days', true, now() - INTERVAL '10 days',
     'ghl-opp-tp-ie-0005', true, now() - INTERVAL '40 days', now() - INTERVAL '40 days'),

    -- 6. XpertsPortal, waiting on a signature. Client approved, expert has not
    --    signed: the one state where the case is blocked on somebody outside the
    --    building and a portal link is the thing that unblocks it.
    ('66666666-0000-0000-0000-000000000006', '44444444-4444-4444-4444-444444444444',
     'dddddddd-0000-0000-0000-000000000002',
     'XP-2026-8E4470', 'ASSIGNED', 'eeeeeeee-0000-0000-0000-000000000008',
     'eeeeeeee-0000-0000-0000-00000000000a', 'eeeeeeee-0000-0000-0000-000000000009',
     '55555555-0000-0000-0000-000000000005', 'CREDENTIAL_EVALUATION', 'EDUCATION_PLUS_EXPERIENCE',
     'OTHER', 'INDIVIDUAL', 420.00, now() + INTERVAL '7 days',
     'EXPERT_SIGNING', 'NONE', now() - INTERVAL '2 days', 'ON_TRACK',
     'UK nursing degree plus six years of ward experience. Halvorsen has signed this shape before.',
     'ffffffff-0000-0000-0000-000000000003', 'PENDING', 1, 'APPROVED', 'APPROVED',
     now() - INTERVAL '3 days', 'https://drive.google.com/drive/folders/tp-xp-8e4470',
     'https://docs.google.com/document/d/tp-draft-8e4470-v1', 'INV-XP-10188', NULL,
     NULL, NULL, false, NULL,
     'ghl-opp-tp-xp-0006', true, now() - INTERVAL '11 days', now() - INTERVAL '11 days'),

    -- 7. XpertsPortal, just claimed out of the pool. Its documents are still all
    --    REQUIRED, which is what the coordinator's queue looks like.
    ('66666666-0000-0000-0000-000000000007', '44444444-4444-4444-4444-444444444444',
     'dddddddd-0000-0000-0000-000000000002',
     'XP-2026-1D9C6E', 'ASSIGNED', 'eeeeeeee-0000-0000-0000-000000000008', NULL,
     'eeeeeeee-0000-0000-0000-000000000009',
     '55555555-0000-0000-0000-000000000006', 'EXPERT_OPINION_LETTER', NULL, 'EB1A',
     'ATTORNEY', 1350.00, now() + INTERVAL '15 days',
     'DOC_COLLECTION', 'NONE', now() - INTERVAL '3 days', 'ON_TRACK', NULL,
     NULL, NULL, 0, NULL, NULL,
     NULL, NULL, NULL, 'INV-XP-10195', NULL,
     NULL, NULL, false, NULL,
     'ghl-opp-tp-xp-0007', true, now() - INTERVAL '3 days', now() - INTERVAL '3 days'),

    -- 8. Documents are in and an offer is out with nobody having answered it.
    --    This is the only case whose expert_case_offer row is still OFFERED, so
    --    it is the one the matcher and the awaiting-response view have to find.
    ('66666666-0000-0000-0000-000000000008', '33333333-3333-3333-3333-333333333333',
     'dddddddd-0000-0000-0000-000000000001',
     'IE-2026-63BA48', 'ASSIGNED', 'eeeeeeee-0000-0000-0000-000000000003', NULL,
     'eeeeeeee-0000-0000-0000-000000000004',
     '55555555-0000-0000-0000-000000000007', 'EXPERT_OPINION_LETTER', NULL, 'EB1A',
     'INDIVIDUAL', 1250.00, now() + INTERVAL '10 days',
     'EXPERT_ASSIGNMENT', 'NONE', now() - INTERVAL '1 day', 'ON_TRACK',
     'Robotics research lead. Offered to Adeyemi first; Vargas is the fallback if he passes.',
     NULL, NULL, 0, NULL, NULL,
     NULL, 'https://drive.google.com/drive/folders/tp-ie-63ba48', NULL, 'INV-IE-20411', NULL,
     NULL, NULL, false, NULL,
     'ghl-opp-tp-ie-0008', true, now() - INTERVAL '8 days', now() - INTERVAL '8 days');

-- The checklists, generated rather than typed out. The labels are the ones
-- ChecklistTemplates.forService would have opened at intake, so a demo case
-- carries exactly the documents a real one does; only the three service types
-- the demo cases actually use are listed, because a template row nothing joins
-- to is a copy of Java that can drift unnoticed.
--
-- The statuses are the story each case is telling: nothing collected yet in the
-- pool, a stalled file on the held case, a start made on the fresh one, and
-- everything approved once a case has left DOC_COLLECTION — which it cannot do
-- with a document outstanding, since markDocsComplete refuses.
--
-- Scoped to the demo ids so that re-running any part of this against an
-- environment which has since taken real cases through Handoff A cannot reach
-- them.
INSERT INTO document_checklist_item (brand_id, case_id, label, status, updated_at)
SELECT c.brand_id, c.id, t.label,
       CASE
           WHEN c.current_stage <> 'DOC_COLLECTION'                THEN 'APPROVED'
           WHEN c.pool_status = 'IN_POOL'                          THEN 'REQUIRED'
           WHEN c.exception_state = 'ON_HOLD_AWAITING_CLIENT' THEN
                CASE WHEN t.ord <= 2 THEN 'APPROVED'
                     WHEN t.ord = 3  THEN 'UPLOADED'
                     ELSE 'MISSING' END
           WHEN t.ord = 1                                          THEN 'APPROVED'
           WHEN t.ord = 2                                          THEN 'UPLOADED'
           ELSE 'REQUIRED'
       END,
       c.stage_entered_at
FROM evalos_case c
JOIN (VALUES
    ('CREDENTIAL_EVALUATION'::text, ARRAY[
        'Passport or government photo ID',
        'Degree certificate or diploma',
        'Official transcripts / mark sheets',
        'Certified English translation of any non-English document']::text[]),
    ('EXPERT_OPINION_LETTER', ARRAY[
        'Passport or government photo ID',
        'Degree certificate or diploma',
        'Official transcripts / mark sheets',
        'CV or résumé',
        'Job offer letter or position description',
        'Employment verification letters']::text[]),
    ('RFE_RESPONSE', ARRAY[
        'Passport or government photo ID',
        'Degree certificate or diploma',
        'Official transcripts / mark sheets',
        'The RFE notice as issued',
        'Copy of the original petition']::text[])
) AS tpl (service_type, labels) ON tpl.service_type = c.service_type
CROSS JOIN LATERAL unnest(tpl.labels) WITH ORDINALITY AS t (label, ord)
WHERE c.id::text LIKE '66666666-%';

-- The offer history behind the assignments above. This is what the expert's
-- acceptance rate is computed from, so the declines matter as much as the
-- accepts — a roster where everyone accepted everything scores every expert
-- identically and proves nothing.
--
-- Case 3 holds the interesting pair: Adeyemi declined it, Vargas took it, and
-- the case's expert_id is Vargas. Case 8's row is the only OFFERED one, which
-- is what `idx_expert_case_offer_open` exists to find and what V19's
-- outcome_dated CHECK requires to have no outcome_at.
INSERT INTO expert_case_offer (id, brand_id, case_id, expert_id, offered_at, outcome, outcome_at, decline_reason) VALUES
    ('99999999-0000-0000-0000-000000000001', '33333333-3333-3333-3333-333333333333',
     '66666666-0000-0000-0000-000000000003', 'ffffffff-0000-0000-0000-000000000002',
     now() - INTERVAL '6 days', 'DECLINED', now() - INTERVAL '5 days',
     'Hardware-side argument; outside what I can sign to.'),

    ('99999999-0000-0000-0000-000000000002', '33333333-3333-3333-3333-333333333333',
     '66666666-0000-0000-0000-000000000003', 'ffffffff-0000-0000-0000-000000000001',
     now() - INTERVAL '5 days', 'ACCEPTED', now() - INTERVAL '5 days', NULL),

    ('99999999-0000-0000-0000-000000000003', '33333333-3333-3333-3333-333333333333',
     '66666666-0000-0000-0000-000000000004', 'ffffffff-0000-0000-0000-000000000001',
     now() - INTERVAL '18 days', 'ACCEPTED', now() - INTERVAL '18 days', NULL),

    ('99999999-0000-0000-0000-000000000004', '33333333-3333-3333-3333-333333333333',
     '66666666-0000-0000-0000-000000000005', 'ffffffff-0000-0000-0000-000000000002',
     now() - INTERVAL '35 days', 'ACCEPTED', now() - INTERVAL '34 days', NULL),

    ('99999999-0000-0000-0000-000000000005', '44444444-4444-4444-4444-444444444444',
     '66666666-0000-0000-0000-000000000006', 'ffffffff-0000-0000-0000-000000000003',
     now() - INTERVAL '9 days', 'ACCEPTED', now() - INTERVAL '9 days', NULL),

    ('99999999-0000-0000-0000-000000000006', '33333333-3333-3333-3333-333333333333',
     '66666666-0000-0000-0000-000000000008', 'ffffffff-0000-0000-0000-000000000002',
     now() - INTERVAL '1 day', 'OFFERED', NULL, NULL);

-- Two payouts, which is one per delivered case and no more: `uq_payout_per_case`
-- allows exactly one non-VOIDED row per case, and delivery is the only thing
-- that opens one. Amounts are each expert's standard fee, which is what
-- openForDelivery prefills, and the due date is delivery + the brand's
-- payout_term_days (7).
--
-- One is PENDING and one is PAID on purpose: the payouts week needs a row that
-- can still be settled and a row that already was, or neither the settle dialog
-- nor the payment detail screen has anything to open.
INSERT INTO payout_ledger (id, brand_id, case_id, expert_id, amount, currency, status, due_date, recorded_by, payment_id) VALUES
    ('77777777-0000-0000-0000-000000000001', '33333333-3333-3333-3333-333333333333',
     '66666666-0000-0000-0000-000000000004', 'ffffffff-0000-0000-0000-000000000001',
     375.00, 'USD', 'PENDING', now() + INTERVAL '4 days', NULL, NULL),

    ('77777777-0000-0000-0000-000000000002', '33333333-3333-3333-3333-333333333333',
     '66666666-0000-0000-0000-000000000005', 'ffffffff-0000-0000-0000-000000000002',
     300.00, 'USD', 'PAID', now() - INTERVAL '5 days',
     'eeeeeeee-0000-0000-0000-000000000006', '88888888-0000-0000-0000-000000000001');

-- The transfer that settled it. `amount` equals the one draft it covers, which
-- is the invariant settle() enforces and the reason a hand-written payment has
-- to be kept in step with the ledger rows above.
--
-- `confirmed_at` is left NULL so the confirmation step is still available to
-- click: a payment that arrives already confirmed hides half the screen.
INSERT INTO payout_payment (id, brand_id, expert_id, amount, currency, method, reference, paid_date, notes, confirmed_at, recorded_by) VALUES
    ('88888888-0000-0000-0000-000000000001', '33333333-3333-3333-3333-333333333333',
     'ffffffff-0000-0000-0000-000000000002', 300.00, 'USD', 'Wise transfer', 'WISE-TP-40118',
     now() - INTERVAL '9 days', 'Single draft; weekly batch was one row that week.', NULL,
     'eeeeeeee-0000-0000-0000-000000000006');

-- A few notifications, mostly unread, so the nav badge has a number and the
-- list has something under it. Recipients are the people who would actually
-- have been told: the pool alert goes to the PM, the assignment to the CM.
INSERT INTO notification (brand_id, recipient_id, type, case_id, body, read, created_at) VALUES
    ('33333333-3333-3333-3333-333333333333', 'eeeeeeee-0000-0000-0000-000000000003',
     'NEW_CASE_IN_POOL', '66666666-0000-0000-0000-000000000001',
     'IE-2026-7A31C4 is paid and waiting in the pool.', false, now() - INTERVAL '2 days'),

    ('33333333-3333-3333-3333-333333333333', 'eeeeeeee-0000-0000-0000-000000000003',
     'EXCEPTION_RAISED', '66666666-0000-0000-0000-000000000002',
     'IE-2026-B0E2F9 is on hold awaiting the client.', false, now() - INTERVAL '4 days'),

    ('33333333-3333-3333-3333-333333333333', 'eeeeeeee-0000-0000-0000-000000000003',
     'SLA_AT_RISK', '66666666-0000-0000-0000-000000000002',
     'IE-2026-B0E2F9 is at risk of missing its deadline.', false, now() - INTERVAL '1 day'),

    ('33333333-3333-3333-3333-333333333333', 'eeeeeeee-0000-0000-0000-000000000005',
     'CASE_ASSIGNED', '66666666-0000-0000-0000-000000000003',
     'You are the case manager on IE-2026-4C8D17.', true, now() - INTERVAL '4 days'),

    ('44444444-4444-4444-4444-444444444444', 'eeeeeeee-0000-0000-0000-000000000008',
     'STAGE_CHANGED', '66666666-0000-0000-0000-000000000006',
     'XP-2026-8E4470 moved to expert signing.', false, now() - INTERVAL '2 days'),

    ('44444444-4444-4444-4444-444444444444', 'eeeeeeee-0000-0000-0000-000000000009',
     'CASE_ASSIGNED', '66666666-0000-0000-0000-000000000007',
     'You are the coordinator on XP-2026-1D9C6E.', false, now() - INTERVAL '3 days');

-- The timeline. A case detail with an empty history reads as broken, and the
-- timeline is built from audit_event, so the demo cases need audit rows or half
-- the screen is blank.
--
-- Generated per case rather than written out, because the shape is the same for
-- all of them: opened by the system, then whatever the case has since become.
-- CaseTimelineService reads `stage`, `exceptionState` and `note` out of
-- after_snapshot and tolerates the rest being absent, so those three are all
-- that is written.
--
-- These rows are append-only like every other audit row (V10 has a trigger that
-- refuses UPDATE and DELETE), which means a mistake here is corrected by a new
-- row, never by editing one.
INSERT INTO audit_event (brand_id, object_type, object_id, action, actor_id, actor_type, after_snapshot, created_at)
SELECT c.brand_id, 'CASE', c.id, 'CREATED', NULL, 'SYSTEM',
       jsonb_build_object('stage', 'DOC_COLLECTION', 'exceptionState', 'NONE',
                          'note', 'Opened from the GHL contact.created handoff.'),
       c.created_at
FROM evalos_case c
WHERE c.id::text LIKE '66666666-%';

INSERT INTO audit_event (brand_id, object_type, object_id, action, actor_id, actor_type, after_snapshot, created_at)
SELECT c.brand_id, 'CASE', c.id, 'ASSIGNED', c.assigned_pm, 'STAFF',
       jsonb_build_object('stage', c.current_stage, 'exceptionState', 'NONE',
                          'note', 'Claimed out of the pool.'),
       c.created_at + INTERVAL '2 hours'
FROM evalos_case c
WHERE c.id::text LIKE '66666666-%' AND c.assigned_pm IS NOT NULL;

INSERT INTO audit_event (brand_id, object_type, object_id, action, actor_id, actor_type, after_snapshot, created_at)
SELECT c.brand_id, 'CASE', c.id, 'STAGE_CHANGED', c.assigned_pm, 'STAFF',
       jsonb_build_object('stage', c.current_stage, 'exceptionState', c.exception_state,
                          'note', 'Moved to ' || c.current_stage || '.'),
       c.stage_entered_at
FROM evalos_case c
WHERE c.id::text LIKE '66666666-%' AND c.current_stage <> 'DOC_COLLECTION';

INSERT INTO audit_event (brand_id, object_type, object_id, action, actor_id, actor_type, after_snapshot, created_at)
SELECT c.brand_id, 'CASE', c.id, 'UPDATED', c.assigned_pm, 'STAFF',
       jsonb_build_object('stage', c.current_stage, 'exceptionState', c.exception_state,
                          'note', 'Held: waiting on the client for the outstanding documents.'),
       c.stage_entered_at + INTERVAL '1 day'
FROM evalos_case c
WHERE c.id::text LIKE '66666666-%' AND c.exception_state <> 'NONE';
