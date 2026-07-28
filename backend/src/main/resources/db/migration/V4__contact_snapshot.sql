-- Read-only snapshot of a GHL contact. GHL owns the record; EvalOS keeps a
-- brand-tagged copy so a case can be read without calling out. No code path
-- updates a synced field (invariant 7) — a re-sync replaces `synced_at` and the
-- snapshot wholesale.
CREATE TABLE contact_snapshot (
    id                  uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    brand_id            uuid        NOT NULL REFERENCES brand (id),
    ghl_contact_id      text,
    full_name           text,
    email               text,
    phone               text,
    company             text,
    client_type         text,
    source_channel      text,
    utm_source          text,
    utm_medium          text,
    utm_campaign        text,
    date_first_captured timestamptz,
    synced_at           timestamptz,
    created_at          timestamptz NOT NULL DEFAULT now()
);
