-- `sync_outbox` is a `ScopedEntity`, and every one of those carries `created_at`.
--
-- `V57` left it out and Hibernate's `ddl-auto: validate` caught it on the next boot -- which is the
-- check working exactly as intended: a mapped field with no column is a 500 on the first insert, and
-- validate turns it into a refused startup instead. Its own migration rather than an edit to `V57`,
-- because that one is applied and editing an applied migration is a checksum mismatch.
--
-- `queued_at` is deliberately kept as well, and they are not duplicates: `created_at` is the row's,
-- written by the entity superclass, while `queued_at` is the PUSH's and is what the drain orders by.
-- They will be equal today and stop being equal the moment anything re-queues a row in place.
ALTER TABLE sync_outbox
    ADD COLUMN created_at timestamptz NOT NULL DEFAULT now();
