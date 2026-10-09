-- The production Admin login (spec 78, D78).
--
-- The GM no longer administers staff, so a production database with no Admin cannot add one. This seeds
-- the first. Brand NULL: the Admin is cross-brand, like the GM.
--
-- The password arrives as the `admin-password-hash` Flyway placeholder, resolved from
-- `ADMIN_PASSWORD_HASH` at migrate time with NO DEFAULT, exactly as V960 does for the desks: nothing
-- about the credential is in this file or in git, and an environment that forgets the variable fails
-- to migrate rather than seeding a known hash. Rotate with an UPDATE, never by editing this file —
-- an applied migration's checksum is fixed.
--
-- The address is a placeholder too, because the real one is the operator's to choose. Change it once
-- the account exists (an Admin edits their own record under Staff).
INSERT INTO team_member (id, brand_id, team_id, role, email, password_hash, display_name, reports_to, active)
VALUES (gen_random_uuid(), NULL, NULL, 'ADMIN', '${admin-email}', '${admin-password-hash}',
        'Administrator', NULL, true)
ON CONFLICT DO NOTHING;
