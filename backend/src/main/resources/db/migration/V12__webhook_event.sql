-- Every inbound webhook that got past signature verification, archived before it
-- produced any side effect (invariant 10). This table is both the idempotency
-- ledger and the replay archive.
--
-- `brand_id` is nullable for the same reason as on audit_event: a future source
-- may be archived before a brand is resolved. A GHL event always has one, because
-- brand resolution happens before anything is written.
CREATE TABLE webhook_event (
    id                 uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    source             text        NOT NULL,
    event_type         text        NOT NULL,
    -- The idempotency key: invoice ref, payment id, or the provider's event id.
    external_id        text        NOT NULL,
    brand_id           uuid        REFERENCES brand (id),
    signature_verified boolean     NOT NULL,
    raw_payload        jsonb       NOT NULL,
    processed          boolean     NOT NULL DEFAULT false,
    received_at        timestamptz NOT NULL DEFAULT now(),
    processed_at       timestamptz,
    -- Last failure, so a row that returned a retriable 5xx says why.
    error              text,
    -- One event id from one source is processed once, enforced here rather than by
    -- a check-then-insert: two concurrent redeliveries would both pass that check.
    CONSTRAINT uq_webhook_event_source_external UNIQUE (source, external_id)
);

CREATE INDEX idx_webhook_event_brand_type ON webhook_event (brand_id, event_type);
