-- Unit 44, slice D: the opportunity stops being a droppable cache and becomes an EvalOS row.
--
-- THIS REPLACES `ghl_opportunity_cache` (V40), and `00d` §6.5 gives five reasons it cannot be
-- salvaged rather than migrated. Four are schema: GHL's id as the primary key, `ghl_contact_id`
-- and `stage_id` NOT NULL, and no `brand_id` at all. The fifth is the deep one and is the whole
-- reason this table looks different:
--
--   "Its only write path is delete-all-then-insert-all per pipeline. Every row's identity is
--    destroyed on every board refresh, so nothing can hold a foreign key into it and any row
--    carrying local state -- a sync_state, an outbox reference, a portal-born row with no ghl_id
--    -- dies. The current write model is 'truth lives elsewhere, replace everything'; the
--    mirror's is the exact opposite."
--
-- TWO NAMES PER ROW (`00c` §2a), and here is where it finally earns itself. A portal-born
-- opportunity EXISTS BEFORE GHL HAS SEEN IT: the client picks a service, EvalOS opens the row, and
-- GHL is called. With GHL's id as the primary key that row cannot exist, and when GHL later
-- assigns one the row's identity would CHANGE, taking every foreign key with it. So `id` is
-- EvalOS's, stable from creation, and `ghl_id` is the join key for sync and is null until GHL
-- answers.
--
-- `id` IS ALSO THE CORRELATION KEY (`00d` §6.1). It is written into a GHL custom field on create,
-- so that a retry after a timeout can ask "did my create actually land?" instead of guessing. That
-- is the single biggest sequencing correction to `00c`: at-least-once delivery over a
-- non-idempotent create is exactly how one opportunity becomes two, and no amount of outbox
-- discipline fixes it without a key GHL will hand back.

CREATE TABLE opportunity (
    -- EvalOS's own key. Never GHL's -- see the two-names note above.
    id                    uuid        PRIMARY KEY,

    brand_id              uuid        NOT NULL REFERENCES brand (id),

    -- GHL's id, verbatim, NULL until GHL has seen this row. Unique per brand where present:
    -- `NULLS NOT DISTINCT` is deliberately NOT used, because many local-only rows must be able to
    -- coexist while every GHL id appears at most once.
    ghl_id                text,

    -- The client. TEXT AND NOT A FOREIGN KEY, and only until slice 44c: there is no `contact`
    -- table yet, and `client_account` is nullable and non-unique on `ghl_contact_id` so it cannot
    -- carry one either. Same shape `meeting.ghl_contact_id` already uses, and 44c is where both
    -- become a real reference.
    ghl_contact_id        text,

    -- A REAL FOREIGN KEY, which is the point of mirroring rather than caching. The sync only ever
    -- walks pipelines EvalOS has already mirrored (44a), so this is always satisfiable.
    pipeline_id           uuid        NOT NULL REFERENCES pipeline (id),

    -- The stage, as GHL's id. DELIBERATELY NOT A FOREIGN KEY INTO `pipeline_stage`, and that is a
    -- decision rather than an omission: the two mirrors are refreshed by two sweeps on two
    -- schedules, so a stage created in GHL minutes ago is a stage this table would have to reject
    -- an opportunity for. A sync that fails because a DIFFERENT sweep is behind is a coupling that
    -- turns one late job into two broken screens.
    --
    -- The id still resolves -- `pipeline_stage.ghl_id` holds the same string, which is `00c` §2b's
    -- whole argument: same ids on both sides, so the lookup is an equality check and not a
    -- translation. A stage the mirror has not seen draws its column under the raw id, as it did
    -- before.
    ghl_stage_id          text,

    name                  text,
    amount                numeric(12, 2),

    -- GHL's own word: open / won / lost / abandoned. Not an EvalOS vocabulary, and this table must
    -- never grow a lifecycle of its own -- `evalos_case` is the only lifecycle (invariant 8).
    status                text,

    -- Where the lead came from, as GHL holds it. Free text, often null: the GM overview reports
    -- the blank share as a finding rather than hiding it.
    source                text,

    -- The GHL user the deal is assigned to. Read-only here for now, and GHL OWNS IT
    -- (`00d` §6.2): a round-robin automation reassigning a deal is not a conflict to be undone,
    -- and reverting it would break the one thing `00b` keeps GHL for. Nothing links a GHL user to
    -- an EvalOS team_member, so this is stored and not resolved.
    ghl_assigned_to       text,

    -- GHL's own timestamps. `ghl_updated_at` is the comparison Unit 45's conflict policy needs --
    -- and `00d` §6.2 is explicit that a NULL here is an explicit CONFLICT, never "EvalOS is
    -- newer", because the policy would otherwise degrade silently into "EvalOS always wins".
    ghl_created_at        timestamptz,
    ghl_updated_at        timestamptz,
    last_status_change_at timestamptz,
    last_stage_change_at  timestamptz,

    -- When EvalOS last changed this row itself, for the same comparison from the other side.
    local_updated_at      timestamptz,

    -- When the sync last confirmed it, and when GHL stopped returning it. Soft delete, for the
    -- same reason as `pipeline`: a row destroyed is a row nothing can reference, and an
    -- opportunity that disappeared is a fact Unit 45's drift report is built out of.
    synced_at             timestamptz,
    missing_since         timestamptz,

    created_at            timestamptz NOT NULL DEFAULT now()
);

-- One GHL id per brand, and only where there IS one. A plain UNIQUE would collapse every
-- local-only row into a single allowed NULL on some engines and is the obvious wrong reading here.
CREATE UNIQUE INDEX uq_opportunity_per_brand_ghl_id
    ON opportunity (brand_id, ghl_id) WHERE ghl_id IS NOT NULL;

-- The board read: every deal on a set of pipelines.
CREATE INDEX idx_opportunity_pipeline ON opportunity (pipeline_id);

-- The retry read (`00d` §6.1). GHL's opportunity search offers NO filter on a custom field --
-- verified against the API, and it is a correction to that section's proposed mechanism -- so a
-- retry-after-timeout asks GHL for the CONTACT's opportunities and matches the correlation value
-- locally. This index is the EvalOS half of the same question.
CREATE INDEX idx_opportunity_contact ON opportunity (brand_id, ghl_contact_id);

COMMENT ON TABLE opportunity IS
    'Mirror of a GHL opportunity (Unit 44d). id is EvalOS''s and is the correlation key; ghl_id is '
    'null until GHL has seen the row. Never deleted.';
