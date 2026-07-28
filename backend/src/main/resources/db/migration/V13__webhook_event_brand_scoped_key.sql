-- Scope the idempotency key by brand.
--
-- V12 made it UNIQUE (source, external_id), which is brand-agnostic. But each brand
-- is a separate GHL sub-account numbering its own invoices, so two brands can
-- legitimately send the same invoice_ref — and the first one to arrive would suppress
-- the second as a duplicate, dropping a paid case and returning the other brand's
-- event id. Reached in testing, not theoretical.
--
-- NULLS NOT DISTINCT because brand_id is nullable: with the default (NULLS DISTINCT)
-- two brand-less rows sharing a source and external id would both be accepted, which
-- is exactly the deduplication this constraint exists to provide. No such row exists
-- today — GHL resolves its brand before anything is archived — but a future source
-- archived before brand resolution must not silently lose idempotency.
ALTER TABLE webhook_event DROP CONSTRAINT uq_webhook_event_source_external;

ALTER TABLE webhook_event ADD CONSTRAINT uq_webhook_event_source_brand_external
    UNIQUE NULLS NOT DISTINCT (source, brand_id, external_id);
