-- Case Creation v2.0. Handoff A fires on the GHL opportunity being marked Won, so the
-- case now carries the opportunity it was born from. Unit 18 needs it to close that
-- opportunity back in GHL, and support needs it to answer "which deal is this".
ALTER TABLE evalos_case
    ADD COLUMN ghl_opportunity_id text;

-- Two cases for one won opportunity is a duplicate, and the gateway's event_id dedupe
-- does not catch it: two deliveries of the same opportunity with different event ids are
-- genuinely different deliveries. Enforced in the index rather than by a lookup for the
-- reason V15 gives — a check-then-act is a race both transactions win.
--
-- Partial on `current_stage <> 'CLOSED'`, and that clause is load-bearing. The open-case
-- lookup deliberately ignores closed cases, so a client returning after their first case
-- closed takes the create path — new business, not a duplicate. If GHL re-uses or re-wins
-- that opportunity id, an unscoped index would turn legitimate repeat business into a
-- constraint violation: a 5xx GHL retries forever, and no case for a deal that was paid
-- for. NULL ids are exempt, which covers every row created before this migration.
CREATE UNIQUE INDEX uq_case_open_per_opportunity
    ON evalos_case (brand_id, ghl_opportunity_id)
    WHERE ghl_opportunity_id IS NOT NULL AND current_stage <> 'CLOSED';
