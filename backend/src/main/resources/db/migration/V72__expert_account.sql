-- Unit 59 (D23): experts sign in. An account is bound to one roster row (expert.id), never matched
-- on email at sign-in; the email only finds the roster row. Same shape as V43's client tables.
create table expert_account (
    id              uuid primary key,
    brand_id        uuid        not null references brand (id),
    expert_id       uuid        not null unique references expert (id),
    password_hash   text,
    created_at      timestamptz not null default now(),
    last_sign_in_at timestamptz
);

-- Single-use set / reset links. Only the SHA-256 of the mailed token is stored.
create table expert_credential_token (
    id                uuid primary key,
    brand_id          uuid        not null references brand (id),
    expert_account_id uuid        not null references expert_account (id),
    token_hash        text        not null unique,
    purpose           text        not null check (purpose in ('SET', 'RESET')),
    expires_at        timestamptz not null,
    used_at           timestamptz,
    created_at        timestamptz not null default now()
);
create index expert_credential_token_account_idx
    on expert_credential_token (expert_account_id);
