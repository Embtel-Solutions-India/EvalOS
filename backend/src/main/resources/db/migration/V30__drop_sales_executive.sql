-- Reverses V29: the sales desk and the SALES_EXECUTIVE role are removed.
--
-- V29 is not deleted and must not be. It is in every environment's `flyway_schema_history` and
-- editing or removing an applied migration is a checksum failure at the next boot — the history
-- is append-only for the same reason the audit trail is. So the reversal is a migration of its
-- own, and it runs in the order the dependencies require: rows first, then the index and column,
-- then the two constraints that would refuse the rows.

-- **The rows go before the CHECK comes back**, or restoring it fails on any database that ever
-- ran the V906 seed (a laptop, `evalos_test`) with a row the new constraint forbids.
--
-- A hard DELETE rather than `active = false`, because this is not a person leaving: the role
-- itself no longer exists, and a deactivated row carrying a role the enum cannot express is a row
-- that breaks `Role.valueOf` the moment anything reads it. `team_member.id` is referenced by
-- `reports_to` and by assignment columns on `evalos_case`, but a SALES_EXECUTIVE is assigned no
-- case and reports to nobody by construction (V906's own comment), so nothing points at these
-- rows. If a deployment somehow has one that is referenced, this statement fails loudly on the
-- foreign key — which is the correct outcome, not something to force past with a cascade.
DELETE FROM team_member WHERE role = 'SALES_EXECUTIVE';

-- The mapping to a GHL user. Only the sales board read it, and nothing writes it now that
-- `PUT /api/team-members/{id}/ghl-user` is gone. The index goes with the column automatically,
-- but naming it here keeps the reversal readable beside V29, which created both.
DROP INDEX IF EXISTS idx_team_member_ghl_user;

ALTER TABLE team_member DROP COLUMN IF EXISTS ghl_user_id;

-- Back to V3's list. The CHECK is the writer the enum cannot reach — a seed script, a hand-run
-- UPDATE — so leaving 'SALES_EXECUTIVE' in it would let a row exist that `Role.valueOf` throws on.
ALTER TABLE team_member
    DROP CONSTRAINT team_member_role_valid,
    ADD CONSTRAINT team_member_role_valid CHECK (role IN (
        'GM', 'BRAND_MANAGER', 'PROJECT_MANAGER',
        'PROJECT_COORDINATOR', 'CASE_MANAGER', 'EXPERT_NETWORK_MANAGER'
    ));

-- And back to V3's brand rule: the GM is once again the only role that may have no brand, where
-- NULL means "every brand" (`Tier.ALL`). V29 widened this for a role whose NULL meant the
-- opposite — "no brand", `Tier.SELF`, matching nothing — and that second reading leaves with it.
ALTER TABLE team_member
    DROP CONSTRAINT team_member_brand_required,
    ADD CONSTRAINT team_member_brand_required CHECK (
        role = 'GM' OR brand_id IS NOT NULL
    );
