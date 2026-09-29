-- Unit 58 §2 — drafts become uploaded files on the existing DRAFT versions (V31), with a comment
-- thread per version. No new version table: case_document already numbers and stamps drafts.

-- For a DRAFT row, object_key / filename / size_bytes hold the Word file and these the PDF.
-- No other kind uses them.
ALTER TABLE case_document
    ADD COLUMN pdf_object_key text,
    ADD COLUMN pdf_filename   text,
    ADD COLUMN pdf_size_bytes bigint;

-- CHANGES_REQUESTED: stamped when the client sends a version back (until now it was left unmarked).
ALTER TABLE case_document DROP CONSTRAINT case_document_status_known;
ALTER TABLE case_document ADD CONSTRAINT case_document_status_known CHECK (status IN (
    'SUBMITTED', 'RETURNED', 'PM_APPROVED', 'CLIENT_APPROVED', 'CHANGES_REQUESTED', 'SIGNED', 'SUPERSEDED'));

CREATE TABLE draft_comments (
    id          uuid        PRIMARY KEY,
    brand_id    uuid        NOT NULL REFERENCES brand (id),
    document_id uuid        NOT NULL REFERENCES case_document (id),
    author_kind text        NOT NULL,
    -- STAFF: team_member.id. CLIENT: the portal_access id the comment was posted through.
    author_id   uuid        NOT NULL,
    body        text        NOT NULL,
    page        integer,
    created_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT draft_comments_author_kind_known CHECK (author_kind IN ('STAFF', 'CLIENT')),
    CONSTRAINT draft_comments_body_length CHECK (char_length(body) BETWEEN 1 AND 2000),
    CONSTRAINT draft_comments_page_positive CHECK (page IS NULL OR page >= 1)
);

CREATE INDEX draft_comments_document_idx ON draft_comments (document_id, created_at);

-- Kept with the case for the Document Retention Policy's seven years, never edited or deleted.
CREATE FUNCTION draft_comments_append_only() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'draft_comments is append-only: a comment is never edited or deleted';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER draft_comments_append_only
    BEFORE UPDATE OR DELETE ON draft_comments
    FOR EACH ROW EXECUTE FUNCTION draft_comments_append_only();
