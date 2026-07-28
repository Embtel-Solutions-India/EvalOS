-- Brands are the tenants. Every scoped row in EvalOS points at one of these.
CREATE TABLE brand (
    id                     uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    name                   text        NOT NULL,
    slug                   text        NOT NULL UNIQUE,
    active                 boolean     NOT NULL DEFAULT true,
    -- Each brand is a separate GHL sub-account with its own inbound endpoint;
    -- this token is what resolves brand_id at Handoff A.
    webhook_endpoint_token text        NOT NULL UNIQUE,
    created_at             timestamptz NOT NULL DEFAULT now()
);
