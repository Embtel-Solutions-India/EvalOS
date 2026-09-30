-- The deal screen shows everything GHL holds on the deal's contact (2026-09-30), so the mirror keeps
-- what that screen reads (D47): country, tags and custom field values. Custom field values are keyed
-- by GHL field id, as opportunity.custom_fields is, so a renamed field keeps its values.
-- Filled by the contact sweep and the deal screen's backfill; a webhook or sign-up leaves them alone.
ALTER TABLE contact_snapshot
    ADD COLUMN country       text,
    ADD COLUMN tags          text[] NOT NULL DEFAULT '{}',
    ADD COLUMN custom_fields jsonb  NOT NULL DEFAULT '{}';
