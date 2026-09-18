-- Unit 53: the documents a client sends WITH the request, before any case exists.
--
-- D33/D34 (2026-09-17). `workflows.md` §2 has always named DOCUMENT SUBMISSION as a step and
-- Unit 43 deferred it -- `43` §5: "a missing document is a thing Sales chases, not a wall the
-- funnel puts in front of a lead". **That posture survives: submit is never gated on
-- completeness.** What changes is that there is now somewhere to put them.
--
-- THE KEY IS THE ONE THAT ALREADY EXISTS, so nothing is invented.
-- `DocumentStore.clientKey(brandId, ghlContactId, documentId)` -> `{brand}/client/{contact}/{doc}`.
-- The `ponytail:` note on that method predicted this unit and named its blocker -- the funnel
-- uploaded "before any case or contact exists" -- and the blocker is gone: the contact is created
-- at set-password, at the next sign-in, or at the first request that needs one (D3d/D3c).
--
-- THE PREFIX IS THE CONTACT, NOT THE APPLICATION, so a repeat client's second request lands beside
-- their first. The documents belong to the PERSON. That is also what makes §4's carry-forward free:
-- nothing copies in S3 and nothing re-keys at Handoff A, because the key was never case-scoped.
CREATE TABLE application_document (
    id                          uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    brand_id                    uuid        NOT NULL REFERENCES brand (id),

    client_application_id       uuid        NOT NULL REFERENCES client_application (id) ON DELETE CASCADE,

    -- A REAL foreign key, not the GHL string. D41 names contacts by GHL id for the S3 prefix
    -- without touching primary keys (D18) -- the key's GHL id is reached THROUGH this row, so a
    -- contact whose GHL id is backfilled later does not orphan a document.
    contact_id                  uuid        NOT NULL REFERENCES contact_snapshot (id),

    -- Authoritative for reads, exactly as on `case_document`: objects written under an older key
    -- shape stay readable because a read resolves through the stored key and never rebuilds it.
    object_key                  text        NOT NULL,

    -- The real name is DATA, never a path. A filename reaching a key is how a client picks the
    -- prefix they land in.
    filename                    text        NOT NULL,
    content_type                text,
    size_bytes                  bigint,

    -- Both, mirroring `case_document`: `created_at` is ScopedEntity's and every scoped row has one,
    -- `uploaded_at` is the domain fact the two document tables are read and sorted by.
    created_at                  timestamptz NOT NULL DEFAULT now(),
    uploaded_at                 timestamptz NOT NULL DEFAULT now(),
    -- True for everything this unit writes. The column exists because Sales uploading on a
    -- client's behalf is a plausible next ask, and a boolean beats a second table.
    uploaded_by_client          boolean     NOT NULL DEFAULT true,

    -- Set ONCE at Handoff A, which is what makes the carry-forward idempotent AND visible: a
    -- replayed `opportunity.won` finds it set and skips, and a human can see which documents made
    -- it onto the case without diffing two tables.
    carried_to_case_document_id uuid        REFERENCES case_document (id)
);

-- The client's own list, and Sales' list, are both "this application's documents, newest first".
CREATE INDEX idx_application_document_application
    ON application_document (client_application_id, uploaded_at DESC);

-- The carry-forward's own read at Handoff A: what is still uncarried for this application.
CREATE INDEX idx_application_document_uncarried
    ON application_document (client_application_id) WHERE carried_to_case_document_id IS NULL;

COMMENT ON TABLE application_document IS
    'Documents uploaded WITH a client request, before a case exists (Unit 53, D33). Shares S3 keys '
    'with case_document after Handoff A carries them over -- the object never moves.';

-- NO CHECKLIST ITEM, and no request-stage checklist anywhere. A checklist is a case concept and
-- Unit 10 owns it; chasing a missing document at request stage is Sales' conversation, not a state
-- machine (`53` §5).
