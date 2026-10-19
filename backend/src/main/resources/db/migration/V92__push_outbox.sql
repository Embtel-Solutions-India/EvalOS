-- Web push retries survive a restart. A push the push service could not take right now (network, 429, 5xx) was
-- retried from an in-memory scheduler, so a deploy during a push outage silently dropped every notification
-- waiting on it. Now it is a row, and the PUSH_OUTBOX sweep sends it when it falls due.
--
-- A WORK QUEUE, NOT HISTORY. A row is deleted the moment it settles -- sent, refused, browser gone, or out of
-- retries -- so the table holds only what is still owed, never a record of what was sent.
--
-- THE LEAST IT CAN HOLD. The browser is a reference, not a copy of its endpoint and keys, and it cascades: a
-- subscription forgotten for any reason takes its owed pushes with it. The payload is kept because a retry must
-- say what the first try said, and it is the same line the lock screen shows (a case code, never a client).
CREATE TABLE push_outbox (
    id              uuid        PRIMARY KEY,
    brand_id        uuid        NOT NULL REFERENCES brand (id),
    subscription_id uuid        NOT NULL REFERENCES push_subscriptions (id) ON DELETE CASCADE,
    payload         text        NOT NULL,
    -- Retries already spent; PushSender's backoff table is indexed by it and gives up at its length.
    retries         integer     NOT NULL DEFAULT 0,
    due_at          timestamptz NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now()
);

-- The drain's read: what is due, oldest first.
CREATE INDEX idx_push_outbox_due ON push_outbox (due_at);
CREATE INDEX idx_push_outbox_subscription ON push_outbox (subscription_id);
