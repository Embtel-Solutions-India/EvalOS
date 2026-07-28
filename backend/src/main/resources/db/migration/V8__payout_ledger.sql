-- The manual payout ledger. EvalOS records that a payment happened; it never
-- moves money and has no disbursement rail.
CREATE TABLE payout_ledger (
    id          uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    brand_id    uuid          NOT NULL REFERENCES brand (id),
    case_id     uuid          REFERENCES evalos_case (id),
    expert_id   uuid          REFERENCES expert (id),
    amount      numeric(12,2),
    currency    text,
    status      text,
    -- Filled in by the manual payout form (Unit 16).
    method      text,
    reference   text,
    due_date    timestamptz,
    paid_date   timestamptz,
    recorded_by uuid          REFERENCES team_member (id),
    created_at  timestamptz   NOT NULL DEFAULT now()
);

CREATE INDEX idx_payout_brand_status ON payout_ledger (brand_id, status);
CREATE INDEX idx_payout_brand_expert ON payout_ledger (brand_id, expert_id);
