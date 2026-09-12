-- Unit 42, fix-forward — the seeded emails V45 left with whitespace around them.
--
-- **V45 is applied and is not edited.** It guarded on `length(trim(c.email)) > 0` but inserted
-- `c.email` raw, so a contact_snapshot address stored as ' ana@example.com ' seeded an account
-- whose email carries those spaces. ClientAccountService.normalize() trims what the client types
-- before every lookup, so that account can never be found: the client is told "we couldn't find
-- that email" forever, which is exactly the falsehood V45 exists to remove.
--
-- **Collisions are handled by deleting, not by merging.** A trimmed address can already exist as
-- its own account (two snapshots, one padded and one not, are one person with one inbox — V45's
-- DISTINCT ON groups on lower(c.email), which does not collapse them). Every seeded account has
-- password_hash null and nothing references it, so the padded duplicate is discardable; keeping
-- the untrimmed one instead would leave the unique index on (brand_id, lower(email)) to fail this
-- migration. The surviving row is the one already reachable.
--
-- Deliberately not touching client_credential_token: a seeded account has never signed in, so it
-- has no tokens, and the FK would refuse the delete loudly if that assumption were ever wrong.

-- One row survives per (brand_id, lower(trim(email))): an unpadded one if there is one, and
-- otherwise the oldest. Written as a window rather than a correlated EXISTS on the trimmed value,
-- because two DIFFERENTLY padded copies of one address are possible — V45 grouped on
-- lower(c.email), which does not see ' a@x.com ' and '  a@x.com ' as the same key — and an EXISTS
-- would keep both, then let the UPDATE below hit the unique index.
delete from client_account
where id in (select id
             from (select id,
                          row_number() over (partition by brand_id, lower(trim(email))
                                             order by (email = trim(email)) desc, created_at asc) as rn
                   from client_account) ranked
             where rn > 1);

update client_account
set email = trim(email)
where email <> trim(email);
