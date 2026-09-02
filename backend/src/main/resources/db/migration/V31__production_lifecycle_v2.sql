-- Unit 31: five production stages become twelve, each with one owner.
--
-- `current_stage` is plain `text` with no CHECK (V5), so this is an UPDATE rather than a type
-- change. That is the easy half. The hard half is the split: `DRAFT_GENERATION` was three
-- different owners' work told apart by two nullable sub-status columns, and every existing row
-- has to land in exactly one of the three new stages.
--
-- **Order matters.** The renames are unambiguous and go first. The split reads the two sub-status
-- columns and must be written most-specific-first, because a row can satisfy more than one
-- condition and the LAST write would otherwise win.

-- ---------------------------------------------------------------------------
-- 1. The unambiguous renames.
-- ---------------------------------------------------------------------------

UPDATE evalos_case SET current_stage = 'PM_REVIEW'        WHERE current_stage = 'EXPERT_ASSIGNMENT';
UPDATE evalos_case SET current_stage = 'READY_TO_DELIVER' WHERE current_stage = 'FINAL_DELIVERY';

-- `FINAL_DELIVERY` split in two, and `delivery_date` is what tells them apart: a case that has
-- been sent is DELIVERED, one that is only QC-passed is READY_TO_DELIVER. Runs after the rename
-- above, so it narrows that result rather than racing it.
UPDATE evalos_case SET current_stage = 'DELIVERED'
    WHERE current_stage = 'READY_TO_DELIVER' AND delivery_date IS NOT NULL;

-- ---------------------------------------------------------------------------
-- 2. The DRAFT_GENERATION split — three stages from one, decided by two columns.
-- ---------------------------------------------------------------------------
--
-- The combinations, and the answer for each. **Every row must match exactly one**, which is why
-- the most specific goes first and a catch-all closes it: a row that fell through would keep a
-- stage name the enum no longer has, and `Role.valueOf`-style failures at read time are the worst
-- possible way to discover a migration missed a case.

-- Client is looking at it. The client's clock is the one running, so this outranks whatever the
-- PM column says — a draft cannot be with the client unless a PM approved it.
UPDATE evalos_case SET current_stage = 'CLIENT_REVIEW'
    WHERE current_stage = 'DRAFT_GENERATION' AND client_approval_status = 'PENDING';

-- The client answered yes. Under the old model the case moved to EXPERT_SIGNING on approval, so
-- this combination should not exist — but "should not exist" is exactly the class of row that
-- does. It lands on CLIENT_APPROVAL, where the CM's send-to-expert is the next act.
UPDATE evalos_case SET current_stage = 'CLIENT_APPROVAL'
    WHERE current_stage = 'DRAFT_GENERATION' AND client_approval_status = 'APPROVED';

-- The client asked for changes: CM work, same as any other revision.
UPDATE evalos_case SET current_stage = 'DRAFT_IN_PROGRESS'
    WHERE current_stage = 'DRAFT_GENERATION' AND client_approval_status = 'REVISION_REQUESTED';

-- With the client column settled, the PM column decides the rest.
UPDATE evalos_case SET current_stage = 'DRAFT_REVIEW'
    WHERE current_stage = 'DRAFT_GENERATION' AND pm_approval_status = 'PENDING';

-- **PM approved and not yet sent — the row the new READY_TO_SEND stage exists for.** Under the
-- old model this case was invisible: approved, still in DRAFT_GENERATION, waiting on a Coordinator
-- with nothing on any board saying so.
UPDATE evalos_case SET current_stage = 'READY_TO_SEND'
    WHERE current_stage = 'DRAFT_GENERATION' AND pm_approval_status = 'APPROVED';

-- Returned, or never submitted, or any combination not named above: the Case Manager has it.
-- **This is the catch-all and it must stay last.** DRAFT_IN_PROGRESS is the safe landing: it is
-- the stage the revision loops all return to, so a row placed here is at worst asked to travel
-- forward again through reviews that will pass it, rather than skipping an approval.
UPDATE evalos_case SET current_stage = 'DRAFT_IN_PROGRESS'
    WHERE current_stage = 'DRAFT_GENERATION';

-- ---------------------------------------------------------------------------
-- 3. Prove it. Nothing may hold a stage the enum cannot read.
-- ---------------------------------------------------------------------------
--
-- A DO block rather than a CHECK constraint: the point is to fail *this migration* loudly if the
-- mapping above missed a combination, not to police the column forever — that is the enum's job,
-- and V5 deliberately left this column unconstrained.
DO $$
DECLARE stray text;
BEGIN
    SELECT current_stage INTO stray FROM evalos_case
        WHERE current_stage NOT IN (
            'DOC_COLLECTION', 'PM_REVIEW', 'DRAFT_IN_PROGRESS', 'DRAFT_REVIEW', 'READY_TO_SEND',
            'CLIENT_REVIEW', 'CLIENT_APPROVAL', 'EXPERT_SIGNING', 'FINAL_QC', 'READY_TO_DELIVER',
            'DELIVERED', 'CLOSED')
        LIMIT 1;
    IF stray IS NOT NULL THEN
        RAISE EXCEPTION 'V31 left an unmapped stage: %', stray;
    END IF;
END $$;

-- ---------------------------------------------------------------------------
-- 4. `case_document` — the draft becomes a versioned file.
-- ---------------------------------------------------------------------------
--
-- `evalos_case` carries `draft_link` (one link) and `draft_version_count` (an integer). **A count
-- is not a history**: it cannot say who uploaded V2, when, what the PM said about it, or which
-- version the client approved — and the client-approved version is the one the expert signs and
-- the business is paid for.
--
-- Neither old column is dropped here. `draft_link` still points at whatever is current and is
-- removed by Unit 30 when documents move to S3; dropping it in the same migration that adds this
-- table would leave a window where a rollback loses the pointer entirely.
CREATE TABLE case_document (
    id               uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    brand_id         uuid        NOT NULL REFERENCES brand (id),
    case_id          uuid        NOT NULL REFERENCES evalos_case (id),
    -- DRAFT · CLIENT_UPLOAD · REDACTED_PROFILE · SIGNED_LETTER. Open vocabulary, closed by the
    -- CHECK below for the same reason V19's outcome column is: the enum stops a caller, the CHECK
    -- stops a seed script.
    kind             text        NOT NULL,
    version          int         NOT NULL,
    -- The S3 object (Unit 30). Nullable only until that unit lands; the whole point of a row here
    -- is that it names a file.
    object_key       text,
    filename         text        NOT NULL,
    content_type     text,
    size_bytes       bigint,
    uploaded_by      uuid        REFERENCES team_member (id),
    -- STAFF · CLIENT · EXPERT · SYSTEM — the same actor vocabulary the audit trail uses (V22).
    -- Nullable `uploaded_by` with a non-null type is how a client's upload is recorded: they have
    -- no team_member row, and inventing one would put a non-employee in the staff table.
    uploaded_by_type text        NOT NULL,
    uploaded_at      timestamptz NOT NULL DEFAULT now(),
    notes            text,
    status           text        NOT NULL,
    created_at       timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT case_document_kind_known CHECK (kind IN (
        'DRAFT', 'CLIENT_UPLOAD', 'REDACTED_PROFILE', 'SIGNED_LETTER')),
    CONSTRAINT case_document_status_known CHECK (status IN (
        'SUBMITTED', 'RETURNED', 'PM_APPROVED', 'CLIENT_APPROVED', 'SIGNED', 'SUPERSEDED')),
    CONSTRAINT case_document_actor_known CHECK (uploaded_by_type IN (
        'STAFF', 'CLIENT', 'EXPERT', 'SYSTEM')),
    CONSTRAINT case_document_version_positive CHECK (version > 0)
);

-- **No version is ever reused, and this is the database's job rather than a service check.**
-- Two Case Managers — or one CM and a retried request — racing to upload V3 is precisely the
-- failure a read-then-write in application code misses.
CREATE UNIQUE INDEX uq_case_document_version ON case_document (case_id, kind, version);

-- The read every screen does: this case's documents of one kind, newest first.
CREATE INDEX idx_case_document_case_kind ON case_document (case_id, kind, version DESC);

-- Brand-scoped like every other table, so `ScopePredicate` has a column to filter on.
CREATE INDEX idx_case_document_brand ON case_document (brand_id, case_id);
