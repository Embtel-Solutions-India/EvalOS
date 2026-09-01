-- Unit 29: the sales executive, and the GHL user they are.
--
-- TWO constraints change here, not one, and the second is the one that would have failed at
-- insert time rather than at boot: V3's `team_member_brand_required` reads
-- `role = 'GM' OR brand_id IS NOT NULL`, so a SALES_EXECUTIVE with the NULL brand_id the design
-- requires would have been rejected by the database with the enum, the entity and the tests all
-- perfectly happy.

ALTER TABLE team_member
    DROP CONSTRAINT team_member_role_valid,
    ADD CONSTRAINT team_member_role_valid CHECK (role IN (
        'GM', 'BRAND_MANAGER', 'PROJECT_MANAGER',
        'PROJECT_COORDINATOR', 'CASE_MANAGER', 'EXPERT_NETWORK_MANAGER',
        'SALES_EXECUTIVE'
    ));

-- V3's comment on brand_id said "NULL means all brands. The check below keeps that to the GM
-- alone, so a mis-seeded row can never silently become cross-brand." That reasoning still holds
-- and this is not a hole in it: NULL brand_id means "no brand" and only *combined with*
-- `Tier.ALL` does it mean all brands. SALES_EXECUTIVE is `Tier.SELF`, and ScopePredicate turns a
-- brand-locked caller with no brand into `disjunction()` — it matches nothing, not everything.
-- So this row reads zero EvalOS rows rather than all of them, which is the opposite failure mode
-- from the one V3 was guarding.
--
-- Why it has no brand at all: its entire data surface is the one GHL location named by the global
-- `evalos.ghl.location-id`, which EvalOS cannot attribute to a brand. Unit 25 puts that location
-- on `brand`, and when it does this role gets a real brand_id and should be removed from this
-- check again.
ALTER TABLE team_member
    DROP CONSTRAINT team_member_brand_required,
    ADD CONSTRAINT team_member_brand_required CHECK (
        role IN ('GM', 'SALES_EXECUTIVE') OR brand_id IS NOT NULL
    );

-- The GHL user this staff member *is*, so their board can be filtered to their own deals.
--
-- Nullable, and an unmapped sales executive must therefore see NOTHING rather than everything —
-- enforced in `SalesBoardService`, because failing closed is the only reason this column exists
-- instead of matching on login email. (Email matching was rejected precisely because whether it
-- fails open or closed depends on how somebody happens to write the comparison.)
--
-- text, not uuid: GHL ids are 20-character opaque strings and are not UUIDs. The same fact is why
-- audit rows about an opportunity carry a derived object_id — see GhlSalesClient.
ALTER TABLE team_member
    ADD COLUMN ghl_user_id text;

-- A GHL user is one person, so two staff rows must not claim to be the same one: the second
-- mapping would silently split one salesperson's pipeline across two logins, each seeing all of
-- it and neither wrong on its own terms. A unique index rather than a service check, because a
-- service check is the thing somebody forgets to call from the second write path.
CREATE UNIQUE INDEX idx_team_member_ghl_user ON team_member (ghl_user_id)
    WHERE ghl_user_id IS NOT NULL;
