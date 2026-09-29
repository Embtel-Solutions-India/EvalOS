-- Unit 60 (D55): a staff member's GHL user, so EvalOS can ask GHL about THEIR calendar —
-- blocked-off time and per-person free slots are keyed by GHL user id, not by anything EvalOS holds.
--
-- V29 added this column and V30 dropped it, but only because the SALES_EXECUTIVE role it served was
-- removed; nothing about the mapping itself was refused. It comes back with V29's rule intact.
--
-- Filled by the REFERENCE_MIRROR pass on an email match (ReferenceMirrorService.absorbUsers), never
-- overwritten once set, so a hand correction sticks.
ALTER TABLE team_member
    ADD COLUMN ghl_user_id text;

-- One GHL user is one person: two staff rows claiming the same one would split that person's diary
-- across two logins. A unique index rather than a service check, for V29's reason.
CREATE UNIQUE INDEX idx_team_member_ghl_user ON team_member (ghl_user_id)
    WHERE ghl_user_id IS NOT NULL;
