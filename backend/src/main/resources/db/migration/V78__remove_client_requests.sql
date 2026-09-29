-- Unit 64: the client no longer requests a service from the portal. Spec 64-remove-client-requests.md.

-- 1. The request and the documents sent with it. Nothing references either table; every FK points
--    outward. Documents already carried onto cases are ordinary case_document rows and stay. The
--    request documents never carried (a request not yet won) keep their S3 objects under the
--    contact's prefix, but no row points at them any more -- stated as a cost in spec 64 §6.
--    client_application's unmapped `answers` column (Unit 55's pending drop) goes with the table.
DROP TABLE application_document;
DROP TABLE client_application;

-- 2. INTAKE was where the portal filed a request. Nothing reads it now. A pipeline a GM had marked
--    INTAKE goes back to UNASSIGNED, the state a GM sets a purpose from.
UPDATE pipeline SET purpose = 'UNASSIGNED' WHERE purpose = 'INTAKE';
ALTER TABLE pipeline DROP CONSTRAINT pipeline_purpose_check;
ALTER TABLE pipeline ADD CONSTRAINT pipeline_purpose_check
    CHECK (purpose IN ('MARKETING', 'SALES', 'DELIVERY', 'EXPERT_HIRING', 'UNASSIGNED'));

-- 3. The portal account is created when the client's case is (D3d). SIGNUP stays for the rows the
--    removed sign-up left behind, which PORTAL_CLEANUP still sweeps; CASE rows are never swept.
--    V59 declared the CHECK inline, so Postgres named it client_account_created_via_check.
ALTER TABLE client_account DROP CONSTRAINT client_account_created_via_check;
ALTER TABLE client_account ADD CONSTRAINT client_account_created_via_check
    CHECK (created_via IN ('SEED', 'SIGNUP', 'STAFF', 'CASE'));

COMMENT ON COLUMN client_account.created_via IS
    'SEED = V45 backfill or another bulk import; SIGNUP = the removed self-service sign-up; '
    'STAFF = created by a staff action; CASE = opened when the client''s case was created (Unit 64). '
    'Only SIGNUP rows are eligible for PORTAL_CLEANUP.';
