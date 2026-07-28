-- Baseline. No domain tables yet (Unit 03 owns those).
-- pgcrypto gives later migrations gen_random_uuid() and digest helpers.
CREATE EXTENSION IF NOT EXISTS pgcrypto;
