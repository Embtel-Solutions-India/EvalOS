-- Unit 44, slice A: GHL's pipelines and stages become EvalOS rows.
--
-- THE FIRST BRICK OF THE MIRROR (`00c` §2), and the one with no authorisation risk: GHL owns
-- these records outright (`00d` §6.2 — "`pipeline.*`, `pipeline_stage.*`: GHL, read-only; GHL
-- always wins; EvalOS never pushes"), so this table can only ever be behind, never in conflict.
--
-- WHY MIRROR SOMETHING EVALOS COULD JUST ASK FOR. Today every board read calls
-- `GET /opportunities/pipelines` to turn a stage id into a stage name, because a stage id from
-- GHL is opaque on its own. That is an unpaginated network call on a screen render, against a
-- 100-requests-per-10-seconds-per-location budget shared with every other desk. With the stages
-- as rows the id resolves locally, and `00c` §2b's argument lands: **the id stops being opaque
-- without becoming a mapping**, so a stage comparison between the two systems is `a == b` rather
-- than a translation that can itself be wrong.
--
-- IDS ARE GHL'S, VERBATIM (`00c` §2, `43` §6c). `ghl_id` is GHL's own string, stored as given.
-- EvalOS mints its own `id` beside it because a row must be able to exist before GHL has seen it
-- and must not change identity when GHL answers (`00c` §2a) -- that matters for `opportunity` in
-- a later slice, and the shape is kept uniform here so the two tables read the same way.
--
-- NOT A CACHE, AND THE DIFFERENCE IS THE WRITE PATH. `ghl_opportunity_cache` (V40) is refilled by
-- delete-all-then-insert-all per pipeline, which destroys every row's identity on every refresh --
-- `00d` §6.5 calls that the deepest of the five reasons it is unsalvageable. These tables are
-- upserted in place and **nothing is ever deleted**: a pipeline GHL stops returning is stamped
-- `missing_since` and kept, because `purpose` is EvalOS's own judgement about that pipeline and a
-- pipeline hidden for an afternoon must not lose it. It also means a foreign key into these rows
-- is safe, which is what the rest of Unit 44 needs.

CREATE TABLE pipeline (
    id            uuid        PRIMARY KEY,

    -- WHICH BRAND, given that `evalos.ghl.location-id` names one sub-account with no link to a
    -- brand (invariant 1's one stated exception). The answer is `evalos.ghl.sales-brand`, which
    -- Unit 36 added precisely to NARROW that exception: it names the brand that owns the location,
    -- and assignment of a pipeline-scoped role to any other brand's member is a 400.
    --
    -- So this column is honest today and becomes plural at Unit 25, when the location moves onto
    -- `brand`. A mirror with no `brand_id` would be `ghl_opportunity_cache`'s mistake repeated --
    -- V40 has none, deliberately, which made the single-brand ceiling load-bearing SCHEMA rather
    -- than configuration (`00d` §6.5, reason 4).
    brand_id      uuid        NOT NULL REFERENCES brand (id),

    -- GHL's pipeline id, verbatim. Unique per brand rather than globally, for the same reason
    -- `meeting.ghl_appointment_id` is: two brands will one day hold two GHL locations, and ids are
    -- only unique within one.
    ghl_id        text        NOT NULL,

    -- GHL's own name and display order. Mirrored rather than interpreted -- a rename in GHL is a
    -- rename here on the next sweep, and nothing in EvalOS keys off the text.
    name          text        NOT NULL,
    position      integer     NOT NULL,

    -- WHAT THIS PIPELINE IS FOR, and it is the one column on this table GHL does not own.
    --
    -- `00d` §6.7 is the decision: config carried three `*-pipeline-name` properties and
    -- `application.yml` explicitly forbade a list ("THREE NAMES, NOT A LIST") -- right at three
    -- pipelines, wrong at nine. The mirrored table IS the list, and `purpose` is how EvalOS says
    -- which of them means what without a property per pipeline.
    --
    -- UNASSIGNED IS THE DEFAULT AND IT IS THE SAFE ONE. A pipeline that appears in GHL tomorrow
    -- arrives meaning nothing to EvalOS, which is correct: guessing a purpose from a name is how a
    -- rename silently reroutes a client's request. A GM sets it.
    --
    -- DELIVERY is here because the target set includes a Case Delivery pipeline that no single
    -- person owns. `00d` §6.7 is explicit about what mirroring it must NOT do: a GHL pipeline whose
    -- stages track delivery is structurally a second case board, and invariant 8 plus `43` §9
    -- forbid that shape. It is mirrored for reporting and hand-off visibility; `evalos_case` stays
    -- the only lifecycle.
    purpose       text        NOT NULL DEFAULT 'UNASSIGNED'
                  CHECK (purpose IN ('MARKETING', 'SALES', 'DELIVERY', 'INTAKE', 'UNASSIGNED')),

    -- When GHL last confirmed this row, and when it stopped returning it.
    --
    -- `missing_since` is a soft delete and it is load-bearing rather than cautious: `purpose` is
    -- EvalOS's judgement, and a pipeline archived in GHL for an afternoon that came back as a new
    -- row would come back UNASSIGNED. A row stamped here is also the evidence Unit 45's drift
    -- report needs -- a pipeline that vanished is a fact about GHL, not an absence of one.
    synced_at     timestamptz NOT NULL,
    missing_since timestamptz,

    created_at    timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT uq_pipeline_per_brand_ghl_id UNIQUE (brand_id, ghl_id)
);

COMMENT ON TABLE pipeline IS
    'Mirror of a GHL pipeline (Unit 44a). GHL owns every column except purpose. Never deleted.';

CREATE TABLE pipeline_stage (
    id            uuid        PRIMARY KEY,
    brand_id      uuid        NOT NULL REFERENCES brand (id),

    -- A real foreign key, which is the point of mirroring rather than caching: `ghl_opportunity_cache`
    -- could never carry one, because its rows are destroyed and recreated on every refresh.
    pipeline_id   uuid        NOT NULL REFERENCES pipeline (id),

    ghl_id        text        NOT NULL,
    name          text        NOT NULL,
    position      integer     NOT NULL,

    synced_at     timestamptz NOT NULL,
    missing_since timestamptz,
    created_at    timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT uq_pipeline_stage_per_brand_ghl_id UNIQUE (brand_id, ghl_id)
);

-- THE SECONDARY NATURAL KEY (`00d` §6.7), and the reason it is an index and not a constraint.
--
-- "GHL stage ids are not stable across a delete-and-recreate, and IE has now demonstrated it will
-- recreate things. Without it a recreated pipeline reads as every opportunity in it having
-- drifted." So when a stage arrives whose `ghl_id` is unknown but whose (pipeline, position, name)
-- matches a row EvalOS already has, the sweep REPOINTS that row rather than inserting a second --
-- the row keeps its `id`, so every foreign key into it survives the recreate.
--
-- NOT UNIQUE: GHL does not promise distinct positions or distinct names within a pipeline, and a
-- constraint EvalOS cannot enforce upstream would turn somebody else's duplicate into a failed
-- sweep. The matching rule tolerates a tie by leaving both rows alone and letting Unit 45's drift
-- report say so -- a wrong repoint is worse than a reported ambiguity.
CREATE INDEX idx_pipeline_stage_natural_key ON pipeline_stage (pipeline_id, position, name);

CREATE INDEX idx_pipeline_stage_pipeline ON pipeline_stage (pipeline_id);

COMMENT ON TABLE pipeline_stage IS
    'Mirror of a GHL pipeline stage (Unit 44a). GHL owns every column. Never deleted.';
