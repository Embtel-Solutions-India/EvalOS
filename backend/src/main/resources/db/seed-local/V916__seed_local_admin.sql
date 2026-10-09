-- The local Admin login (spec 78): admin@evalos.local, same published dev password as the rest of
-- this tree (`DevPassw0rd!`). LOCAL PROFILE ONLY — prod seeds its own from a placeholder (V961).
INSERT INTO team_member (id, brand_id, team_id, role, email, password_hash, display_name, reports_to)
VALUES ('aaaaaaaa-0000-0000-0000-0000000000a1', NULL, NULL, 'ADMIN', 'admin@evalos.local',
        '$2a$10$r5HWTZRMQLgLPJKNHaZGgujwqeEjBbsDR8dpmh6JuZ7QdUjE1DHMW', 'Alex Admin', NULL)
ON CONFLICT DO NOTHING;
