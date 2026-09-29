-- Unit 65 (D59 edited 2026-09-30): the fee a case pays its expert lives on the offer.
ALTER TABLE expert_case_offer
    ADD COLUMN fee        numeric(12,2) CHECK (fee >= 0),
    ADD COLUMN fee_set_by uuid REFERENCES team_member (id),
    ADD COLUMN fee_set_at timestamptz;

-- Open and accepted offers get the fee they would have been paid at delivery, so none is left
-- unpriced and today's payouts appear in the register.
UPDATE expert_case_offer o
   SET fee = e.standard_fee, fee_set_at = now()
  FROM expert e
 WHERE o.outcome IN ('OFFERED', 'ACCEPTED') AND e.id = o.expert_id;

-- An accepted offer that already has a payout takes the payout's amount: that is what was agreed
-- in practice (the standard fee, possibly corrected before this unit).
UPDATE expert_case_offer o
   SET fee = p.amount, fee_set_at = now()
  FROM payout_ledger p
 WHERE o.outcome = 'ACCEPTED' AND p.case_id = o.case_id AND p.expert_id = o.expert_id
   AND p.status <> 'VOIDED' AND p.amount IS NOT NULL;
