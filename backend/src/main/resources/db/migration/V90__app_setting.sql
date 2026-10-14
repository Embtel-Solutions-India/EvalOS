-- Settings the Administrator changes in the app (D83, spec 85). One row per setting that has been saved;
-- no row means the environment variable applies. Deleting a row is "reset to environment", and an update is a
-- change of current configuration, not history: the history is the audit trail (`object_type = 'SETTING'`).
--
-- Deployment-wide by nature — it configures this server (its SMTP relay, its GHL credential), not a brand's rows —
-- so it carries no brand_id. The key list is closed in code (`Setting`), not here, so a new setting is not a migration.
--
-- A secret's value is AES-GCM ciphertext (`PaymentDetailConverter`, keyed by EVALOS_FIELD_KEY), never plaintext.
CREATE TABLE app_setting (
    key        text        PRIMARY KEY,
    value      text        NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid        REFERENCES team_member (id)
);
