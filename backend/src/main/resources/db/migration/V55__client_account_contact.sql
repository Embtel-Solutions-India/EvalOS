-- Unit 44, slice C: one person stops being two rows with no link.
--
-- THE PROBLEM, as `.claude/data-model.md` states it: "`client_account` and `contact_snapshot` are
-- not joined. Both can hold a `ghl_contact_id` and nothing links them. A case reaches a contact
-- snapshot; it does not reach the account." So the portal knows who signed in and the production
-- side knows whose case it is, and neither can answer the other's question.
--
-- WHY A JOIN AND NOT A MERGE INTO ONE `contact` TABLE. `00c` §2's sketch names a `contact` table and
-- `00d` §11 asks for the merge -- and the data model's own wording is "merged with OR JOINED TO".
-- The merge is blocked, and by the same trap `ghl_funnel_cache` is in: TWO seeds write
-- `contact_snapshot` (`V905` local, `V951` testprod), both numbered 900+, both running after every
-- `db/migration` script -- and `MigrationTreeTest` forbids a migration in that range while editing
-- an applied seed is a checksum mismatch that refuses the boot. A rename or a drop has nowhere to
-- sit. Do it in the change that rebaselines the seed tree; `contact_snapshot` IS the mirror's
-- contact table until then, and nothing about that is wrong except its name.
--
-- WHAT IS ALSO WRONG AND IS FIXED HERE: `client_account.ghl_contact_id` was nullable AND NOT
-- UNIQUE, so decision D6 ("one GHL Contact, many opportunities") was not enforced by the schema and
-- nothing stopped two accounts naming one contact.

ALTER TABLE client_account
    ADD COLUMN contact_id uuid REFERENCES contact_snapshot (id);

COMMENT ON COLUMN client_account.contact_id IS
    'The CRM row for this person (Unit 44c). Null for an account whose contact is not known here '
    'yet -- which is legal: a client may sign up before any case exists.';

-- The link, for every account that can already be matched. On `ghl_contact_id` within the brand,
-- because that is the identity GHL owns (invariant 7) and the only one both sides agree on. An
-- account with no GHL id, or one naming a contact this brand has never seen, is simply left
-- unlinked -- a wrong link is far worse than a missing one, since it would attach somebody's cases
-- to the wrong sign-in.
UPDATE client_account a
   SET contact_id = c.id
  FROM contact_snapshot c
 WHERE c.brand_id = a.brand_id
   AND c.ghl_contact_id = a.ghl_contact_id
   AND a.ghl_contact_id IS NOT NULL
   AND a.contact_id IS NULL;

-- D6, enforced rather than described.
--
-- PARTIAL, `where ghl_contact_id is not null`: a post-cutover client may legitimately have no GHL
-- contact yet -- `00c` §1 records that IE's sub-account was replaced with no contact migration, so
-- every id EvalOS held named a contact that no longer existed -- and many such accounts must be
-- able to coexist. What must not coexist is two accounts claiming the SAME contact, which is one
-- person with two sign-ins and two views of their own cases.
CREATE UNIQUE INDEX uq_client_account_per_brand_ghl_contact
    ON client_account (brand_id, ghl_contact_id) WHERE ghl_contact_id IS NOT NULL;

-- The same rule one level up, so a duplicate cannot arrive through the link either.
CREATE UNIQUE INDEX uq_client_account_per_contact
    ON client_account (contact_id) WHERE contact_id IS NOT NULL;

-- The reverse read: which account belongs to this contact. Used when a case wants to reach the
-- person's sign-in, which is the direction that did not exist at all before.
CREATE INDEX idx_client_account_contact ON client_account (contact_id);
