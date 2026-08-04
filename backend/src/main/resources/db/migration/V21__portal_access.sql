-- Unit 14: the one table behind both link-based portals — the client's draft review here, and
-- the expert's surface in Unit 15 (`audience = 'EXPERT'`).
--
-- **A portal link is a credential**, so it is stored the way EvalOS stores credentials: only
-- the SHA-256 of the token is here, never the token. A database read — a backup, a support
-- query, a leaked dump — therefore yields no working link. Same reasoning as BCrypt on
-- `team_member.password_hash`, and the comparison is `MessageDigest.isEqual` for the same
-- reason `WebhookVerifier` uses it.
--
-- **The token is the scope.** One row names exactly one case, so a portal read has no
-- predicate to build and nothing to fail open on: there is no case parameter on any portal
-- route and therefore nothing to enumerate. `brand_id` is here because every scoped row
-- carries it (ScopedEntity) and because the audit row a client's approval writes takes its
-- brand from this row rather than from a request.
CREATE TABLE portal_access (
    id           uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    brand_id     uuid        NOT NULL REFERENCES brand (id),
    -- The one case this token admits. No ON DELETE: a case is never deleted in EvalOS.
    case_id      uuid        NOT NULL REFERENCES evalos_case (id),
    audience     text        NOT NULL,
    -- Hex SHA-256 of the token. The token itself is returned once, at mint time, and is
    -- stored nowhere.
    token_hash   text        NOT NULL,
    -- Absolute, and real: a draft-review link that worked forever would be a permanent bearer
    -- credential sitting in somebody's inbox. Default 30 days, configurable.
    expires_at   timestamptz NOT NULL,
    -- Set when a re-mint supersedes this row. A client who says "the link doesn't work" gets a
    -- new one and the old one stops working, so a support request cannot permanently widen the
    -- number of live credentials.
    revoked_at   timestamptz,
    -- Moved on every use. This is the field support actually needs ("did they ever open it, and
    -- when last?"); the case's own `client_portal_read_at` is stamped once, on first read.
    last_seen_at timestamptz,
    created_at   timestamptz NOT NULL DEFAULT now(),
    -- Closed the same two ways as V18's taxonomy and V19's outcome: the enum stops a caller,
    -- the CHECK stops a seed script or a hand-written UPDATE. An unrecognised audience would be
    -- a token that admits nobody, or worse, one whose audience check nothing matches.
    CONSTRAINT portal_access_audience_known CHECK (audience IN ('CLIENT', 'EXPERT'))
);

-- The lookup every portal request does, and the guarantee that two rows cannot share a token.
CREATE UNIQUE INDEX uq_portal_access_token_hash ON portal_access (token_hash);

-- The mint's own read: "is there already a live token for this case and audience", to revoke it.
--
-- **Deliberately not a partial unique index on `(case_id, audience) WHERE revoked_at IS NULL
-- AND expires_at > now()`.** `now()` is not immutable and cannot sit in an index predicate.
-- One live token per case per audience is enforced in `PortalAccessService` by revoking the
-- previous one inside the minting transaction, and the read tolerates more than one anyway
-- because it matches on the hash rather than on the case.
CREATE INDEX idx_portal_access_case_audience ON portal_access (case_id, audience);
