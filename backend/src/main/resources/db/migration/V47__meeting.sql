-- Meetings EvalOS has booked, mirrored from GHL's own response.
--
-- WHY THIS EXISTS, AND WHAT IT REVERSES. `SalesMeetingService.reschedule` says in its javadoc:
-- "The appointment id comes from the booking response; EvalOS stores none, so the caller is
-- working from something it has just been handed. That is the truth model, not an oversight."
-- That was coherent while a meeting was a fire-and-forget write. It is what made the sales desk
-- **write-only**: a salesperson books Thursday's call and no screen in EvalOS can ever show it
-- again, which defeats the stated purpose of Units 36-41 ("so that they never open GHL").
--
-- WHY A MIRROR RATHER THAN A READ-BACK. Three routes were compared:
--
--   GET /calendars/events                      needs `calendars/events.readonly` — NOT granted
--   GET /contacts/{id}/tasks|appointments      granted, but per-contact: N calls per screen
--   GET /opportunities/search?getCalendarEvents  granted, one call — but the joined field is
--                                              empty across all 100 rows of the busiest pipeline,
--                                              so its wire shape could not be verified
--
-- The third is the right long-term read and stays the plan. It is not the right thing to parse
-- today: nothing in this GHL account has ever produced a calendar event, so a binding written
-- against it would be a guess that compiles. What IS verified is the booking RESPONSE — already
-- parsed by `GhlCalendarClient.AppointmentRow`, already covered by `GhlCalendarClientHttpTest`,
-- and already returned to the caller and discarded.
--
-- This is also the direction `00c` sets: EvalOS holds its own rows and syncs them, rather than
-- asking GHL on every read. A meeting booked here survives the sync being switched off.
--
-- WHAT THIS IS NOT. It is not the authority on a meeting. GHL owns appointments: one moved or
-- cancelled in GHL is not reflected here until Unit 45's sweep reconciles. `ghl_updated_at` and
-- `synced_at` are here so that sweep has something to compare, and `status` is GHL's own word
-- rather than an EvalOS vocabulary — this table must not grow a lifecycle of its own.

CREATE TABLE meeting (
    id                  uuid PRIMARY KEY,
    brand_id            uuid        NOT NULL REFERENCES brand (id),

    -- GHL's appointment id. Unique per brand rather than globally: two brands will one day hold
    -- two GHL locations, and ids are only unique within one.
    ghl_appointment_id  text        NOT NULL,

    -- The deal this meeting is about. Text, not a foreign key: the opportunity lives in GHL and
    -- EvalOS has no opportunity table until Unit 44. Indexed so a deal's meetings are one lookup.
    ghl_opportunity_id  text        NOT NULL,
    ghl_contact_id      text        NOT NULL,
    ghl_calendar_id     text        NOT NULL,

    -- The pipeline the opportunity was on when the meeting was booked. This is the ACCESS KEY:
    -- `PipelineScope` scopes a desk by pipeline, so reading meetings needs the same predicate
    -- available without a second call to GHL.
    ghl_pipeline_id     text        NOT NULL,

    title               text        NOT NULL,
    starts_at           timestamptz NOT NULL,
    ends_at             timestamptz NOT NULL,

    -- GHL's status string, stored verbatim and never interpreted. `CaseTransitions` exists for
    -- EvalOS's own lifecycles; a remote system's vocabulary does not get a state machine here.
    status              text,

    -- Who booked it, for the audit trail and for "my meetings".
    booked_by           uuid        NOT NULL REFERENCES team_member (id),

    created_at          timestamptz NOT NULL DEFAULT now(),
    -- Last time EvalOS wrote this row from a GHL response. Unit 45's drift check compares this
    -- against GHL's own timestamp; until then it records how stale the mirror may be.
    synced_at           timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT uq_meeting_per_brand_appointment UNIQUE (brand_id, ghl_appointment_id),
    CONSTRAINT meeting_ends_after_it_starts CHECK (ends_at > starts_at)
);

-- The two reads this table exists for: a desk's meetings in a window, and one deal's meetings.
-- Both lead with `brand_id` because every scoped query filters by it first.
CREATE INDEX idx_meeting_pipeline_window ON meeting (brand_id, ghl_pipeline_id, starts_at);
CREATE INDEX idx_meeting_opportunity ON meeting (brand_id, ghl_opportunity_id);
