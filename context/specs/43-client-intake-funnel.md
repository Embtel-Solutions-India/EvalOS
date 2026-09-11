# Unit 43 — Get Started: the client intake funnel

> **Status:** specced 2026-09-11, revised 2026-09-12, not built.
> **Depends on:** 42 (the account this funnel creates), 30 (S3 uploads), 37 (`GhlWriteClient`).
> **Restores:** the seven-screen funnel and the conditional questionnaire deleted in `f9f1165`.
> **Also ships:** the `pipeline` / `pipeline_stage` half of `00c`'s tier-1 mirror (§6a) — this
> unit cannot move an application to a hot stage without knowing which stage that is, and one
> `GhlPipelineClient.pipelines()` call fills both tables.

> **This unit reverses `34-portal-frontend-wiring.md` D2**, which recommended cutting the funnel:
>
> > *"It is good work and it is **front of house**, which EvalOS does not do. Wiring it would
> > create a case outside `opportunity.won`... `serviceCatalog.ts` and `questionGroups.ts` are
> > worth keeping either way; they are data, not workflow."*
>
> D2's *reasoning* was right and is preserved: **this funnel still creates no case.** What
> changed on 2026-09-11 is the business decision that EvalOS is the front of house for clients,
> which `00b` already made for Sales and Marketing. D2's closing sentence — that the catalog and
> the question groups are data worth keeping — is what makes this unit mostly a restoration.

---

## 1. What changes, in one paragraph

A visitor works through seven steps: welcome, choose a service, state a purpose, give their
details, answer a questionnaire whose questions depend on what they picked, upload the documents
that service needs, and review and submit. **Giving their details creates three things at once
— their account, their GHL contact, and a GHL opportunity already at the hot stage** — and the
questionnaire and documents follow. Sales reviews the answers on a staff screen, contacts the
client, and raises the invoice. **No case is created** — the case is still born only of a won
opportunity, which is still `opportunity.won` arriving on the webhook.

## 1a. Two entry points, one flow (decided 2026-09-12)

```
NEW LEAD        Portal → Get started → About You → create contact + opportunity @ HOT
                      → questionnaire + docs → sales reviews & contacts → payment → WON → case

EXISTING CLIENT Portal → Sign in     → About You → REUSE contact, new opportunity @ HOT
                      → questionnaire + docs → sales reviews & contacts → payment → WON → case
```

**The two paths differ in exactly one thing: whether a GHL contact is created or reused.**
Everything after that is identical — both land at hot, both get a sales review, both reach a case
through Handoff A. An earlier draft of this decision had existing clients skipping the hot stage
and the sales review entirely; **that was rejected on 2026-09-12.** A repeat order can differ in
scope and price from the first one, so a human still prices it.

**One contact, many opportunities.** A returning client gets a *new* opportunity against their
existing contact — invariant 7's rule that `ghl_contact_id` is the client and
`ghl_opportunity_id` is one purchase. EvalOS never creates a second contact for someone it
already knows.

### How the portal tells them apart

**Both mechanisms, and they cover each other's gap.** The welcome screen asks — *Get started* or
*I already have an account* — and Unit 42's `identify` verifies the answer by email.

- The **buttons** are the primary route, because a person knows which they are and asking is
  cheaper than inferring.
- **`identify` is the safety net**, and it is the half that actually matters: a returning client
  who clicks *Get started* out of habit is recognised from their email and routed to the existing
  path instead of being **duplicated as a second contact**. Without it the two buttons are a
  trust-the-user mechanism on the one decision a user has no reason to get right.

**Caveat, and it is temporary but universal today:** after the 2026-09-11 CRM replacement, every
seeded client exists in EvalOS with **no GHL contact**. They sign in, are correctly recognised as
existing, and still take the create-a-contact branch — because there is nothing in the new
location to reuse. The rule is therefore *reuse `ghl_contact_id` when it is non-null*, not
*reuse it when the client is known*.

---

## 2. Most of this already exists, in git

Commit `f9f1165` ("34d — the client portal has no mock left in it, and no account") deleted the
whole funnel. It was deleted for a good reason at the time — every screen imported `useAuth` and
`authService`, so when the account shell went, the funnel no longer compiled, and *"code that
cannot compile is not parked, it is broken"*. Unit 42 brings the account back, which makes the
funnel compilable again.

Recover from `f9f1165^`, do not rewrite:

| File | Lines | What it is |
| --- | --- | --- |
| `constants/serviceCatalog.ts` | 235 | 3 categories → services → which question groups and document templates each needs |
| `constants/questionGroups.ts` | 205 | reusable groups: education, professional background, translation details, … |
| `lib/questionnaire.ts` | 23 | the conditional engine |
| `types/intake.ts` | — | `QuestionDefinition`, `QuestionGroup`, `RequestAnswers`, `ServiceDefinition` |
| `components/intake/*` | — | `QuestionField`, `IntakeProgress`, `ServiceCard`, `IntakeDocumentCard` |
| `pages/intake/*.tsx` | ~900 | the seven screens |
| `schemas/intake.ts` | 31 | **never deleted** — still on disk, password rules included |

**What must change on the way back in**, and it is a short list: every `authService` /
`useAuth` import becomes Unit 42's client, `intakeService`'s `localStorage` draft becomes a
server-side `client_application` row, and `FillDemoDataButton` / `constants/demoData.ts` do not
come back.

---

## 3. The conditional questionnaire, unchanged

The engine is 23 lines and it is the right 23 lines:

```ts
getVisibleGroups(serviceId, answers)   // groups for this service, minus those whose showIf fails
getVisibleQuestions(group, answers)    // questions in a group, minus those whose showIf fails
isGroupComplete(group, answers)        // every visible required question answered
```

A `showIf` is a predicate over the answers so far, so attorney questions appear only for a
client working with an attorney and employer questions only for an employer-type client,
**with no per-service component anywhere**. Adding a service is an entry in `serviceCatalog.ts`
pointing at group ids. No screen changes.

**`clientType` is seeded into the answers pool from the account, not only held on it.** The
deleted `Questionnaire.tsx` did this deliberately and the comment explains why: groups whose
`showIf` reads `answers.clientType` cannot see a value that lives only on the account record.
Preserve that seeding; it is the one non-obvious line in the screen.

**The engine stays on the client and the server re-checks.** Required-ness is a display rule
the client evaluates to decide what to render; `POST /applications/{id}/submit` re-runs
`isGroupComplete` server-side over the same catalog and refuses an incomplete submission. A
visibility rule enforced only in a browser is not enforced.

**That means the catalog is shared, not duplicated.** `serviceCatalog.ts` and
`questionGroups.ts` become the seed for a server-side copy, and a test asserts the two agree on
service ids, group ids and required flags. Two hand-maintained copies of the same catalog is
the failure this unit is most likely to ship.

---

## 4. The account — and the lead — are created at step 4, not at the end

**About You creates the `client_account`** (Unit 42's table) with email, password and terms —
the fields `schemas/intake.ts` already validates. Steps 5–7 then run signed in.

This is the one substantive UX change from the deleted version, and the argument is resumption:
a client who abandons on the documents step has, by then, chosen a service, answered fifteen
questions and typed their history. If the account is created at submit, all of that is in
`localStorage` on one browser and is gone when they come back on their phone. Creating it at
step 4 means the answers live on a server row keyed to an account, and signing in again lands
them exactly where they stopped.

**So the dashboard gains one state**: an application in progress, with a *Continue* button. A
signed-in client with a `DRAFT` application and no case sees that instead of an empty portal.

---

## 5. Documents

Uploads go through the route Unit 30 already built, streaming to
`{brandId}/client/{clientAccountId}/{documentId}`.

**Keyed on the EvalOS account id, not on `ghl_contact_id`** — which is the prefix Unit 30
documents. At this point in the funnel there is no GHL contact yet: it is created at submit,
one step later. Keying the prefix on an id that does not exist until after the files are
written is not possible, and keying it on the account is also what keeps the documents readable
if the contact is later deleted or the CRM replaced. `00c` inherits this as the pattern.

Which documents are asked for comes from the service's `documentTemplates`. Nothing is
mandatory to submit: a missing document is a thing Sales chases, not a wall the funnel puts in
front of a lead.

---

## 6. Submit

```
POST /api/portal/applications                   create a DRAFT   (step 4, at account creation)
PUT  /api/portal/applications/{id}              save answers     (steps 5–6, autosaved)
POST /api/portal/applications/{id}/submit       the one that writes to GHL
```

**The lead reaches GHL at step 4, not here.** That is the 2026-09-12 correction and it is the
most important change in this spec.

### 6a. The opportunity is created at About You, before the questionnaire

**An earlier draft created the contact and opportunity at submit. That was wrong, and the reason
is a business one rather than a technical one:** nothing reached GHL until the final screen, so
**every lead who abandoned mid-questionnaire vanished entirely** — no contact, no opportunity, no
one to follow up, no record that a real person had tried. The questionnaire is the longest part
of the funnel and therefore exactly where people stop.

So `POST /api/portal/applications` — fired when About You is submitted, the same call that creates
the account — does all of this:

1. Create the `client_account` (Unit 42) if the client is new, or load it if signing in.
2. **`GhlWriteClient.upsertContact`** — or skip it and reuse `ghl_contact_id` when it is non-null.
3. **`GhlWriteClient.upsertOpportunity`** on the intake pipeline, **at the hot stage**.
4. Create the `client_application` row as `DRAFT`, carrying the returned ids.

**Why hot immediately, with no questions answered yet.** Arriving at the portal and starting an
application *is* the qualification signal — this is inbound, self-selected demand, not a scraped
list. The earlier draft argued that completing the funnel was the signal; that argument
described a lead who is already converting, which is too late to be useful to a salesperson.

**The earliest possible moment is About You, and that is a hard floor, not a preference.** GHL's
contact upsert matches on email then phone, and `MarketingLeadService:49` refuses a lead with
neither — *"with neither, upsert has nothing to match on and every submission creates another
contact."* Before About You the funnel holds a service id and a purpose, and no way to identify a
human. So "create the opportunity first" means *first thing it is possible to do*.

### 6b. What submit does now

Submit is no longer the moment GHL hears about the lead. It:

1. **Re-validates** the answers server-side against the catalog (§3). Refuses incomplete.
2. Marks the application `SUBMITTED` and commits.
3. Files the answers and the document list as an **`OpportunityNote`**, so Sales reads them in the
   stream EvalOS owns.

**The stage does not move on submit.** It is already hot. A submitted application differs from a
draft one in `status`, and that is what the staff screen (§6c) sorts on.

### 6c. Sales and Production read the answers — the one staff-side screen

**Required by the flow, not an extra.** "Sales reviews and contacts the client" is a step in the
middle of both paths, and a review step with nowhere to read the thing being reviewed does not
exist. Decided 2026-09-12: **the questionnaire answers and the uploaded documents are visible to
Sales *and* to Production team members.**

- **Sales** needs them to price the work and answer the client.
- **Production** (PM, PC, CM) needs them because they are the same answers the case will be
  worked from — re-keying them after Handoff A would be a second copy to disagree with the first.

One read-only panel in the staff app: the answers rendered from the same catalog the portal
renders (§3, one source), and the documents as presigned links through the route Unit 30 already
built. **No editing.** A staff member correcting a client's answer creates a version of the truth
the client never gave, and the client is the only authority on what they answered.

**This is the only part of Unit 43 that touches `frontend/`** (the staff app); everything else is
`client-expert/client/` and the backend. Called out because the unit otherwise reads as
portal-only, and a reviewer will notice the odd file out.

**EvalOS mirrors GHL's stage id verbatim, against a mirrored stage table.** Decided 2026-09-12,
and this **supersedes the 2026-09-11 position** in this file, which had a `stage_name` column
sitting beside the id. That was a workaround for a problem the mirror removes: the argument was
that an opaque GHL id means nothing once GHL is gone, which is true **only if EvalOS has no
stage table.** It has one as of this unit, so the id resolves locally and the extra column is
redundant.

**So this unit carries the pipeline half of `00c`'s tier-1 mirror**, two small tables:

```sql
pipeline        id uuid pk, brand_id, ghl_id unique, name, position, synced_at
pipeline_stage  id uuid pk, brand_id, pipeline_id, ghl_id unique, name, position, synced_at
```

**They are here rather than in Unit 44 because this unit cannot work without them** — "move it
to the hot stage" requires knowing which stage is hot — and because they are nearly free:
`GhlPipelineClient.pipelines()` already returns `Pipeline(id, name, List<Stage>)` with
`Stage(id, name, position)`, so one call that is already being made fills both tables. Adding a
`stage_name` column here and deleting it at 44 would be more work than doing it once.

**Same ids on both sides, which is the point.** There is no mapping table and no parallel EvalOS
stage vocabulary — the same reasoning that keeps a valuation in GHL's `monetaryValue` rather than
an EvalOS column. A stage comparison between the two systems is then an equality check rather
than a translation, which is what makes a mismatch detectable (`00c` §2a).

```
evalos.ghl.intake-pipeline-name    which pipeline a portal application lands in
evalos.ghl.hot-stage-name          which stage on it means qualified
```

Matched **by name**, like the four pipeline settings already are, and for the same reason: the
id is opaque and the name is what whoever provisions GHL can see. A name that does not resolve
**fails the submit loudly** rather than filing a qualified lead in whatever stage GHL defaults
to, where nobody is looking for it.

**With GHL absent, steps 4 and 5 are skipped and step 2 still happened.** The application is
`SUBMITTED`, the stage name reads `Hot`, and the stage id is null until a contact is created.
That is what "EvalOS moves it to hot itself" means concretely, and it is the smallest form of
independence that is actually testable.

**`MarketingLeadService.openLead` is NOT reused, and this is the correction that matters.**
It looked like the obvious call — it is exactly these two upserts in exactly this order — but
its first line is `scope.mine()`, which reads `TenantContext.current().ghlPipelineId()`: the
**staff member's** assigned pipeline. A portal request carries a `PortalPrincipal` and no
`TenantContext`, so `openLead` throws `ForbiddenException("You have no GHL pipeline assigned")`
on every submission. `PipelineScope` is Unit 36's staff-authorization model and **none of it
applies on the portal chain**, where there are no roles at all.

**The pipeline is fetched, not assigned.** `GhlPipelineClient.pipelines()` already lists the
location's pipelines; the intake pipeline is matched by name from
`evalos.ghl.intake-pipeline-name`, the same by-name convention the other three pipeline
settings use and for the same reason — the id is opaque and the name is what whoever
provisions GHL can see. A name that does not resolve fails the submit loudly rather than
filing the lead somewhere nobody looks.

---

## 7. GHL is downstream of the truth, not the truth

**EvalOS's own rows commit whether or not GHL answers.** If GHL is unreachable,
rate-limited or mis-provisioned when About You is submitted, the `client_account` and the
`client_application` still exist, the client still walks the questionnaire, and their documents
still reach S3. `ghl_contact_id` and `ghl_opportunity_id` stay null and a retry fills them in.
The same holds at submit: the application reaches `SUBMITTED` and the client sees "under review"
regardless.

**A lead with null GHL ids is not lost, it is un-pushed** — it is on the staff screen (§6c) with
every answer intact, and Sales can work it by hand while the ids are backfilled. That is the
difference this ordering buys, and it is the whole reason §6a moved the GHL calls earlier rather
than making them mandatory.

This inverts the order every other GHL write in EvalOS uses, and it is deliberate. Elsewhere a
staff member is at a keyboard and can retry a failed write themselves. Here the client has
finished and closed the tab, and **"your submission was lost because a third party was down"
is not a sentence this business can send.**

**Both GHL calls are upserts**, so a retry matches the existing contact rather than duplicating
it — which `39`'s spec already established is the recoverability argument for upsert over
create, not a tidiness one. Retry is a staff action on the application row; no automatic
retry loop, consistent with *"writes do not retry"* (invariant 2), and for the same reason:
EvalOS has no idempotency key scheme, and a blind retry is how one opportunity becomes two.

---

## 8. Data model

```sql
client_application
  id uuid pk, brand_id uuid not null,
  client_account_id uuid not null,
  service_id text not null, purpose text,
  status text not null,               -- DRAFT | SUBMITTED | QUALIFIED | WITHDRAWN
  answers jsonb not null default '{}',
  ghl_opportunity_id text null,       -- a LINK, filled after submit, nullable forever
  pipeline_id uuid null,              -- FK to the mirrored pipeline (§6a), not a GHL id
  stage_id uuid null,                 -- FK to the mirrored stage — resolves without GHL
  created_at, updated_at, submitted_at
```

**`pipeline_id` and `stage_id` are foreign keys into EvalOS's own mirror, not GHL strings.**
The mirror row carries GHL's id, so the GHL value is one join away and is never duplicated here
— and the application still reads correctly with GHL switched off, which a bare GHL id column
would not.

**`client_application` IS the EvalOS-owned opportunity for a portal-born lead**, and there is
deliberately **no separate `lead` table in this unit.** The client's contact record is
`client_account` (Unit 42) and the deal record is this row — together they are the "create a
contact in EvalOS" half of the 2026-09-11 decision, with the three `ghl_*` columns as links.
A `lead` table here would hold exactly one row per application and do nothing until Unit 44.

**Unit 44 decides whether GHL-born leads share this table or get their own**, once the inbound
`contact.created` payload has been read. A GHL lead has no service and no answers, so forcing it
in here is the likely wrong answer — but that is a decision for the unit that can see the
payload, not a guess made now.

**`answers` is `jsonb`, not a table per question.** The shape is defined by the catalog and
changes whenever a service is added; a normalised `application_answer` table would add a join
and a migration per catalog edit to store what is read whole, every time, by one screen.

**`status` has four values and none of them is a case stage.** An application is not a case and
must never grow a lifecycle that looks like one — that is how a second case state machine gets
built by accident. `QUALIFIED` means Sales accepted it and sent an invoice; the case appears
later, through Handoff A, and is a different row entirely.

---

## 9. Invariant impact

**Invariant 8 — untouched, and this is the invariant this unit is most suspected of breaking.**
Invariant 8's own text names this funnel as *"the first real pressure"* on it. The pressure is
answered rather than pushed through: `client_application` is **not** a case, holds no case
stage, gets no `case_code`, and `ClientApplicationService` has no dependency on
`CaseIntakeService` — which `DomainInvariantsTest` already enforces structurally and which this
unit must not weaken. A case still appears only when `opportunity.won` arrives on the webhook.

**The order of events is worth writing down, because it is the whole design:** client submits →
EvalOS application → GHL opportunity → Sales qualifies → GHL invoice → client pays → GHL fires
`opportunity.won` → **Handoff A creates the case, paid.** Payment is after submission, and
qualification is a human step by a salesperson.

**Invariant 1 — held.** `client_application` is brand-scoped; every query filters `brand_id`.

**Invariant 7 — as amended by Unit 42.** EvalOS asks GHL to create a contact and stores the id
it returns; it still mints none.

**Unit 36's role model is not involved.** No `SALES` or `MARKETING` role, no `Segment`, no
`Tier.PIPELINE`, no `PipelineScope`, no `evalos.ghl.sales-brand`. Stated explicitly because
§6's two GHL writes look exactly like the marketing desk's and the reflex is to reuse its
service.

---

## 10. What this unit deliberately does not do

- **No payment.** The client pays a GHL invoice link Sales sends. Invoicing is still GHL's
  (invariant 2's surviving half), and Unit 41 reads invoices without offering a pay action.
- **No pricing or quoting in the funnel.** A service's price depends on turnaround and document
  count and is a sales conversation.
- **No automatic qualification.** A human salesperson decides. There is no rules engine here.
- **No status email.** The portal shows the state; Unit 42's mail channel is for authentication
  only and §4 of that spec is where that boundary is drawn.
- **`FillDemoDataButton` and `demoData.ts` do not come back.** They were scaffolding for a mock
  funnel and this one reaches a real CRM.

---

## 11. Acceptance criteria

1. A visitor completes all seven steps and the application reaches `SUBMITTED`, with a contact
   and an opportunity visible in GHL location `WY6bW2xUCI8Tz8gw7aLJ` and the answers on a note.
1b. **A visitor who abandons immediately after About You still leaves a GHL contact and an
   opportunity at the hot stage.** This is the criterion the 2026-09-12 reordering exists for; if
   it passes only after the questionnaire, the change was not made.
1c. A returning client whose `ghl_contact_id` is non-null gets a **second opportunity on the same
   contact**, never a second contact.
1d. A returning client whose `ghl_contact_id` is null (every seeded client after the CRM
   replacement) takes the create-a-contact branch without error.
1e. A returning client who clicks **Get started** rather than Sign in is recognised by
   `identify` and does not become a duplicate contact.
2. Abandoning at step 6 and signing in again from a different browser resumes with the answers
   intact.
3. Choosing a service whose groups include a conditional one shows those questions only when
   the `showIf` holds — asserted on the engine, not through the DOM.
4. A submit missing a required visible answer is refused **by the server**, with the client-side
   check disabled in the test.
5. A test fails if the server catalog and `serviceCatalog.ts` disagree on service ids, group ids
   or required flags.
6. With GHL unreachable, submit still returns 200, the application is `SUBMITTED`, `stage_id`
   points at the mirrored hot stage, and `ghl_opportunity_id` is null.
7. Submitting twice does not create a second contact or a second opportunity.
8. A submitted application lands on the **hot** stage in GHL, not the pipeline's default, and
   the mirrored `pipeline_stage` row it points at carries the same `ghl_id` GHL reports.
9. A `hot-stage-name` that does not resolve on the intake pipeline fails the submit with a
   message naming the stage — it does not fall back to a default stage.
10. `DomainInvariantsTest` still fails the build if `ClientApplicationService` reaches
    `CaseIntakeService`.
11. A Sales user and a Production user (PM/PC/CM) can both open a submitted application's answers
    and documents; neither can edit an answer, and the route offers no write path at all.
12. The staff panel renders from the **same catalog** the portal renders — the §3 agreement test
    covers both, so a question added in one appears in the other with no second edit.
