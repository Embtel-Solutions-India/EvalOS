-- Unit 16b. Money owed and money sent are two facts, so they are two tables.
--
-- A payout_ledger row is one delivered draft an expert is owed for. A payout_payment
-- row is one transfer that actually left the bank, covering however many drafts it
-- covered. The expert charges per draft and is paid weekly, so the two counts do not
-- match and one table would have to lie about one of them.
CREATE TABLE payout_payment (
    id           uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    brand_id     uuid          NOT NULL REFERENCES brand (id),
    -- NOT NULL, unlike payout_ledger.expert_id: a row can theoretically open on a
    -- case with no expert, but a payment with no payee is not a thing to record.
    expert_id    uuid          NOT NULL REFERENCES expert (id),
    -- > 0, not >= 0: a payment of nothing is not a payment, and it would sum into
    -- every money-out total while looking like a settled week.
    amount       numeric(12,2) NOT NULL CHECK (amount > 0),
    currency     text          NOT NULL,
    method       text          NOT NULL,
    reference    text          NOT NULL,
    paid_date    timestamptz   NOT NULL,
    -- On the payment rather than the row: notes describe the transfer, not one draft.
    notes        text,
    confirmed_at timestamptz,
    recorded_by  uuid          NOT NULL REFERENCES team_member (id),
    created_at   timestamptz   NOT NULL DEFAULT now()
);

CREATE INDEX idx_payment_brand_expert ON payout_payment (brand_id, expert_id);
CREATE INDEX idx_payment_brand_paid ON payout_payment (brand_id, paid_date);

ALTER TABLE payout_ledger
    ADD COLUMN payment_id uuid REFERENCES payout_payment (id);

CREATE INDEX idx_payout_payment ON payout_ledger (payment_id);

-- Never written — Unit 16 was never built, so no data is lost. They move to
-- payout_payment, where one transfer carries one method/reference/date instead of N
-- copies of it. Dropped rather than left dead: these sit on a money table and read as
-- load-bearing, and a future reader who finds payout_ledger.reference would
-- reasonably write code that half-works.
ALTER TABLE payout_ledger
    DROP COLUMN method,
    DROP COLUMN reference,
    DROP COLUMN paid_date;

-- A negative payout is not a smaller payout, it is money flowing the wrong way, and it
-- would sum into every total while looking like a discount. A refund is VOIDED plus its
-- own row, never a negative amount. NULL stays legal: it means "not decided yet", which
-- is the point of the column being nullable.
ALTER TABLE payout_ledger
    ADD CONSTRAINT payout_amount_not_negative CHECK (amount IS NULL OR amount >= 0);

-- Currency is what somebody is actually paid in. An expert on a GBP agreement paid a
-- USD number is wrong twice, in the amount and in the record of what was owed, so the
-- gap must not be able to reach the ledger at all.
ALTER TABLE payout_ledger
    ALTER COLUMN currency SET NOT NULL;

-- deliverToClient's `deliveryDate == null` guard is a check-then-act and Case has no
-- @Version, so two concurrent deliveries can both read null and both open a row. The
-- index cannot race; the loser rolls back. Partial on VOIDED so a refunded case that
-- re-delivers is not blocked by the tombstone.
CREATE UNIQUE INDEX uq_payout_per_case
    ON payout_ledger (case_id) WHERE status <> 'VOIDED';

-- Spec 16 reads "the brand's configured currency" and "the configured payout term".
-- V2__brand.sql has neither. Flyway applies all migrations globally by version number
-- regardless of location, so V28 runs before db/seed-local/V900__seed_local.sql. If
-- currency were NOT NULL here, the seed inserts would fail on a fresh database.
-- Instead, currency stays nullable; payout_ledger.currency NOT NULL ensures the gap
-- does not reach the ledger, and Task 3's openForDelivery refuses a brand with no
-- currency rather than guessing one. Seeded brands are updated by V904.
-- payout_term_days keeps a default — a wrong due date is a visible annoyance, not a
-- wrong payment.
ALTER TABLE brand
    ADD COLUMN currency text,
    ADD COLUMN payout_term_days int NOT NULL DEFAULT 7;
