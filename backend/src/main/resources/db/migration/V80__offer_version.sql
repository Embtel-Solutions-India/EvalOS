-- Unit 65 review: optimistic locking on the offer. A fee edit read before the expert answered would
-- otherwise write the whole row back and set the outcome to OFFERED again; with a version it fails
-- (409 CHANGED_MEANWHILE) instead.
ALTER TABLE expert_case_offer ADD COLUMN version bigint NOT NULL DEFAULT 0;
