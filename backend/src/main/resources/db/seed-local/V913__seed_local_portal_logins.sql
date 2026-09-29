-- Local only: one client and one expert who can sign in to the portals, so both can be clicked
-- through against the demo cases without the set-password email.
--
-- Same throwaway password as every other seeded login: DevPassw0rd!
--
-- Client: Amara Okafor (V905 contact 01, GHL id ghl-demo-0001). She owns IE-2026-4801, which is in
-- document collection, so the checklist send (Unit 61) and the upload can be tried end to end.
-- Expert: Dr Miriam Osei (V905 expert 01), assigned to five IE cases.
--
-- Both upsert, so re-running against a database where the account already exists (V45 created
-- client accounts from contacts) sets the password rather than failing.

INSERT INTO client_account (id, brand_id, email, password_hash, ghl_contact_id, contact_id,
                            first_name, last_name, created_via, created_at)
SELECT gen_random_uuid(), c.brand_id, c.email,
       '$2a$10$r5HWTZRMQLgLPJKNHaZGgujwqeEjBbsDR8dpmh6JuZ7QdUjE1DHMW',
       c.ghl_contact_id, c.id, 'Amara', 'Okafor', 'SEED', now()
  FROM contact_snapshot c
 WHERE c.id = 'c0000000-0000-0000-0000-000000000001'
ON CONFLICT (brand_id, lower(email)) DO UPDATE
   SET password_hash  = EXCLUDED.password_hash,
       ghl_contact_id = EXCLUDED.ghl_contact_id,
       contact_id     = EXCLUDED.contact_id;

INSERT INTO expert_account (id, brand_id, expert_id, password_hash, created_at)
SELECT gen_random_uuid(), e.brand_id, e.id,
       '$2a$10$r5HWTZRMQLgLPJKNHaZGgujwqeEjBbsDR8dpmh6JuZ7QdUjE1DHMW', now()
  FROM expert e
 WHERE e.id = 'e0000000-0000-0000-0000-000000000001'
ON CONFLICT (expert_id) DO UPDATE
   SET password_hash = EXCLUDED.password_hash;
