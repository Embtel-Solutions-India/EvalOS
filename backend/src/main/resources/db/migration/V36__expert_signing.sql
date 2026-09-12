-- Unit 15: the expert portal, and the provenance of what comes back signed.
--
-- Four columns, and the count is the point. The spec drafted eight on `evalos_case`
-- (`expert_viewed_at`, `sign_deadline_at`, `letter_sent_hash`, `signed_letter_drive_file_id`,
-- `signed_letter_hash`, `signed_at`, `sign_attestation`, `sign_attested_name`). Four of those
-- state facts the system already holds and would be a second place for each to be wrong:
--
--   * `sign_deadline_at` — `SlaCalculator` budgets EXPERT_SIGN off `stage_entered_at`. A stored
--     deadline is that calculation frozen at send time, and it stops agreeing with the board the
--     first time a case is put on hold.
--   * `signed_letter_drive_file_id` — Drive is gone (V34). The signed letter is a `case_document`
--     row with an `object_key`, exactly like every other file since Unit 30.
--   * `signed_at` — that row's `uploaded_at`.
--   * `letter_sent_hash` — **cannot be computed and is therefore not a column.** The letter the
--     expert is sent is `draft_link`, a free-text link to a document EvalOS does not hold the
--     bytes of, and `DocumentStore` has no read-bytes capability by design. A nullable column
--     nothing ever writes reads as "we did not hash this one" rather than "we never can". See
--     the spec amendment; it becomes possible the day a draft is an object in S3.
--
-- What is left is the half of the provenance chain EvalOS genuinely holds: what came back, who
-- said it was theirs, and when the expert first opened the link.

-- The expert's read receipt, mirroring `client_portal_read_at` (V21) exactly: stamped ONCE, on
-- the first read, so it answers "has the expert opened this at all" — which is what the Case
-- Manager needs before chasing. "When did they last look" is `portal_access.last_seen_at`,
-- which moves on every request. Two fields because they are two questions.
ALTER TABLE evalos_case
    ADD COLUMN expert_portal_read_at timestamptz;

ALTER TABLE case_document
    -- SHA-256 of the bytes as received, hex. On the document rather than the case because a case
    -- can be signed more than once — a failed final QC (V31's PM_QC_FAIL) sends the letter back
    -- to the Case Manager and the next signature is a new version. A per-case column would hold
    -- the newest and quietly lose the one a dispute is about.
    ADD COLUMN content_sha256 text,
    -- The attestation as ticked, stored verbatim rather than as a boolean: the wording is the
    -- evidence, and a `true` against wording nobody kept proves nothing. Non-null exactly on the
    -- rows an expert signed.
    ADD COLUMN attestation    text,
    -- The name as displayed to the expert when they ticked it. Deliberately not a join to
    -- `expert.full_name`: that column can be corrected later, and this is what the person
    -- actually saw and agreed to.
    ADD COLUMN attested_name  text;
