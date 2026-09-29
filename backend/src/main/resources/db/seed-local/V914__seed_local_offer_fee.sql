-- On a fresh database the seed inserts its offers after V79 ran; price them the same way (Unit 65).
UPDATE expert_case_offer o
   SET fee = e.standard_fee, fee_set_at = now()
  FROM expert e
 WHERE o.fee IS NULL AND o.outcome IN ('OFFERED', 'ACCEPTED') AND e.id = o.expert_id;

UPDATE expert_case_offer o
   SET fee = p.amount, fee_set_at = now()
  FROM payout_ledger p
 WHERE o.outcome = 'ACCEPTED' AND p.case_id = o.case_id AND p.expert_id = o.expert_id
   AND p.status <> 'VOIDED' AND p.amount IS NOT NULL;
