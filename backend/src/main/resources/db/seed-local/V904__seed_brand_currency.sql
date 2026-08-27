-- Set currency on the two seeded brands. A separate migration rather than an edit
-- to V900, because Flyway orders migrations globally by version number across all
-- locations, so V28__payout_payment.sql runs before V900__seed_local.sql. Making
-- currency NOT NULL in the schema would fail the seed inserts on a fresh database.
-- Instead, currency is nullable on brand (Task 3's openForDelivery refuses a brand
-- with no currency), and is populated here.
UPDATE brand SET currency = 'USD'
    WHERE id = '11111111-1111-1111-1111-111111111111';

UPDATE brand SET currency = 'USD'
    WHERE id = '22222222-2222-2222-2222-222222222222';
