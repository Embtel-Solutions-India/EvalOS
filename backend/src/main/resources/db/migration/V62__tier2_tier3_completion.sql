-- Unit 47b: the tier-2 and tier-3 surface Unit 47 cut, built.
--
-- WHY IT WAS CUT AND WHY THAT WAS WRONG. `47` §4 cut tags, GHL notes, custom field VALUES and task
-- read-back under `00d` §6.6 ("a sync surface with no consumer is pure drift risk"). The business
-- overruled it on 2026-09-17, and one of the four cuts rested on a claim about the GHL API that is
-- simply false. §4 said:
--
--     "GHL lists tasks only per contact, so a desk-wide refresh is one request per contact --
--      which the 100-per-10s budget refuses. It needs a list endpoint GHL does not offer."
--
-- `GET /opportunities/search` -- the read the mirror ALREADY makes, once per pipeline -- takes
-- `getTasks`, `getNotes` and `getCalendarEvents`, and returns `tasks`, `notes`, `calendarEvents`
-- and `customFields` on each opportunity. Verified against the live operation contract, not
-- inferred. So all four ride on a request EvalOS was making anyway: the fan-out that justified the
-- cut does not exist.
--
-- WHAT THIS MIGRATION ADDS, and what it deliberately does not:
--   * `opportunity.custom_fields`   -- the VALUES (D49 reversed).
--   * `ghl_note`                    -- GHL's own notes, distinct from `opportunity_note`.
--   * `ghl_tag`                     -- the location's tag vocabulary.
--   * nothing for task or appointment read-back: `follow_up` and `meeting` already hold every
--     column needed, and were only ever missing the code that writes GHL's answer back into them.

-- THE VALUES, NEXT TO THE ROW THEY BELONG TO.
--
-- A map of GHL field id -> value, as GHL returns it. jsonb rather than a child table: nothing joins
-- to a single value, nothing constrains one, and the whole map is read and written together -- the
-- same argument `client_application.answers` already carries.
--
-- KEYED BY GHL'S FIELD ID, never by name. A field renamed in GHL keeps its id, and `ghl_custom_field`
-- (V60) is what turns an id into a label for a human. Keying by name would make a rename lose data.
--
-- THIS IS WHAT UNBLOCKS D46. A desk create carries custom field values, and the outbox stores an id
-- and never a payload -- so a queued create had nowhere to put them and creates stayed synchronous.
-- With the values on the row, the row IS the payload, and the unit that moves creates onto the
-- queue no longer needs a payload column to do it.
ALTER TABLE opportunity
    ADD COLUMN custom_fields jsonb NOT NULL DEFAULT '{}'::jsonb;

COMMENT ON COLUMN opportunity.custom_fields IS
    'GHL custom field VALUES for this opportunity, keyed by GHL field id (Unit 47b). Labels live '
    'in ghl_custom_field. Mirrored from the opportunity search, never written back by EvalOS yet.';

-- GHL'S OWN NOTES.
--
-- NOT `opportunity_note`, AND THE TWO MUST NOT MERGE. `opportunity_note` is EvalOS staff prose,
-- append-only by database trigger, EvalOS-owned and never synced (45e `FieldOwnership.EVALOS`).
-- This is GHL's side: written in GHL's UI, owned by GHL, read-only here. Merging them would put an
-- append-only trigger over rows a sync has to be able to update, and would make "who said this"
-- unanswerable.
CREATE TABLE ghl_note (
    id             uuid        PRIMARY KEY,
    brand_id       uuid        NOT NULL REFERENCES brand (id),

    -- GHL's note id, verbatim -- the upsert key.
    ghl_id         text        NOT NULL,

    -- Whose note it is. A GHL note hangs off a CONTACT; the opportunity is how EvalOS found it, and
    -- is nullable because a contact can have notes with no deal attached.
    ghl_contact_id text,
    ghl_opportunity_id text,

    title          text,
    body           text,

    -- GHL's own author and timestamp, kept as given: the trail is only useful if it says when GHL
    -- says, not when EvalOS happened to read it.
    ghl_user_id    text,
    date_added     timestamptz,

    synced_at      timestamptz NOT NULL,
    missing_since  timestamptz,
    created_at     timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT uq_ghl_note_per_brand UNIQUE (brand_id, ghl_id)
);

COMMENT ON TABLE ghl_note IS
    'Mirror of a note written in GHL (Unit 47b). Read-only here. Distinct from opportunity_note, '
    'which is EvalOS staff prose, append-only, and never synced.';

CREATE INDEX idx_ghl_note_opportunity ON ghl_note (brand_id, ghl_opportunity_id);

-- THE LOCATION'S TAG VOCABULARY.
--
-- The fourth reference list, on V60's shape exactly: upsert on GHL's id, stamp `synced_at`, never
-- delete. It is refreshed by the same REFERENCE_MIRROR sweep, because a tag changes as often as a
-- calendar does -- which is to say, rarely.
CREATE TABLE ghl_tag (
    id             uuid        PRIMARY KEY,
    brand_id       uuid        NOT NULL REFERENCES brand (id),
    ghl_id         text        NOT NULL,
    name           text        NOT NULL,

    synced_at      timestamptz NOT NULL,
    missing_since  timestamptz,
    created_at     timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT uq_ghl_tag_per_brand UNIQUE (brand_id, ghl_id)
);

COMMENT ON TABLE ghl_tag IS
    'Mirror of a GHL location tag (Unit 47b). The vocabulary only -- which contact carries which '
    'tag arrives on the contact record.';
