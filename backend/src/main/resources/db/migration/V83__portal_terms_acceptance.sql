-- Unit 72 (D71): a client or expert accepts the portal's policies once, on their first sign-in, and
-- again only when the policies change. The version is the policies' date (PortalTerms.VERSION); an
-- account whose version differs is asked again. Null on both = never accepted. The acceptance is
-- also an append-only audit_event row (TERMS_ACCEPTED), which is the evidence.
ALTER TABLE client_account
    ADD COLUMN terms_accepted_at timestamptz,
    ADD COLUMN terms_version     text;

ALTER TABLE expert_account
    ADD COLUMN terms_accepted_at timestamptz,
    ADD COLUMN terms_version     text;
