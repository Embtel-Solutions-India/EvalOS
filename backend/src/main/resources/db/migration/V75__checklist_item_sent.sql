-- Unit 61 (D60): a checklist item reaches the client portal only once the PC or the CM sends it.
--
-- sent_at NULL means unsent: the client does not see the item and cannot upload against it.
-- sent_by is who pressed Send; NULL on a sent row means it was sent before D60 (the backfill below).
ALTER TABLE document_checklist_item
    ADD COLUMN sent_at timestamptz,
    ADD COLUMN sent_by uuid REFERENCES team_member (id);

-- **Every item that exists today is already visible to its client**, because until now an item
-- showed in the portal the moment it was saved. Marking them sent keeps that true: D60 changes
-- what happens to new items, and no client loses a list they can already see.
UPDATE document_checklist_item SET sent_at = COALESCE(updated_at, now()) WHERE sent_at IS NULL;
