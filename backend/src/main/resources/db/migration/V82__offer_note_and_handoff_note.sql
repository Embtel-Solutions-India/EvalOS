-- D69: every offer carries a note to its expert. Nullable only for offers made before this
-- migration; CaseLifecycleService refuses a new offer without one.
ALTER TABLE expert_case_offer ADD COLUMN note text;

-- D70: the note a salesperson writes when they win a deal in EvalOS, for the production team.
-- An ordinary deal note (synced to the GHL contact like any other), flagged so the case finds it.
ALTER TABLE opportunity_note ADD COLUMN handoff boolean NOT NULL DEFAULT false;
CREATE INDEX opportunity_note_handoff_idx ON opportunity_note (brand_id, ghl_opportunity_id) WHERE handoff;
