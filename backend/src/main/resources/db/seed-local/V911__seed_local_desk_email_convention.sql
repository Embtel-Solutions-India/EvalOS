-- The six desk logins get one naming convention: sales-1..3 and bde-1..3.
--
-- WHY A NEW SCRIPT RATHER THAN AN EDIT. V908 seeds these people as `sales.attorney`,
-- `sales.employer`, `sales.individual`, `marketing.individual` and `marketing.attorney`; V909 adds
-- `marketing.bde3`. Both are applied, and editing an applied migration is a checksum mismatch that
-- refuses the boot -- the same reason V909 repointed pipelines instead of rewriting V908.
--
-- WHY IT WAS NEEDED AT ALL. The local database had been renamed BY HAND to the convention the
-- business actually uses, so a laptop that had been running for a while disagreed with a laptop
-- set up from scratch: the first had `sales-1.ie@evalos.local` and the second had
-- `sales.attorney.ie@evalos.local`, and only one of them matched what anybody was told to log in
-- with. A hand-edit that never became a seed is a fresh checkout that cannot follow the
-- instructions.
--
-- ADDRESSED BY ID, NOT BY EMAIL. The ids are V908's and V909's own and cannot drift; matching on
-- the old address would silently do nothing on a database where somebody had already renamed it,
-- which is exactly the state this script exists to converge.
--
-- IDEMPOTENT by construction: setting an address to what it already is changes nothing, and the
-- unique index on (brand, email) is what would catch a collision rather than this script assuming
-- there is none.
--
-- The password is unchanged and is still the same throwaway as every other seeded login:
-- DevPassw0rd!  It is a LOCAL seed and must never be what a real environment holds -- see
-- `docs/seed-desks.sql` for the production script, which takes its own hash.

-- --- Sales: three people, three service pipelines ------------------------------------------
UPDATE team_member SET email = 'sales-1.ie@evalos.local', display_name = 'Sales 1'
 WHERE id = 'aaaaaaaa-0000-0000-0000-000000000010';

UPDATE team_member SET email = 'sales-2.ie@evalos.local', display_name = 'Sales 2'
 WHERE id = 'aaaaaaaa-0000-0000-0000-000000000011';

UPDATE team_member SET email = 'sales-3.ie@evalos.local', display_name = 'Sales 3'
 WHERE id = 'aaaaaaaa-0000-0000-0000-000000000012';

-- --- BDE: three people, the three BDE pipelines ---------------------------------------------
--
-- MARKETING rather than SALES, and the distinction is a real one in this system rather than a
-- label: a MARKETING member opens a LEAD (`MarketingLeadService.openLead`, an upsert on contact +
-- pipeline, so a repeat enquiry updates the open lead) and a SALES member opens a DEAL
-- (`SalesDeskService.createDeal`, a true create) and gets meetings, follow-ups and close. Business
-- development is the first of those. If that reading is wrong the fix is this column and nothing
-- else -- no pipeline, no access model and no screen depends on it.
UPDATE team_member SET email = 'bde-1.ie@evalos.local', display_name = 'BDE 1'
 WHERE id = 'aaaaaaaa-0000-0000-0000-000000000013';

UPDATE team_member SET email = 'bde-2.ie@evalos.local', display_name = 'BDE 2'
 WHERE id = 'aaaaaaaa-0000-0000-0000-000000000014';

UPDATE team_member SET email = 'bde-3.ie@evalos.local', display_name = 'BDE 3'
 WHERE email = 'marketing.bde3.ie@evalos.local';
