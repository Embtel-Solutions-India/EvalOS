-- Unit 33: the record EvalOS holds becomes the record the business holds.
--
-- Three columns on the case and nineteen on the expert, plus the taxonomy widening
-- the expert roster has needed since V18 shipped an unsigned-off starter list.
--
-- **No `expert_credentials` side table.** The relationship is 1:1, there is no history
-- to keep and no second implementation — a child table here would be a join added for
-- symmetry, and every read of the profile would pay for it.

-- ---------------------------------------------------------------------------
-- The case
-- ---------------------------------------------------------------------------

ALTER TABLE evalos_case
    -- The beneficiary the letter is about. NOT on contact_snapshot: invariant 7 makes
    -- that a read-only copy of a GHL record, and Handoff A's confirmed payload is a
    -- contact and carries no beneficiary. Typed by the Case Manager, prefilled from the
    -- contact only when client_type = 'INDIVIDUAL', where they are the same person.
    ADD COLUMN applicant_name     text,
    -- A FieldTag name. Written by the match action and by nothing else — never by the
    -- gateway. Unit 12 refused this column because the only source would have been an
    -- intake webhook that carries no discipline; the source now is the tag the PM types
    -- at match time, which was being discarded. NULL means no match has been run.
    ADD COLUMN field_of_expertise text,
    -- The filing deadline USCIS imposed, which is not `deadline` — that is what EvalOS
    -- promised. They coincide on most cases and diverge on the ones that matter. Feeds
    -- nothing automatically: the Case Manager sets `deadline` from it, and a rule that
    -- did so silently would be a rule nobody could see was wrong.
    ADD COLUMN rfe_date           date;

-- ---------------------------------------------------------------------------
-- The expert dossier
-- ---------------------------------------------------------------------------

ALTER TABLE expert
    -- The roster's human id (IE-EXP-###). This is the join key between the case sheet's
    -- `evaluator_id` and the expert sheet, so an import cannot link the two without it.
    ADD COLUMN expert_code           text,
    -- The niche ("Power Systems & Smart Grids") — the real matching signal, and
    -- deliberately free text. `secondary_fields` stays the closed vocabulary; a niche is
    -- exactly where a closed vocabulary is wrong on its first unseen value.
    ADD COLUMN sub_specialization    text,
    -- Terminal degree and where it came from. `degree_institution` is not `institution`:
    -- one is where they studied, the other where they work now.
    ADD COLUMN highest_degree        text,
    ADD COLUMN degree_field          text,
    ADD COLUMN degree_institution    text,
    ADD COLUMN current_position      text,
    -- Values from the AffiliationType enum. Closed, because the ENM filters on it.
    ADD COLUMN affiliation_type      text,
    -- US-based is preferred for USCIS letters, so location is a filter, not a footnote.
    ADD COLUMN country               text,
    ADD COLUMN state_region          text,
    ADD COLUMN years_experience      int,
    ADD COLUMN linkedin_url          text,
    -- VisaCategory names. NOT the same fact as `letter_types`: that is the deliverable
    -- this expert will sign, this is the petition the deliverable supports.
    ADD COLUMN visa_categories       text[],
    -- The standing metrics an expert opinion letter actually rests on.
    ADD COLUMN publications          int,
    ADD COLUMN citations             int,
    ADD COLUMN h_index               int,
    ADD COLUMN patents               int,
    -- Narrative and comma-separated, as the roster sheet has them. Free text rather than
    -- text[]: nothing queries inside these, and four GIN indexes to render a detail view
    -- is cost with no reader.
    ADD COLUMN notable_awards        text,
    ADD COLUMN professional_memberships text,
    ADD COLUMN editorial_roles       text,
    ADD COLUMN languages             text,
    -- Can take a 48-hour rush.
    ADD COLUMN rush_available        boolean NOT NULL DEFAULT false,
    -- Typical days to complete a letter. Distinct from `avg_response_hours`, which is
    -- how fast they answer an offer — a fast replier can be a slow writer.
    ADD COLUMN avg_turnaround_days   int;

-- Same shape as V18's email key: brand-scoped, and only over rows that have one, so a
-- roster half-migrated from a sheet without codes still saves.
CREATE UNIQUE INDEX uq_expert_per_brand_code
    ON expert (brand_id, expert_code)
    WHERE expert_code IS NOT NULL;

-- `last_active_date` from the sheet is deliberately NOT a column: it is
-- max(offered_at) over expert_case_offer, which is the same fact with no writer to
-- forget. Nor is there a payment column — ExpertImportService refuses `paymentDetail`
-- as a mapping target, and this migration widens that import surface.

-- ---------------------------------------------------------------------------
-- The taxonomy V18 shipped without sign-off
-- ---------------------------------------------------------------------------
--
-- V18's list was drawn for CREDENTIAL-EVALUATION degree fields. The roster is an
-- EXPERT-OPINION-LETTER roster, and ten of the twenty-two disciplines a real sheet
-- carries could not be spelled at all — an expert whose field has no tag scores zero
-- on Unit 12's 40-point field factor, which is a silent wrong answer rather than an
-- error. V18 says widening is a new migration that widens the CHECK, never an edit to
-- V18 (invariant 9). This is that migration.
--
-- Applied Mathematics stays MATHEMATICS and Clinical Medicine stays MEDICINE — those
-- are the same discipline named differently. PHARMACOLOGY is added beside PHARMACY
-- because a science and a practice are not.

ALTER TABLE expert
    DROP CONSTRAINT expert_primary_fields_known,
    DROP CONSTRAINT expert_secondary_fields_known,
    DROP CONSTRAINT expert_letter_types_known,
    ADD CONSTRAINT expert_primary_fields_known CHECK (primary_fields <@ ARRAY[
        'MECHANICAL_ENGINEERING', 'ELECTRICAL_ENGINEERING', 'CIVIL_ENGINEERING',
        'CHEMICAL_ENGINEERING', 'COMPUTER_SCIENCE', 'INFORMATION_TECHNOLOGY',
        'DATA_SCIENCE', 'BUSINESS_ADMINISTRATION', 'FINANCE', 'ACCOUNTING',
        'MARKETING', 'ECONOMICS', 'NURSING', 'MEDICINE', 'PHARMACY',
        'PUBLIC_HEALTH', 'EDUCATION', 'LAW', 'ARCHITECTURE', 'BIOLOGY',
        'CHEMISTRY', 'PHYSICS', 'MATHEMATICS', 'PSYCHOLOGY', 'FINE_ARTS',
        'HOSPITALITY_MANAGEMENT', 'SUPPLY_CHAIN', 'HUMAN_RESOURCES',
        'AEROSPACE_ENGINEERING', 'ARTIFICIAL_INTELLIGENCE', 'BIOMEDICAL_ENGINEERING',
        'BIOTECHNOLOGY', 'CYBERSECURITY', 'ENVIRONMENTAL_ENGINEERING',
        'MATERIALS_SCIENCE', 'NEUROSCIENCE', 'PHARMACOLOGY',
        'RENEWABLE_ENERGY_ENGINEERING', 'SOFTWARE_ENGINEERING'
    ]::text[]),
    ADD CONSTRAINT expert_secondary_fields_known CHECK (secondary_fields <@ ARRAY[
        'MECHANICAL_ENGINEERING', 'ELECTRICAL_ENGINEERING', 'CIVIL_ENGINEERING',
        'CHEMICAL_ENGINEERING', 'COMPUTER_SCIENCE', 'INFORMATION_TECHNOLOGY',
        'DATA_SCIENCE', 'BUSINESS_ADMINISTRATION', 'FINANCE', 'ACCOUNTING',
        'MARKETING', 'ECONOMICS', 'NURSING', 'MEDICINE', 'PHARMACY',
        'PUBLIC_HEALTH', 'EDUCATION', 'LAW', 'ARCHITECTURE', 'BIOLOGY',
        'CHEMISTRY', 'PHYSICS', 'MATHEMATICS', 'PSYCHOLOGY', 'FINE_ARTS',
        'HOSPITALITY_MANAGEMENT', 'SUPPLY_CHAIN', 'HUMAN_RESOURCES',
        'AEROSPACE_ENGINEERING', 'ARTIFICIAL_INTELLIGENCE', 'BIOMEDICAL_ENGINEERING',
        'BIOTECHNOLOGY', 'CYBERSECURITY', 'ENVIRONMENTAL_ENGINEERING',
        'MATERIALS_SCIENCE', 'NEUROSCIENCE', 'PHARMACOLOGY',
        'RENEWABLE_ENERGY_ENGINEERING', 'SOFTWARE_ENGINEERING'
    ]::text[]),
    -- Two new deliverables the roster sheet already sells: a recommendation letter and
    -- a wage level letter.
    ADD CONSTRAINT expert_letter_types_known CHECK (letter_types <@ ARRAY[
        'CREDENTIAL_EVALUATION', 'EXPERT_OPINION_LETTER', 'RFE_RESPONSE',
        'PERM_LETTER', 'TRANSLATION_CERTIFICATION', 'RECOMMENDATION_LETTER',
        'WAGE_LEVEL_LETTER'
    ]::text[]),
    -- The new array gets the same treatment as the two above: the enum stops a caller,
    -- the CHECK stops a seed script. NACES purposes are visa categories here because
    -- `visa_category` is the column the credential-evaluation side already uses for
    -- "what is this for".
    ADD CONSTRAINT expert_visa_categories_known CHECK (visa_categories <@ ARRAY[
        'H1B', 'EB1A', 'EB2_NIW', 'O1', 'TN', 'PERM', 'L1A',
        'EDUCATION', 'EMPLOYMENT', 'ADMISSION', 'OTHER'
    ]::text[]),
    ADD CONSTRAINT expert_affiliation_type_known CHECK (affiliation_type IN (
        'UNIVERSITY', 'INDUSTRY', 'NATIONAL_LAB', 'GOVERNMENT', 'INDEPENDENT'
    ));

-- The ENM filters the roster by where an expert is and whether they take rush work;
-- both are cheap and both are read on every roster page.
CREATE INDEX idx_expert_brand_country ON expert (brand_id, country);
CREATE INDEX idx_expert_brand_rush ON expert (brand_id, rush_available) WHERE rush_available;
