-- `ghl_contact_id` is the canonical external client identity (invariant 7), and V16's
-- email index quietly outranked it.
--
-- V16 made `(brand_id, lower(email))` unique for every row carrying an email, which reads
-- as "one email, one person". That is only true when EvalOS has no better identifier. Two
-- distinct GHL contacts sharing an inbox — a shared attorney or office address, which
-- V16's own comment flagged as plausible — are two clients, and the index refused to let
-- the second one exist. What happened instead was worse than a refusal: intake fell back
-- to email, found the first client's row, overwrote it with the second client's details,
-- and attached the new case to the wrong client. The identity that should have decided it
-- was sitting in the payload the whole time.
--
-- So the email key is demoted to what it always was in the matching code: a **fallback**,
-- for rows that have no GHL id to be identified by. `CaseIntakeService.existingContact`
-- now refuses an email match that contradicts a supplied `ghl_contact_id`, and this index
-- is what lets the resulting insert land.
--
-- Strictly weaker than V16's, so it cannot fail on existing data: every row it constrains,
-- V16 constrained too.
DROP INDEX uq_contact_per_brand_email;

-- The race V16 closed stays closed. Two concurrent id-less deliveries for one email still
-- collide here, because both rows have a NULL `ghl_contact_id` and both are in scope.
-- Rows that carry a GHL id are out of scope precisely because they no longer need email to
-- tell them apart — `uq_contact_per_brand_ghl_id` already does, and it does it better.
--
-- A second thing this fixes, which was latent rather than reported: a contact who changes
-- their email in GHL to one already held by an id-less row used to fail the sync with a
-- constraint violation — a 5xx on a delivery that was doing exactly the right thing.
CREATE UNIQUE INDEX uq_contact_per_brand_email
    ON contact_snapshot (brand_id, lower(email))
    WHERE email IS NOT NULL AND ghl_contact_id IS NULL;
