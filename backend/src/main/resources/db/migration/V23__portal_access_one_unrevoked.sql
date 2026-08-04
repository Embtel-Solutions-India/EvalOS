-- "One live token per case per audience" becomes a constraint instead of a check-then-act.
--
-- **This corrects the record set by `V21`'s header, which stays wrong on disk** — an applied
-- migration is never edited (invariant 9), the same way `V16` corrected `V15`'s "cannot race"
-- comment. V21 said the invariant "is enforced in the service by revoking the previous one inside
-- the minting transaction" because a predicate on `expires_at > now()` cannot sit in an index
-- (`now()` is not immutable). The first half of that is true and the conclusion did not follow: the
-- invariant can be expressed without `now()` at all.
--
-- Why it needed fixing: `PortalAccessService.mint` did SELECT-live → revoke → INSERT with no lock,
-- so under READ COMMITTED two concurrent mints for one case (two staff members, or one double-click)
-- could both see the same previous row, both revoke it, and both insert — leaving two live
-- credentials for a case whose service javadoc promised one. That is the exact shape of the
-- duplicate-case defect `V15`/`V16` fixed, and this codebase's rule for it is a constraint, not a
-- smarter lookup: the loser's transaction rolls back rather than racing successfully.
--
-- The invariant is stated as **at most one unrevoked row per (case_id, audience)**, which needs no
-- clock. `mint` now retires *every* unrevoked row it supersedes rather than only the live ones, so
-- an expired row carries `revoked_at` too and leaves the index. `isLive` is unchanged
-- (`revoked_at IS NULL AND expires_at > now()`), so nothing about who may read a token moves.

-- Retire any duplicates first, newest kept, so the index can be created on an existing database.
-- An UPDATE is legitimate here: unlike `audit_event`, `portal_access` is not append-only —
-- `revoked_at` and `last_seen_at` are mutable by design.
UPDATE portal_access AS stale
SET revoked_at = now()
WHERE revoked_at IS NULL
  AND EXISTS (
      SELECT 1 FROM portal_access AS newer
      WHERE newer.case_id = stale.case_id
        AND newer.audience = stale.audience
        AND newer.revoked_at IS NULL
        AND (newer.created_at, newer.id) > (stale.created_at, stale.id));

CREATE UNIQUE INDEX uq_portal_access_one_unrevoked
    ON portal_access (case_id, audience)
    WHERE revoked_at IS NULL;
