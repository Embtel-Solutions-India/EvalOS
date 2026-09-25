-- Unit 57 (2026-09-25): case chat. Three conversations per case, membership computed by EvalOS
-- from case assignments, history kept by never deleting a member row. Text only (no attachments).
-- Spec: context/specs/57-case-chat.md.

CREATE TABLE conversations (
    id              uuid        PRIMARY KEY,
    brand_id        uuid        NOT NULL REFERENCES brand (id),
    case_id         uuid        NOT NULL REFERENCES evalos_case (id),
    type            text        NOT NULL CHECK (type IN ('CLIENT', 'INTERNAL', 'EXPERT')),
    status          text        NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'READ_ONLY')),
    created_at      timestamptz NOT NULL DEFAULT now(),
    read_only_at    timestamptz,
    last_message_at timestamptz,
    CONSTRAINT conversations_one_per_type UNIQUE (case_id, type),
    CONSTRAINT conversations_read_only_pair CHECK ((status = 'READ_ONLY') = (read_only_at IS NOT NULL))
);
CREATE INDEX conversations_brand_status_idx ON conversations (brand_id, status);

-- created_at is when the person joined. A current member has left_at NULL.
CREATE TABLE conversation_members (
    id              uuid        PRIMARY KEY,
    brand_id        uuid        NOT NULL REFERENCES brand (id),
    conversation_id uuid        NOT NULL REFERENCES conversations (id),
    member_kind     text        NOT NULL CHECK (member_kind IN ('STAFF', 'CLIENT', 'EXPERT')),
    member_id       uuid        NOT NULL,
    member_role     text        NOT NULL CHECK (member_role IN
                        ('CLIENT', 'SALES', 'PM', 'COORDINATOR', 'CASE_MANAGER', 'ENM', 'EXPERT')),
    created_at      timestamptz NOT NULL DEFAULT now(),
    left_at         timestamptz,
    left_reason     text,
    CONSTRAINT conversation_members_left_pair CHECK ((left_at IS NULL) = (left_reason IS NULL))
);
CREATE UNIQUE INDEX conversation_members_current_idx
    ON conversation_members (conversation_id, member_kind, member_id) WHERE left_at IS NULL;
CREATE INDEX conversation_members_by_member_idx
    ON conversation_members (member_kind, member_id) WHERE left_at IS NULL;

CREATE FUNCTION conversation_members_history_only() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'conversation_members is membership history: rows are never deleted';
    END IF;
    IF OLD.left_at IS NOT NULL OR NEW.left_at IS NULL
       OR NEW.id <> OLD.id OR NEW.brand_id <> OLD.brand_id OR NEW.conversation_id <> OLD.conversation_id
       OR NEW.member_kind <> OLD.member_kind OR NEW.member_id <> OLD.member_id
       OR NEW.member_role <> OLD.member_role OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION 'conversation_members: an update may only stamp left_at and left_reason on a current row';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER conversation_members_history_only
    BEFORE UPDATE OR DELETE ON conversation_members
    FOR EACH ROW EXECUTE FUNCTION conversation_members_history_only();

CREATE TABLE messages (
    id                uuid        PRIMARY KEY,
    brand_id          uuid        NOT NULL REFERENCES brand (id),
    conversation_id   uuid        NOT NULL REFERENCES conversations (id),
    author_kind       text        NOT NULL CHECK (author_kind IN ('STAFF', 'CLIENT', 'EXPERT')),
    author_id         uuid        NOT NULL,
    body              text        NOT NULL CHECK (char_length(body) <= 4000),
    parent_message_id uuid        REFERENCES messages (id),
    created_at        timestamptz NOT NULL DEFAULT now(),
    edited_at         timestamptz,
    deleted_at        timestamptz,
    search            tsvector    GENERATED ALWAYS AS (to_tsvector('simple', body)) STORED,
    CONSTRAINT messages_deleted_is_empty CHECK (deleted_at IS NULL OR body = '')
);
CREATE INDEX messages_page_idx ON messages (conversation_id, created_at DESC, id DESC);
CREATE INDEX messages_thread_idx ON messages (parent_message_id, created_at, id) WHERE parent_message_id IS NOT NULL;
CREATE INDEX messages_search_idx ON messages USING gin (search);

CREATE TABLE message_reactions (
    id          uuid        PRIMARY KEY,
    brand_id    uuid        NOT NULL REFERENCES brand (id),
    message_id  uuid        NOT NULL REFERENCES messages (id),
    reactor_kind text       NOT NULL CHECK (reactor_kind IN ('STAFF', 'CLIENT', 'EXPERT')),
    reactor_id  uuid        NOT NULL,
    reaction    text        NOT NULL CHECK (reaction IN
                    ('THUMBS_UP', 'HEART', 'LAUGH', 'CELEBRATE', 'SURPRISED', 'THANKS')),
    created_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT message_reactions_once UNIQUE (message_id, reactor_kind, reactor_id, reaction)
);

-- The read watermark: one row per member per conversation, moved forward, never back.
CREATE TABLE message_reads (
    id                   uuid        PRIMARY KEY,
    brand_id             uuid        NOT NULL REFERENCES brand (id),
    conversation_id      uuid        NOT NULL REFERENCES conversations (id),
    reader_kind          text        NOT NULL CHECK (reader_kind IN ('STAFF', 'CLIENT', 'EXPERT')),
    reader_id            uuid        NOT NULL,
    last_read_message_id uuid        NOT NULL REFERENCES messages (id),
    last_read_at         timestamptz NOT NULL,
    created_at           timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT message_reads_one_per_reader UNIQUE (conversation_id, reader_kind, reader_id)
);

-- Web push (D37). One row per browser a person has allowed notifications in. Used from Task 11.
CREATE TABLE push_subscriptions (
    id              uuid        PRIMARY KEY,
    brand_id        uuid        NOT NULL REFERENCES brand (id),
    subscriber_kind text        NOT NULL CHECK (subscriber_kind IN ('STAFF', 'CLIENT', 'EXPERT')),
    subscriber_id   uuid        NOT NULL,
    endpoint        text        NOT NULL UNIQUE,
    p256dh          text        NOT NULL,
    auth            text        NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    last_success_at timestamptz
);
CREATE INDEX push_subscriptions_subscriber_idx ON push_subscriptions (subscriber_kind, subscriber_id);
