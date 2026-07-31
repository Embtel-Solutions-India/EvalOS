-- Unit 11: the roster columns V7 did not give `expert`, and the closed taxonomy
-- Unit 12 will match on.
--
-- The vocabulary is enforced twice on purpose. `domain/FieldTag` and
-- `domain/LetterType` stop a controller accepting an unknown tag; the CHECKs below
-- stop a migration, a seed script or a hand-written UPDATE doing the same. Either
-- alone is insufficient — the Java enum does not exist inside psql, and the
-- constraint cannot produce a 400 — and the cost of a closed vocabulary (exact
-- matching in Unit 12, instead of "Mechanical Engineering" failing to match
-- "mechanical engg") is only bought if a row cannot get in some other way. This is
-- the `team_member_role_valid` pattern from V3.
--
-- **The value list has not been signed off by an Expert Network Manager.** It is the
-- starter list from `context/specs/11-expert-database.md`, shipped on instruction so
-- the unit could be built. Widening it is a NEW migration that widens the CHECK —
-- never an edit to this one (invariant 9).
--
-- `<@` is "contained by". A NULL array yields NULL, which a CHECK accepts: an expert
-- with no tags recorded yet is legal, an expert with a tag nobody recognises is not.
ALTER TABLE expert
    ADD COLUMN email        text,
    ADD COLUMN phone        text,
    -- Which deliverables this expert will sign. Values from the LetterType enum.
    ADD COLUMN letter_types text[],
    -- The expert's usual fee, which Unit 16 prefills a payout with. Not a price
    -- EvalOS charges anyone — nothing client-facing reads this column.
    ADD COLUMN standard_fee numeric(12,2);

ALTER TABLE expert
    ADD CONSTRAINT expert_primary_fields_known CHECK (primary_fields <@ ARRAY[
        'MECHANICAL_ENGINEERING', 'ELECTRICAL_ENGINEERING', 'CIVIL_ENGINEERING',
        'CHEMICAL_ENGINEERING', 'COMPUTER_SCIENCE', 'INFORMATION_TECHNOLOGY',
        'DATA_SCIENCE', 'BUSINESS_ADMINISTRATION', 'FINANCE', 'ACCOUNTING',
        'MARKETING', 'ECONOMICS', 'NURSING', 'MEDICINE', 'PHARMACY',
        'PUBLIC_HEALTH', 'EDUCATION', 'LAW', 'ARCHITECTURE', 'BIOLOGY',
        'CHEMISTRY', 'PHYSICS', 'MATHEMATICS', 'PSYCHOLOGY', 'FINE_ARTS',
        'HOSPITALITY_MANAGEMENT', 'SUPPLY_CHAIN', 'HUMAN_RESOURCES'
    ]::text[]),
    ADD CONSTRAINT expert_secondary_fields_known CHECK (secondary_fields <@ ARRAY[
        'MECHANICAL_ENGINEERING', 'ELECTRICAL_ENGINEERING', 'CIVIL_ENGINEERING',
        'CHEMICAL_ENGINEERING', 'COMPUTER_SCIENCE', 'INFORMATION_TECHNOLOGY',
        'DATA_SCIENCE', 'BUSINESS_ADMINISTRATION', 'FINANCE', 'ACCOUNTING',
        'MARKETING', 'ECONOMICS', 'NURSING', 'MEDICINE', 'PHARMACY',
        'PUBLIC_HEALTH', 'EDUCATION', 'LAW', 'ARCHITECTURE', 'BIOLOGY',
        'CHEMISTRY', 'PHYSICS', 'MATHEMATICS', 'PSYCHOLOGY', 'FINE_ARTS',
        'HOSPITALITY_MANAGEMENT', 'SUPPLY_CHAIN', 'HUMAN_RESOURCES'
    ]::text[]),
    ADD CONSTRAINT expert_letter_types_known CHECK (letter_types <@ ARRAY[
        'CREDENTIAL_EVALUATION', 'EXPERT_OPINION_LETTER', 'RFE_RESPONSE',
        'PERM_LETTER', 'TRANSLATION_CERTIFICATION'
    ]::text[]);

-- The import's upsert key, and the reason a re-uploaded sheet updates instead of
-- duplicating. An index rather than a lookup because the lookup is a check-then-act
-- two concurrent uploads can both win — the V15/V16 lesson, and `Expert` carries no
-- @Version either. Partial because an expert with no email on file is legal; they
-- simply cannot be upserted, and the import reports them rather than guessing.
-- lower(email) because the finder is findByBrandIdAndEmailIgnoreCase: the index
-- expression and the finder must agree or the index does not apply.
CREATE UNIQUE INDEX uq_expert_per_brand_email
    ON expert (brand_id, lower(email))
    WHERE email IS NOT NULL;

-- Unit 12 asks "which experts carry this field tag" once per case being matched.
-- GIN is what makes `primary_fields @> ARRAY[...]` an index lookup rather than a
-- scan of the brand's whole roster. Unit 11's own roster filter does not need it —
-- it narrows a page already in memory — so this index is built for the next unit,
-- on purpose, because the column it indexes is this unit's.
CREATE INDEX idx_expert_primary_fields ON expert USING gin (primary_fields);
