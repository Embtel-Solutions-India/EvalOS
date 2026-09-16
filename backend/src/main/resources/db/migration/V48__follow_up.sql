-- Follow-ups EvalOS has set, mirrored from GHL's own response.
--
-- THE SAME REASONING AS V47, AND THE SAME EVIDENCE. A follow-up is a GHL task on the deal's
-- contact. EvalOS created them and kept nothing, so the sales desk could set a reminder for
-- Thursday and no EvalOS screen could ever show it again — the audit's "write-only desk" finding,
-- of which this is the other half.
--
-- WHY NOT READ THEM BACK. Three routes, and two are closed:
--
--   GET /contacts/{id}/tasks                 granted, but PER CONTACT: a desk-wide list is one
--                                            call per contact, which the 100-per-10s budget and
--                                            a screen's patience both refuse
--   /opportunities/search?getTasks=true      granted, one call per pipeline — but `tasks` came
--                                            back empty across all 100 rows of the busiest
--                                            pipeline, so its wire shape is unverified
--   the booking/creation response            verified, already parsed, and previously discarded
--
-- The second stays the right long-term read and arrives with Unit 45's sync. Binding to it today
-- would be a guess that compiles.
--
-- WHAT THIS IS NOT. It is not the authority. A task completed, edited or deleted in GHL is not
-- reflected here until the sync reconciles, which is what `synced_at` exists to bound. Nothing in
-- EvalOS may treat this table as the reason a follow-up is or is not done.
--
-- WHY `completed` IS HELD AT ALL, given that caveat: the desk's own "mark done" writes to GHL and
-- then here, so the common case is correct immediately. A row that disagrees with GHL is a stale
-- mirror, not a lost task.

CREATE TABLE follow_up (
    id                  uuid PRIMARY KEY,
    brand_id            uuid        NOT NULL REFERENCES brand (id),

    -- GHL's task id. Unique per brand, not globally: two brands will hold two GHL locations the
    -- day Unit 25 lands, and ids are only unique within one.
    ghl_task_id         text        NOT NULL,

    -- The deal this follow-up is about. Text, not a foreign key: the opportunity lives in GHL and
    -- EvalOS has no opportunity table until Unit 44.
    ghl_opportunity_id  text        NOT NULL,
    ghl_contact_id      text        NOT NULL,

    -- The ACCESS KEY. `PipelineScope` scopes a desk by pipeline, so a follow-up list needs the
    -- same predicate without a second call to GHL to ask which pipeline the deal is on. A
    -- snapshot: a deal moved to another pipeline leaves its follow-ups scoped to the old desk
    -- until the sync reconciles, which is the trade this column exists to make.
    ghl_pipeline_id     text        NOT NULL,

    title               text        NOT NULL,
    body                text,
    due_at              timestamptz NOT NULL,
    completed           boolean     NOT NULL DEFAULT false,
    completed_at        timestamptz,

    -- Who set it, for the audit trail and for "mine". Not an access key — see the repository.
    set_by              uuid        NOT NULL REFERENCES team_member (id),

    created_at          timestamptz NOT NULL DEFAULT now(),
    synced_at           timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT uq_follow_up_per_brand_task UNIQUE (brand_id, ghl_task_id),
    -- A completed follow-up must say when. The pair going out of step is how "done" becomes
    -- unauditable, and a CHECK is cheaper than remembering.
    CONSTRAINT follow_up_completed_has_a_time
        CHECK ((completed AND completed_at IS NOT NULL) OR (NOT completed AND completed_at IS NULL))
);

-- The screen's two reads: a desk's open follow-ups by date, and one deal's.
-- `brand_id` leads both because every scoped query filters by it first.
CREATE INDEX idx_follow_up_pipeline_due ON follow_up (brand_id, ghl_pipeline_id, completed, due_at);
CREATE INDEX idx_follow_up_opportunity ON follow_up (brand_id, ghl_opportunity_id);
