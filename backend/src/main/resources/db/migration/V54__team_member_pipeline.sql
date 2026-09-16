-- Unit 44, slice B: a desk's pipelines become a set.
--
-- WHY MANY-TO-MANY, AND WHY IT IS NOT A PREFERENCE. `00d` §6.7: the target pipeline set is nine,
-- and one of them -- **Case Delivery** -- is a pipeline NO SINGLE PERSON OWNS.
-- `uq_team_member_pipeline` (one active member per pipeline) cannot express that, and
-- `PipelineScope.mine()` returning exactly one id cannot read it. "Many-to-many is the real shape
-- once Case Delivery exists, and pretending otherwise costs a second migration."
--
-- THIS IS AN AUTHORISATION CHANGE, NOT A SCHEMA CHANGE. §6.7 again: it "moves
-- PipelineScope.mine() from 'one id' to 'a set' across every desk that shares it -- an amendment
-- to Units 39 and 40's authorisation model, not just Unit 44's." Which is why it is its own slice
-- and its own commit.
--
-- A REAL FOREIGN KEY INTO `pipeline`, which `team_member.ghl_pipeline_id` never had. That column is
-- an ACCESS KEY holding an opaque GHL string, and `00d` C4 is what happens when it goes stale:
-- every SALES/MARKETING member was scoped to a pipeline that no longer existed after the sub-account
-- was replaced, and "the board draws zero columns WITH NO ERROR". A foreign key into the mirror
-- makes that state unrepresentable -- a pipeline has to be mirrored before anyone can be assigned to
-- it, and a GM assigning one picks from a list rather than pasting an id.

CREATE TABLE team_member_pipeline (
    team_member_id uuid        NOT NULL REFERENCES team_member (id) ON DELETE CASCADE,
    pipeline_id    uuid        NOT NULL REFERENCES pipeline (id),

    -- Who granted it and when. Not audit -- `audit_event` is still the record of the change -- but
    -- enough that a row can explain itself without a join to the trail.
    granted_at     timestamptz NOT NULL DEFAULT now(),
    granted_by     uuid REFERENCES team_member (id),

    PRIMARY KEY (team_member_id, pipeline_id)
);

-- The read that runs on every sign-in: which pipelines is this member on.
CREATE INDEX idx_team_member_pipeline_member ON team_member_pipeline (team_member_id);

-- The reverse read, for "who works this pipeline". NOT unique: a pipeline may have several owners,
-- or none, which is the whole point of the change.
CREATE INDEX idx_team_member_pipeline_pipeline ON team_member_pipeline (pipeline_id);

COMMENT ON TABLE team_member_pipeline IS
    'Which GHL pipelines a member may work (Unit 44b). Replaces team_member.ghl_pipeline_id as the '
    'authority. Many-to-many because Case Delivery has no single owner.';

-- BACKFILL from the column this replaces, so an existing environment keeps working across the
-- deploy. It matches on `pipeline.ghl_id`, so a member whose column holds a dead id -- `00d` C4's
-- state -- simply gets no row, which is the honest outcome: they had no working pipeline before
-- either, they just could not see that they had not.
INSERT INTO team_member_pipeline (team_member_id, pipeline_id)
SELECT m.id, p.id
  FROM team_member m
  JOIN pipeline p ON p.ghl_id = m.ghl_pipeline_id AND p.brand_id = m.brand_id
 WHERE m.ghl_pipeline_id IS NOT NULL
ON CONFLICT DO NOTHING;

-- `team_member.ghl_pipeline_id` AND `uq_team_member_pipeline` SURVIVE THIS MIGRATION, and that is a
-- constraint rather than a choice. Two local seeds (`V908`, `V909`) write that column, and they run
-- AFTER this script -- seed files are numbered 900+, and `MigrationTreeTest` fails the build on any
-- `db/migration` script in that range, so a DROP has nowhere to sit. Editing an applied seed is a
-- checksum mismatch that refuses the boot. The same trap `ghl_funnel_cache` is stuck in.
--
-- Nothing READS the column from this point: `PipelineScope`, `ScopePredicate` and the principal all
-- take the join table, and `PipelineAssignmentService` writes only here. Drop it in the next change
-- that rebaselines the seed tree.
COMMENT ON COLUMN team_member.ghl_pipeline_id IS
    'VESTIGIAL as of Unit 44b. team_member_pipeline is the authority; nothing reads this. Kept only '
    'because local seeds V908/V909 write it and a DROP cannot be ordered after them.';
