-- Ensure the production Administrator safely (spec 78 follow-up). Runs BEFORE V961 on a fresh database and
-- once, out of order, on a database that already applied V961. Never updates or deletes a team_member row.
--
-- Why it exists: V961 is `INSERT ... ON CONFLICT DO NOTHING` on the exact email, and it is applied, so it
-- cannot be edited. That left three silent failures this file turns into a refused migration (the boot
-- stops and says why) or a safe insert:
--
--   1. ADMIN_EMAIL already belongs to a NON-admin (a desk login). V961 did nothing: no Administrator, no
--      error. Refused here — an existing user is never promoted.
--   2. ADMIN_EMAIL differs from an existing row only in case or spacing. Sign-in matches case-insensitively
--      (`findByEmailIgnoreCaseAndActiveTrue`) but the unique index does not, so V961 would add a SECOND
--      login for the same address and break sign-in for both. Refused here.
--   3. ADMIN_PASSWORD_HASH is not a BCrypt hash (e.g. the password itself). V961 would store it as-is. Refused
--      here whenever the address is not already taken. Generate the value with
--      `new BCryptPasswordEncoder().encode("…")` — the encoder `SecurityConfig` signs in with.
--
-- The existing Administrator — this address, role ADMIN — is left exactly as stored: hash, active flag,
-- name, brand, everything. Repeated boots never re-run this (Flyway applies a version once).
--
-- It INSERTS only when there is no Administrator at all and the address is free — the first boot of a
-- fresh production database (V961 then finds the row and does nothing), or a database where V961 ran but
-- seeded nothing because of (1). An Administrator under another address (renamed, or testprod's V954)
-- means one exists, and no second one is created.
DO $guard$
DECLARE
    configured text := lower(trim('${admin-email}'));
    matches    int;
BEGIN
    SELECT count(*) INTO matches FROM team_member WHERE lower(trim(email)) = configured;

    IF matches > 1 THEN
        RAISE EXCEPTION 'Admin seed refused: ADMIN_EMAIL matches % accounts that differ only in case or spacing. '
            'Resolve the duplicate logins before deploying.', matches;
    END IF;

    IF matches = 1 THEN
        IF EXISTS (SELECT 1 FROM team_member WHERE email = '${admin-email}' AND role = 'ADMIN') THEN
            RETURN; -- the Administrator exists; it is left exactly as it is
        END IF;
        RAISE EXCEPTION 'Admin seed refused: ADMIN_EMAIL belongs to an existing account that is not exactly the '
            'Administrator (another role, or the same address in different case). An existing user is never '
            'promoted and no second login is created. Set ADMIN_EMAIL to the Administrator''s exact address or '
            'to an unused one.';
    END IF;

    IF '${admin-password-hash}' !~ '^\$2[aby]\$[0-9]{2}\$[./A-Za-z0-9]{53}$' THEN
        RAISE EXCEPTION 'Admin seed refused: ADMIN_PASSWORD_HASH is not a BCrypt hash. Put the output of '
            'BCryptPasswordEncoder.encode in it, never the password itself.';
    END IF;

    IF NOT EXISTS (SELECT 1 FROM team_member WHERE role = 'ADMIN') THEN
        INSERT INTO team_member (id, brand_id, team_id, role, email, password_hash, display_name, reports_to, active)
        VALUES (gen_random_uuid(), NULL, NULL, 'ADMIN', trim('${admin-email}'), '${admin-password-hash}',
                'Administrator', NULL, true);
    END IF;
END
$guard$;
