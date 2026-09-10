-- Unit 35, D1: a portal credential names a PARTY, not only a case.
--
-- A client with two cases had two links and no way to see both; the delivered portal screens draw
-- a *list*, which no case-scoped token can answer. The alternative — accounts — was **refused, not
-- deferred**: a password store needs a reset flow, and a reset flow needs the mail channel
-- invariant 14 says does not exist.
--
-- So `case_id` becomes nullable and a row names one of three things:
--
--   * a case          — `case_id`, exactly today's credential, still legal and still 30 days
--   * a client party  — `ghl_contact_id`, every case that contact has
--   * an expert party — `expert_id`, every case that expert is on
--
-- **`ghl_contact_id` is GHL's contact id and never an email** (invariant 7; V27 settled that email
-- is a fallback matching key only). It is the same identifier the S3 key prefix uses, so one client
-- resolves in GHL, in the bucket and in EvalOS with no mapping table.
--
-- `expert_id` arrived in V37 as an identity *check* on a case-scoped token. This migration gives it
-- a second job without changing the first: on a party-scoped row it **is** the scope. V37's
-- fail-closed read is unchanged — a null expert on an EXPERT token is still refused.
ALTER TABLE portal_access
    ALTER COLUMN case_id DROP NOT NULL,
    ADD COLUMN ghl_contact_id text;

-- **This CHECK is worth having, and V37's was not** — the difference is what the constraint has to
-- survive. V37's would have had to hold over the UPDATE that revokes a pre-column row, so it would
-- have blocked the cleanup it existed to force. This one only ever sees rows written from here on:
-- every existing row has a `case_id`, so the first arm holds for all of them and the migration
-- cannot fail on data already present.
--
-- Note the arms are deliberately not mutually exclusive at the SQL level. A row with both a
-- `case_id` and a party id would satisfy the first arm, and the database is not the right place to
-- forbid it — `PortalAccessService.mint` is the only writer and sets exactly one. What the CHECK
-- guarantees is the thing a read depends on: **no row is scoped to nothing.**
ALTER TABLE portal_access
    ADD CONSTRAINT portal_access_scope_is_one_thing CHECK (
        (case_id IS NOT NULL)
        OR (audience = 'CLIENT' AND ghl_contact_id IS NOT NULL)
        OR (audience = 'EXPERT' AND expert_id IS NOT NULL));

-- The one-live-token invariant moves with the scope.
--
-- V23's index was `(case_id, audience) WHERE revoked_at IS NULL`, which now admits unlimited party
-- rows — every one of them has a null `case_id`, and Postgres treats nulls as distinct in a unique
-- index. Three partial indexes instead, one per shape, so that:
--
--   * a case link and a party link for the same person **coexist** (they are different credentials
--     with different lifetimes — 30 days and 7), and
--   * neither shape can double up, which is what makes "re-minting revokes the previous" an
--     invariant of the database rather than a promise of the service.
DROP INDEX uq_portal_access_one_unrevoked;

CREATE UNIQUE INDEX uq_portal_access_one_unrevoked_case
    ON portal_access (case_id, audience)
    WHERE revoked_at IS NULL AND case_id IS NOT NULL;

-- **Both party indexes lead with `brand_id`**, and that is not decoration. V16 already treats a
-- contact as per-brand (`uq_contact_per_brand_ghl_id`), so the same GHL contact can legitimately be
-- a client of two brands; without the brand column here, minting their second brand's link would
-- revoke the first brand's. Same reasoning for an expert on two brands' panels.
CREATE UNIQUE INDEX uq_portal_access_one_unrevoked_client_party
    ON portal_access (brand_id, ghl_contact_id)
    WHERE revoked_at IS NULL AND case_id IS NULL AND audience = 'CLIENT';

CREATE UNIQUE INDEX uq_portal_access_one_unrevoked_expert_party
    ON portal_access (brand_id, expert_id)
    WHERE revoked_at IS NULL AND case_id IS NULL AND audience = 'EXPERT';

-- The client party read needs one index that does not exist yet.
--
-- It resolves `ghl_contact_id` -> contact -> **every** case that contact has. The first hop is
-- covered by V16's `uq_contact_per_brand_ghl_id`. The second is not: V15's
-- `uq_case_open_per_contact_service` is **partial** (`WHERE current_stage <> 'CLOSED'`), so it
-- cannot serve a list that includes delivered work — and a client's "my cases" must, because a
-- closed case is the one whose letter they came back for.
--
-- The expert side needs nothing: V5's `idx_case_brand_expert` already covers the assignment list,
-- and V8's `idx_payout_brand_expert` covers D6's payout read.
CREATE INDEX idx_case_brand_contact ON evalos_case (brand_id, contact_id);
