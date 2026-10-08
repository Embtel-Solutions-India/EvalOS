-- The test-production Admin login (spec 78). Throwaway account; the hash is the one shared by every
-- seeded login here (`seed-password-hash`), kept out of git like the rest of this tree.
INSERT INTO team_member (id, brand_id, team_id, role, email, password_hash, display_name, reports_to, active)
VALUES (gen_random_uuid(), NULL, NULL, 'ADMIN', 'admin@test.evalos.invalid', '${seed-password-hash}',
        'Test Administrator', NULL, true)
ON CONFLICT DO NOTHING;
