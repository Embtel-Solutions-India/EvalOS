-- Contact identity, which V15 assumed and never actually had.
--
-- V15 added a unique index on evalos_case (brand_id, contact_id, service_type) and its
-- comment claims the duplicate-case race "cannot race". That is only true once a
-- contact_snapshot row already exists. contact_snapshot had no unique key at all, so
-- for a contact EvalOS had never seen, two concurrent contact.created deliveries each
-- inserted their own snapshot, got different contact_ids, and both sailed through V15's
-- index — two case codes, two checklists, two NEW_LEAD alerts for one piece of work.
-- V15 is applied and is never edited (invariant 9), so this migration is where the
-- record is corrected: V15 closed one half of the race, this closes the other.
--
-- Both indexes are partial, because both columns are nullable and a row that names
-- neither is not a duplicate of anything.

-- The GHL id is the real identity: one snapshot per GHL contact per brand.
CREATE UNIQUE INDEX uq_contact_per_brand_ghl_id
    ON contact_snapshot (brand_id, ghl_contact_id)
    WHERE ghl_contact_id IS NOT NULL;

-- Email is the fallback identity, and it has to be constrained too: the payload does
-- not guarantee a GHL id (it carries no @NotBlank), so CaseIntakeService matches on
-- email when the id is absent — which means two concurrent id-less deliveries could
-- both insert. `lower(email)` because the lookup is findByBrandIdAndEmailIgnoreCase;
-- a case-only difference must not create a second person.
--
-- This enforces an assumption the application already makes rather than adding a new
-- one. The assumption is worth revisiting: an ATTORNEY contact may be a firm, and a
-- shared office inbox across several applicants would now be refused rather than
-- silently merged. Refusing is the safer of the two — a wrong merge attaches a case to
-- the wrong person — but if it ever fires in practice, the fix is a real contact key
-- from GHL, not dropping the constraint.
CREATE UNIQUE INDEX uq_contact_per_brand_email
    ON contact_snapshot (brand_id, lower(email))
    WHERE email IS NOT NULL;
