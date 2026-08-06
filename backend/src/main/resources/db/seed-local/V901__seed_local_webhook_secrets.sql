-- Local dev secrets for the two seeded brands. A separate migration rather than an
-- edit to V900, because V900 has already been applied on dev databases and an
-- applied migration is never edited (invariant 9).
--
-- Throwaway values: a real per-brand secret comes from GHL and lives in the
-- environment, never in a file that ships in the jar.
UPDATE brand SET ghl_webhook_secret = 'local-ie-webhook-secret'
    WHERE id = '11111111-1111-1111-1111-111111111111';

UPDATE brand SET ghl_webhook_secret = 'local-xp-webhook-secret'
    WHERE id = '22222222-2222-2222-2222-222222222222';
