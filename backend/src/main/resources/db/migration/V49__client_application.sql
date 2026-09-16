-- Unit 43 — a client's request for a service, from the moment they pick one to the moment Sales
-- reads it.
--
-- WHAT THIS IS, AND WHAT IT IS EMPHATICALLY NOT. It is **not** a case. A case is still born only
-- of a won opportunity arriving on the webhook (Handoff A, invariant 8), and nothing in this unit
-- creates one. It is not a second copy of a GHL opportunity either: the opportunity is GHL's, the
-- id below is a link to it, and every field here is something GHL has nowhere to put — a service
-- id from EvalOS's own catalog, and a questionnaire whose questions depend on it.
--
-- WHY THE ANSWERS LIVE HERE RATHER THAN IN THE BROWSER. The deleted funnel kept its draft in
-- `localStorage`, which is one browser. A client who abandons on the documents step has by then
-- chosen a service and answered fifteen questions; coming back on their phone lost all of it.
-- A row keyed to an account is what makes "sign in again and carry on" true (43 §4).
--
-- WHY `answers` IS ONE jsonb COLUMN AND NOT A TABLE OF ANSWERS. The questions are defined by the
-- catalog, not by the schema — adding a service is an entry in `serviceCatalog.ts` pointing at
-- question group ids, and no migration. An `application_answer (application_id, question_id,
-- value)` table would model that faithfully and buy nothing: nothing joins on a question id,
-- nothing aggregates across clients, and the only reader is a human being shown the whole set.
-- jsonb over text because Postgres then refuses malformed JSON for free.
--
-- IT IS AN ARRAY OF {id, label, value}, NOT A MAP OF id -> value, and the label is the reason.
-- The question text lives in the frontend catalog, so a map would leave the STAFF screen holding
-- `q_degree_country: "Nigeria"` with no way to render the question — and the staff app cannot
-- import the client portal's catalog. Storing the label as it was ASKED also survives the catalog
-- being reworded later, which a lookup would not. An array rather than an object because the
-- order a questionnaire was answered in is part of reading it back.
--
-- STATUS IS EvalOS's OWN LIFECYCLE, so it gets an enum and a CHECK — unlike `meeting.status`,
-- which mirrors GHL's word and deliberately does not. There are two values and there is no
-- transition table, because there is one transition.

create table client_application (
    id                 uuid        primary key,
    brand_id           uuid        not null references brand (id),

    -- The account is the client. Not `ghl_contact_id`: the account is EvalOS's own row and
    -- survives the CRM being replaced, which the 2026-09-11 sub-account swap made concrete.
    client_account_id  uuid        not null references client_account (id),

    -- From `serviceCatalog.ts`. Text, not an FK and not an enum: the catalog is frontend data by
    -- decision (see ClientApplicationService), and a column that had to be migrated every time
    -- marketing renamed a service would make the catalog's whole point false.
    service_id         text        not null,

    -- The service's display name AS IT WAS WHEN THE CLIENT CHOSE IT. Denormalised on purpose: it
    -- is what the opportunity in GHL is called and what Sales reads six weeks later, and it must
    -- not silently change under them because the catalog was edited.
    service_name       text        not null,

    -- Why they want it — 'immigration', 'employment', … — or null for a service that implies its
    -- own purpose and therefore never asks.
    purpose            text,

    status             text        not null
        constraint client_application_status_check check (status in ('DRAFT', 'SUBMITTED')),

    answers            jsonb       not null default '[]'::jsonb,

    -- The GHL opportunity this application opened. NOT a foreign key — the opportunity lives in
    -- GHL, and `ghl_opportunity_cache` is droppable (V40), so a FK into it would make truncating
    -- a cache delete real applications. Nullable for exactly one window: a GHL outage between the
    -- row being written and the opportunity being created. `ClientApplicationService` retries it
    -- on the next save rather than stranding the client mid-funnel.
    ghl_opportunity_id text,

    created_at         timestamptz not null default now(),
    updated_at         timestamptz not null default now(),
    submitted_at       timestamptz,

    -- A submitted application without a submission time is a row that cannot answer "when", which
    -- is the first thing anyone asks of it. Biconditional, and written with the NULL arm
    -- explicit: `NULL IN (...)` is NULL and **a CHECK evaluating to NULL passes in Postgres** —
    -- the trap V39's segment check fell into and that LocalPostgresIntegrationTest caught.
    constraint client_application_submitted_at_check check (
        (status = 'SUBMITTED' and submitted_at is not null)
        or (status <> 'SUBMITTED' and submitted_at is null)
    )
);

-- The dashboard's one question: "does this client have something in progress?" Ordered so the
-- newest answers it without a sort.
create index client_application_account_idx
    on client_application (client_account_id, created_at desc);

-- Sales reads an application by the opportunity they are standing on.
create index client_application_opportunity_idx
    on client_application (brand_id, ghl_opportunity_id)
    where ghl_opportunity_id is not null;

-- **One draft per client at a time.** A second "start a request" while one is unfinished is
-- almost always the same person having lost the first, and two half-filled forms is the state
-- nobody can act on. Submitted applications are unlimited — that is a repeat client, which is
-- the business EvalOS wants.
create unique index client_application_one_draft_idx
    on client_application (client_account_id)
    where status = 'DRAFT';
