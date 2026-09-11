-- Unit 42 — a client party token may now name an EvalOS account instead of a GHL contact.
--
-- V38's constraint required a CLIENT party row to carry a ghl_contact_id. That was right when
-- the only way to be a client was to have been a GHL contact first. Unit 42 makes an EvalOS
-- account the thing a client signs in with, and after IE's GHL sub-account was replaced on
-- 2026-09-11 **most accounts have no GHL contact at all** — so the old constraint refuses
-- exactly the row the sign-in door needs to mint.
--
-- **The constraint is widened, not dropped.** A token scoped to nothing is still refused, which
-- is the property V38 was protecting. What changes is that "a client" now has two legal names.

ALTER TABLE portal_access ADD COLUMN client_account_id uuid REFERENCES client_account (id);

ALTER TABLE portal_access DROP CONSTRAINT portal_access_scope_is_one_thing;

ALTER TABLE portal_access
    ADD CONSTRAINT portal_access_scope_is_one_thing CHECK (
        (case_id IS NOT NULL)
        OR (audience = 'CLIENT' AND ghl_contact_id IS NOT NULL)
        OR (audience = 'CLIENT' AND client_account_id IS NOT NULL)
        OR (audience = 'EXPERT' AND expert_id IS NOT NULL));

-- The one-live-token rule follows the new scope, mirroring V38's partial index for contacts.
--
-- No brand column here, unlike V38's two party indexes: an account belongs to exactly one brand
-- (client_account.brand_id, V43) and its id is already unique across all of them, so the brand
-- adds nothing a join would not already know. V38 needed it because a GHL contact id is a
-- *foreign* key that the same person legitimately carries in two brands.
CREATE UNIQUE INDEX portal_access_one_live_per_account
    ON portal_access (client_account_id)
    WHERE client_account_id IS NOT NULL AND revoked_at IS NULL;
