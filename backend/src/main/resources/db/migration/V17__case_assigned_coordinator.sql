-- The Coordinator's assignment slot, which the design assumed and the schema never had.
--
-- PROJECT_COORDINATOR is Tier.SELF, but evalos_case's only assignee column was
-- assigned_cm, which holds a Case Manager. So a Coordinator's scoped read matched no
-- row: their board was empty and the four transitions the spec makes them the actor for
-- (docs-complete, send-to-client, deliver, close) answered 403 on a case they were in
-- fact responsible for. This is the column the Self axis needed.
--
-- Fixing the read half needed the other half too: ScopePredicate.Fields now takes a
-- *set* of assignment attributes and a SELF caller matches when any of them names them.
-- One case is one pipeline, and the people working it hold different slots.
ALTER TABLE evalos_case
    ADD COLUMN assigned_coordinator uuid REFERENCES team_member (id);

-- Mirrors idx_case_scope_stage, which covers (brand_id, team_id, assigned_cm,
-- current_stage) for the CM's board. The Coordinator's board reads the same shape
-- through the other column, so it needs its own index or it seq-scans the brand.
CREATE INDEX idx_case_scope_coordinator ON evalos_case (brand_id, assigned_coordinator, current_stage);
