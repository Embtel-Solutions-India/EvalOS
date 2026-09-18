-- Unit 47: the location's reference lists become EvalOS rows.
--
-- SCOPED BY `00d` §6.6, NOT BY THE TIER LIST. `00c` §3 names this unit "custom fields, tags,
-- notes, tasks, calendars"; §6.6 overrules the shape of it -- "a sync surface with no consumer is
-- pure drift risk with no offsetting benefit... rewrite Unit 47 as 'mirror what 46 needs'". So the
-- question answered here is not "what is in tiers 2 and 3" but "what does a desk screen still read
-- live from GHL after Unit 46". That list is exactly three: custom field definitions, calendars,
-- and the location's users. Tags and GHL notes have no reader and are not here. See `47` §4.
--
-- THE SAME SHAPE, THREE MORE TIMES. Each of these is a small, slow-changing, location-scoped list
-- that a form reads on every render -- which is precisely what `pipeline` was before 44a, and the
-- fix is the same: upsert on GHL's own id, stamp `synced_at`, never delete. One hourly sweep
-- refreshes all three, because they change at the same speed and three sweeps would mean three
-- chances for one of them to be the one that quietly stopped.
--
-- PREFIXED `ghl_`, UNLIKE `pipeline` AND `opportunity`. `user` is reserved in Postgres and
-- `calendar` collides with vocabulary the app already uses; a table that has to be quoted in every
-- query is a table somebody will eventually forget to quote. All three are named consistently
-- rather than one of them being the odd one out for a reason readers would have to look up.
--
-- WHAT IS NOT HERE, DELIBERATELY: free slots. Availability is GHL's to compute -- open hours,
-- buffers, per-day caps, the assignee's other appointments -- and a mirrored slot is wrong within
-- a minute of being written. The line this unit draws is MIRROR THE STRUCTURE, NEVER THE
-- AVAILABILITY: a calendar is a fact about the location, a free slot is a fact about right now.
-- Unit 48 inherits the consequence: with sync off the business keeps its boards and its
-- production, and cannot take a new booking.

CREATE TABLE ghl_custom_field (
    id                uuid        PRIMARY KEY,

    -- The selling brand, as on every other mirror row: the location belongs to
    -- `evalos.ghl.sales-brand`, and so do the lists it defines. See V50 for the full reasoning.
    brand_id          uuid        NOT NULL REFERENCES brand (id),

    -- GHL's own field id, verbatim. This is the id a form posts back and `GhlWriteClient` writes
    -- into `customFields` on a create, so storing anything derived would mean translating on a
    -- path where a wrong translation is silently accepted by GHL.
    ghl_id            text        NOT NULL,

    -- Which GHL object the field is defined on. Only `opportunity` is read today; the column
    -- exists because the endpoint is `?model=` and a contact-field reader would otherwise need a
    -- second table for the same shape.
    model             text        NOT NULL,

    name              text        NOT NULL,
    field_key         text,

    -- GHL's own type string, NOT an enum. A type EvalOS has never seen must reach the form as an
    -- input it can render generically rather than fail deserialization -- the same ruling
    -- `GhlCustomFieldClient.CustomField` already carries.
    data_type         text,

    -- Empty for every type except SINGLE_OPTIONS. jsonb rather than a child table: nothing joins
    -- to an option, nothing constrains one, and the whole value is read and written together.
    picklist_options  jsonb       NOT NULL DEFAULT '[]'::jsonb,

    synced_at         timestamptz NOT NULL,

    -- A soft delete, exactly as on `pipeline`: a field hidden in GHL for an afternoon must not
    -- disappear from the table that a create's stored ids point at.
    missing_since     timestamptz,

    created_at        timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT uq_ghl_custom_field_per_brand UNIQUE (brand_id, ghl_id)
);

COMMENT ON TABLE ghl_custom_field IS
    'Mirror of a GHL custom field DEFINITION (Unit 47). Definitions only -- values are not '
    'mirrored, because nothing reads them; see 47 section 4.';

CREATE TABLE ghl_calendar (
    id              uuid        PRIMARY KEY,
    brand_id        uuid        NOT NULL REFERENCES brand (id),
    ghl_id          text        NOT NULL,

    name            text        NOT NULL,

    -- GHL's own flag. Kept rather than filtered at write time so that a calendar switched off and
    -- back on does not churn `missing_since`, which means something different: absent from GHL's
    -- answer entirely.
    active          boolean     NOT NULL DEFAULT true,

    slot_minutes    integer,
    title_template  text,

    synced_at       timestamptz NOT NULL,
    missing_since   timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT uq_ghl_calendar_per_brand UNIQUE (brand_id, ghl_id)
);

COMMENT ON TABLE ghl_calendar IS
    'Mirror of a GHL calendar (Unit 47). The structure only -- free slots are never mirrored.';

CREATE TABLE ghl_user (
    id             uuid        PRIMARY KEY,
    brand_id       uuid        NOT NULL REFERENCES brand (id),
    ghl_id         text        NOT NULL,

    name           text        NOT NULL,

    -- Nullable because GHL's user list has returned rows without one. A picker can show a name;
    -- refusing the whole list over one incomplete row would take the booking form down.
    email          text,

    synced_at      timestamptz NOT NULL,
    missing_since  timestamptz,
    created_at     timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT uq_ghl_user_per_brand UNIQUE (brand_id, ghl_id)
);

COMMENT ON TABLE ghl_user IS
    'Mirror of a GHL location user (Unit 47), for the booking form''s team-member picker. '
    'NOT joined to team_member -- that column is open-decisions Q9.';
