-- Payment is no longer what creates a case. Handoff A now fires on contact
-- creation, so a case exists before money does and "paid" becomes a fact recorded
-- on it rather than the reason it exists.
--
-- Defaulting to false is the safe direction: every row that already exists, and
-- every row created from here on, is unpaid until somebody says otherwise. The
-- state machine will not let an unpaid case reach an expert.
--
-- No paid_by column: audit_event already records who did what, and a second
-- record of the same fact is a second thing that can disagree.
ALTER TABLE evalos_case
    ADD COLUMN paid    boolean     NOT NULL DEFAULT false,
    ADD COLUMN paid_at timestamptz;

-- The board and the revenue dashboards both slice on this.
CREATE INDEX idx_case_brand_paid ON evalos_case (brand_id, paid);
