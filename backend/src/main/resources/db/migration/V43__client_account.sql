-- Unit 42 — the client's own account, and the tokens that set its password.
--
-- **`password_hash IS NULL` is the "never set a password" state.** There is deliberately no
-- status column beside it: a second column stating what the first already states is a second
-- thing to keep in step. Every account seeded from contact_snapshot (V44) starts here.
--
-- **`ghl_contact_id` is a LINK, not the identity.** Invariant 7 as amended by this unit: GHL's
-- contact id stays canonical in GHL, but a client's ability to sign in does not depend on GHL
-- holding a row. It is nullable forever, and V44 seeds it NULL because IE's GHL sub-account was
-- replaced on 2026-09-11 and every id EvalOS holds names a contact that no longer exists.

create table client_account (
    id              uuid primary key,
    brand_id        uuid        not null references brand (id),
    email           text        not null,
    password_hash   text,
    ghl_contact_id  text,
    first_name      text,
    last_name       text,
    phone           text,
    country         text,
    created_at      timestamptz not null default now(),
    last_sign_in_at timestamptz
);

-- Case-insensitive, because a client who signs up as Ana@x.com and signs in as ana@x.com is one
-- person. Done as an index on lower(email) rather than the citext extension: no extension to
-- install, and the repository's findBy...EmailIgnoreCase generates a matching lower() predicate.
create unique index client_account_brand_email_key
    on client_account (brand_id, lower(email));

create index client_account_ghl_contact_idx
    on client_account (brand_id, ghl_contact_id)
    where ghl_contact_id is not null;

-- **Only the hash, never the token** — the rule PortalAccess already follows. A database read
-- (a backup, a support query, a leaked dump) yields no working link.
create table client_credential_token (
    id                uuid primary key,
    brand_id          uuid        not null references brand (id),
    client_account_id uuid        not null references client_account (id),
    token_hash        text        not null unique,
    purpose           text        not null check (purpose in ('SET', 'RESET')),
    expires_at        timestamptz not null,
    used_at           timestamptz,
    created_at        timestamptz not null default now()
);

create index client_credential_token_account_idx
    on client_credential_token (client_account_id);
