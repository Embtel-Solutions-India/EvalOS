-- Unit 61 (D60): the demo checklist items above were seeded after V75 on a fresh database, so they
-- arrived unsent. They stand for items clients can already see, so they are marked sent, as V75
-- did for rows that existed before it. Idempotent: only rows still unsent are touched.
UPDATE document_checklist_item SET sent_at = COALESCE(updated_at, now()) WHERE sent_at IS NULL;
