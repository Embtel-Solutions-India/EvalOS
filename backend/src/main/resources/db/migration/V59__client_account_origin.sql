-- Where a client_account came from — so a destructive sweep can tell two rows apart that are
-- otherwise identical.
--
-- **The bug this exists to prevent, found in review before it shipped.** `PORTAL_CLEANUP` deletes
-- abandoned sign-ups, and its predicate was `password_hash IS NULL AND created_at < cutoff`. That
-- is *also* an exact description of every client V45 seeded: the backfill copies real clients out
-- of contact_snapshot with a null password and a real ghl_contact_id, and they stay that way until
-- the day each one first sets a password. None of them has a credential token, an application or a
-- portal_access either, so all three of the sweep's safety clauses pass. Thirty days after V45 ran,
-- the whole seeded backlog would have been deleted — and each of those clients would then be told
-- "we couldn't find that email", which is the precise failure V45's own header says it exists to
-- prevent.
--
-- **Why a column rather than a cleverer predicate.** The two rows are genuinely indistinguishable
-- on the data: same null password, same linked contact, same null last_sign_in_at. Every candidate
-- discriminator was incidental — `contact_id IS NULL` happens to separate them today only because
-- V45 predates V55's column, which is a fact about migration order, not about what the rows mean.
-- A destructive daily job must not rest on that.
--
-- **DEFAULT 'SEED' is the safe default and is deliberate.** Every existing row becomes SEED, which
-- the sweep never touches. A row has to be positively claimed as a self-service sign-up before
-- anything will delete it, so a future writer that forgets to set this gets a row that survives
-- rather than one that quietly qualifies for deletion.

alter table client_account
    add column created_via text not null default 'SEED'
        check (created_via in ('SEED', 'SIGNUP', 'STAFF'));

comment on column client_account.created_via is
    'SEED = V45 backfill or another bulk import; SIGNUP = self-service /auth/sign-up; '
    'STAFF = created by a staff action. Only SIGNUP rows are eligible for PORTAL_CLEANUP.';

-- Partial, because this is the only query that reads the column and it reads one value of it.
create index client_account_abandoned_idx
    on client_account (created_at)
    where created_via = 'SIGNUP' and password_hash is null;

-- PORTAL_CLEANUP's other half. Without it the sweep sequential-scans client_credential_token,
-- which is the one table a sign-up flood grows fastest — the pass that exists to clean up after a
-- flood would be the pass most slowed by one.
create index client_credential_token_expiry_idx
    on client_credential_token (expires_at);
