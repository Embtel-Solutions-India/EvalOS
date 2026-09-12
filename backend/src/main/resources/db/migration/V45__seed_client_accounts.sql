-- Unit 42 — every client EvalOS already knows gets an account with no password.
--
-- They land in NO_PASSWORD on their first sign-in attempt and set a password from the emailed
-- link. Without this, every existing client is told "we couldn't find that email", which is false.
--
-- **Two things about contact_snapshot this has to survive, and neither is obvious:**
--
--   1. `email` is NULLABLE. A contact that arrived without one is skipped — there is nothing to
--      sign in with, and a row with a null login can never be used.
--   2. `email` is NOT UNIQUE. ContactSnapshot's own javadoc warns that "contacts sharing an email
--      would otherwise let the second silently take over". So DISTINCT ON is load-bearing, not
--      tidiness: a naive insert violates client_account_brand_email_key and fails this migration.
--      Where two snapshots share an address they collapse into ONE account, which is correct —
--      it is one person with one inbox, and their cases are found through the party link rather
--      than through the snapshot row.
--
-- **ghl_contact_id IS copied forward.** An earlier draft seeded it NULL, reasoning that IE's GHL
-- sub-account was replaced on 2026-09-11 (WY6bW2xUCI8Tz8gw7aLJ, fresh, no contact migration) so
-- every id EvalOS holds names a contact that no longer exists there. That is true of the column's
-- GHL job and irrelevant to its EvalOS job: PortalCaseService.authorized() resolves a party token
-- to its cases THROUGH this id and fails closed when it is null. Seeding null would let every
-- existing client sign in and then see no cases at all. Invoices and meetings answering empty is
-- the correct, contained degradation; a blank case list is not.

insert into client_account (id, brand_id, email, password_hash, ghl_contact_id,
                            first_name, last_name, phone, created_at)
select distinct on (c.brand_id, lower(c.email))
       gen_random_uuid(),
       c.brand_id,
       c.email,
       null,
       c.ghl_contact_id,
       split_part(c.full_name, ' ', 1),
       nullif(substring(c.full_name from position(' ' in c.full_name) + 1), c.full_name),
       c.phone,
       now()
from contact_snapshot c
where c.email is not null
  and length(trim(c.email)) > 0
order by c.brand_id, lower(c.email), c.created_at asc;
