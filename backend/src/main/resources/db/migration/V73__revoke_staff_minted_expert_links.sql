-- Unit 59 (D23, 2026-09-28): staff-minted expert links are removed — an expert signs up, sets a
-- password and signs in, and that is the only way in. Every expert link already in an inbox is
-- revoked here rather than left to live out its TTL. An expert signed in before this runs simply
-- signs in again (their account is untouched).
UPDATE portal_access
   SET revoked_at = now()
 WHERE audience = 'EXPERT'
   AND revoked_at IS NULL
   AND expires_at > now();
