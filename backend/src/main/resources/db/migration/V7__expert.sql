-- The brand-scoped expert roster. `payment_detail` is the one encrypted field in
-- EvalOS: it is written and read only through PaymentDetailConverter, so what
-- lands here is AES-256-GCM ciphertext, never a readable account number.
CREATE TABLE expert (
    id                     uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    brand_id               uuid          NOT NULL REFERENCES brand (id),
    full_name              text,
    title                  text,
    institution            text,
    -- Taxonomy tags used by matching (Unit 11).
    primary_fields         text[],
    secondary_fields       text[],
    availability           text,
    tier                   text,
    quality_score          numeric(3,1),
    avg_response_hours     numeric,
    total_cases_completed  int           NOT NULL DEFAULT 0,
    current_active_count   int           NOT NULL DEFAULT 0,
    agreement_status       text,
    payment_status         text,
    total_payments_pending numeric(12,2) NOT NULL DEFAULT 0,
    -- Values from the PerformanceFlag enum.
    performance_flags      text[],
    recruitment_source     text,
    date_onboarded         date,
    notes                  text,
    payment_detail         text,
    created_at             timestamptz   NOT NULL DEFAULT now()
);

CREATE INDEX idx_expert_brand_availability ON expert (brand_id, availability);
CREATE INDEX idx_expert_brand_tier ON expert (brand_id, tier);

-- Deferred from V5: evalos_case.expert_id could not reference a table that did
-- not exist yet.
ALTER TABLE evalos_case
    ADD CONSTRAINT fk_case_expert FOREIGN KEY (expert_id) REFERENCES expert (id);
